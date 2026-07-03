package org.javaup.ai.rag;

import org.javaup.ai.assistant.skill.knowledge.KnowledgeRetrievalPlan;
import org.javaup.ai.rag.channel.KnowledgeRetrievalFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalStrategyPolicyTest {

    private final RetrievalStrategyPolicy policy = new RetrievalStrategyPolicy();

    @Test
    void shouldUseFastExactOnlyForSimpleLowRiskQueries() {
        RetrievalStrategy strategy = policy.firstPass(plan("你好", KnowledgeRetrievalPlan.Complexity.SIMPLE, 4, false),
                KnowledgeRetrievalFilter.empty());

        assertEquals(RetrievalStrategyProfile.FAST_EXACT, strategy.profile());
        assertTrue(strategy.enableDense());
        assertTrue(strategy.enableSparse());
        assertFalse(strategy.enableHyde());
        assertFalse(strategy.enableRerank());
    }

    @Test
    void shouldUseStandardHybridForHighRiskCustomerServiceQueries() {
        RetrievalStrategy strategy = policy.firstPass(plan("开演前一天还能退票吗", KnowledgeRetrievalPlan.Complexity.MEDIUM, 10, true),
                KnowledgeRetrievalFilter.empty());

        assertEquals(RetrievalStrategyProfile.STANDARD_HYBRID, strategy.profile());
        assertTrue(strategy.highRisk());
        assertTrue(strategy.enableDense());
        assertTrue(strategy.enableSparse());
        assertTrue(strategy.enableQueryRewrite());
        assertTrue(strategy.enableEntityExpansion());
        assertTrue(strategy.enableSentenceWindow());
        assertTrue(strategy.enableParentElevation());
        assertTrue(strategy.enableRerank());
        assertFalse(strategy.enableHyde());
    }

    @Test
    void shouldUseEnhancedRecoveryWithHydeOnlyForAbstractRecoverableQuestions() {
        KnowledgeRetrievalPlan plan = plan("退票政策有什么限制", KnowledgeRetrievalPlan.Complexity.MEDIUM, 8, true);

        RetrievalStrategy strategy = policy.corrective(plan, "INCORRECT", "缺少适用条件",
                KnowledgeRetrievalFilter.empty());

        assertEquals(RetrievalStrategyProfile.ENHANCED_RECOVERY, strategy.profile());
        assertTrue(strategy.enableCorrectiveRetrieval());
        assertTrue(strategy.enableDense());
        assertTrue(strategy.enableSparse());
        assertTrue(strategy.enableHyde());
    }

    @Test
    void shouldAvoidHydeForBusinessKeyQueries() {
        assertFalse(policy.shouldUseHyde("订单支付失败怎么办", "缺少订单状态"));
        assertFalse(policy.shouldUseHyde("身份证填错可以入场吗", "缺少证件规则"));
        assertTrue(policy.shouldUseHyde("平台退票规则是什么", ""));
    }

    private KnowledgeRetrievalPlan plan(String query, KnowledgeRetrievalPlan.Complexity complexity, int topK, boolean rerank) {
        return new KnowledgeRetrievalPlan(query, query, topK, rerank, 6, 260, 4000,
                List.of(query), complexity);
    }
}
