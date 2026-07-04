package org.javaup.ai.rag;

import org.javaup.ai.assistant.skill.knowledge.KnowledgeRetrievalPlan;
import org.javaup.ai.config.RetrievalPolicyProperties;
import org.javaup.ai.rag.channel.KnowledgeRetrievalFilter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RetrievalStrategyPolicy {

    private final RetrievalPolicyProperties properties;

    public RetrievalStrategyPolicy(RetrievalPolicyProperties properties) {
        this.properties = properties == null ? new RetrievalPolicyProperties() : properties;
    }

    public RetrievalStrategyPolicy() {
        this(new RetrievalPolicyProperties());
    }

    public RetrievalStrategy firstPass(KnowledgeRetrievalPlan plan, KnowledgeRetrievalFilter filter) {
        String query = plan == null ? "" : plan.normalizedQuery();
        boolean highRisk = isHighRisk(query);
        if (!StringUtils.hasText(query)) {
            return RetrievalStrategy.handoffOrClarify(highRisk, "empty query");
        }
        if (plan.complexity() == KnowledgeRetrievalPlan.Complexity.SIMPLE && !highRisk) {
            return RetrievalStrategy.fastExact(plan.topK(), "low-risk fast exact query");
        }
        boolean rerank = plan.enableRerank() && plan.topK() >= Math.max(1, properties.getRerankMinTopK());
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
        int topK = plan.topK() * Math.max(1, properties.getCorrectiveTopKMultiplier());
        return RetrievalStrategy.enhancedRecovery(topK, highRisk, enableHyde,
                "corrective retrieval uses missing-slot rewrite, step-back and structured support");
    }

    public boolean shouldUseHyde(String query, String missingInfo) {
        String text = ((query == null ? "" : query) + " " + (missingInfo == null ? "" : missingInfo)).trim();
        if (!StringUtils.hasText(text)) return false;
        boolean hasBusinessKey = properties.getBusinessKeyTerms().stream().anyMatch(text::contains);
        boolean abstractRule = properties.getAbstractRuleTerms().stream().anyMatch(text::contains);
        return abstractRule && !hasBusinessKey;
    }

    public boolean isHighRisk(String query) {
        if (!StringUtils.hasText(query)) return false;
        return properties.getHighRiskTerms().stream().anyMatch(query::contains);
    }
}
