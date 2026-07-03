package org.javaup.ai.rag;

import java.util.List;

public record RetrievalStrategy(
        RetrievalStrategyProfile profile,
        int topK,
        long latencyBudgetMs,
        boolean enableDense,
        boolean enableSparse,
        boolean enableHyde,
        boolean enableRerank,
        boolean enableQueryRewrite,
        boolean enableEntityExpansion,
        boolean enableSentenceWindow,
        boolean enableParentElevation,
        boolean enableCorrectiveRetrieval,
        boolean highRisk,
        List<String> enabledChannels,
        String reason
) {
    public static RetrievalStrategy fastExact(int topK, String reason) {
        return new RetrievalStrategy(
                RetrievalStrategyProfile.FAST_EXACT,
                Math.max(1, topK),
                800,
                true,
                true,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                List.of("dense", "sparse"),
                reason
        );
    }

    public static RetrievalStrategy standardHybrid(int topK, boolean rerank, boolean highRisk, String reason) {
        return new RetrievalStrategy(
                RetrievalStrategyProfile.STANDARD_HYBRID,
                Math.max(3, topK),
                highRisk ? 2500 : 3200,
                true,
                true,
                false,
                rerank,
                true,
                true,
                true,
                true,
                true,
                highRisk,
                List.of("dense", "sparse"),
                reason
        );
    }

    public static RetrievalStrategy enhancedRecovery(int topK, boolean highRisk, boolean enableHyde, String reason) {
        return new RetrievalStrategy(
                RetrievalStrategyProfile.ENHANCED_RECOVERY,
                Math.max(5, topK),
                highRisk ? 4500 : 6500,
                true,
                true,
                enableHyde,
                true,
                true,
                true,
                true,
                true,
                true,
                highRisk,
                enableHyde ? List.of("dense", "sparse", "hyde") : List.of("dense", "sparse"),
                reason
        );
    }

    public static RetrievalStrategy handoffOrClarify(boolean highRisk, String reason) {
        return new RetrievalStrategy(
                RetrievalStrategyProfile.HANDOFF_OR_CLARIFY,
                0,
                0,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                highRisk,
                List.of(),
                reason
        );
    }
}
