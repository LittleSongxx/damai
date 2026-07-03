package org.javaup.ai.rag;

import org.javaup.ai.assistant.skill.knowledge.KnowledgeRetrievalPlan;
import org.javaup.ai.rag.channel.KnowledgeRetrievalFilter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class RetrievalStrategyPolicy {

    private static final List<String> HIGH_RISK_TERMS = List.of(
            "退票", "退款", "退钱", "实名", "身份证", "入场", "支付", "扣款", "订单", "发票", "投诉", "赔偿", "取消"
    );

    private static final List<String> ABSTRACT_RULE_TERMS = List.of(
            "规则", "政策", "限制", "条件", "流程", "怎么办", "如何", "能不能", "可以吗"
    );

    public RetrievalStrategy firstPass(KnowledgeRetrievalPlan plan, KnowledgeRetrievalFilter filter) {
        String query = plan == null ? "" : plan.normalizedQuery();
        boolean highRisk = isHighRisk(query);
        if (!StringUtils.hasText(query)) {
            return RetrievalStrategy.handoffOrClarify(highRisk, "empty query");
        }
        if (plan.complexity() == KnowledgeRetrievalPlan.Complexity.SIMPLE && !highRisk) {
            return RetrievalStrategy.fastExact(plan.topK(), "low-risk fast exact query");
        }
        boolean rerank = plan.enableRerank() && plan.topK() >= 6;
        String reason = highRisk ? "high-risk customer service question" : "standard customer service hybrid retrieval";
        return RetrievalStrategy.standardHybrid(plan.topK(), rerank, highRisk, reason);
    }

    public RetrievalStrategy corrective(KnowledgeRetrievalPlan plan,
                                        String correctiveAction,
                                        String missingInfo,
                                        KnowledgeRetrievalFilter filter) {
        String query = plan == null ? "" : plan.normalizedQuery();
        boolean highRisk = isHighRisk(query);
        if (!StringUtils.hasText(query)) {
            return RetrievalStrategy.handoffOrClarify(highRisk, "empty corrective query");
        }
        boolean recoverable = StringUtils.hasText(missingInfo)
                || "AMBIGUOUS".equalsIgnoreCase(correctiveAction)
                || "INCORRECT".equalsIgnoreCase(correctiveAction);
        if (highRisk && !recoverable) {
            return RetrievalStrategy.handoffOrClarify(true, "high-risk evidence is not recoverable");
        }
        boolean enableHyde = shouldUseHyde(query, missingInfo);
        return RetrievalStrategy.enhancedRecovery(plan.topK() * 2, highRisk, enableHyde,
                "corrective retrieval uses missing-slot rewrite, step-back and structured support");
    }

    public boolean shouldUseHyde(String query, String missingInfo) {
        String text = ((query == null ? "" : query) + " " + (missingInfo == null ? "" : missingInfo)).trim();
        if (!StringUtils.hasText(text)) return false;
        boolean hasBusinessKey = text.matches(".*(订单|手机号|身份证|二维码|票档|座位|场次|节目|支付|退款).*");
        boolean abstractRule = ABSTRACT_RULE_TERMS.stream().anyMatch(text::contains);
        return abstractRule && !hasBusinessKey;
    }

    public boolean isHighRisk(String query) {
        if (!StringUtils.hasText(query)) return false;
        return HIGH_RISK_TERMS.stream().anyMatch(query::contains);
    }
}
