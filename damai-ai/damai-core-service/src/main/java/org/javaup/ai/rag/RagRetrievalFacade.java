package org.javaup.ai.rag;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.rag.channel.KnowledgeRetrievalFilter;
import org.javaup.ai.rag.channel.SearchContext;
import org.javaup.ai.rag.engine.MultiChannelRetrievalEngine;
import org.javaup.ai.service.AdvancedQueryService;
import org.javaup.ai.service.RagSearchBackendService;
import org.javaup.ai.service.SentenceWindowService;
import org.javaup.ai.vo.RagSearchResultVo;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RagRetrievalFacade {

    private static final String BOUNDARY_NAME = "RagRetrievalFacade";
    private static final String ENGINE_NAME = "MultiChannelRetrievalEngine";
    private static final String DOCUMENT_RESOLVER = "RagSearchBackendService.resolveDocuments";

    private final MultiChannelRetrievalEngine retrievalEngine;
    private final AdvancedQueryService advancedQueryService;
    private final RagSearchBackendService searchBackendService;
    private final SentenceWindowService sentenceWindowService;

    public RagSearchResultVo retrieve(String query, RetrievalStrategy strategy, KnowledgeRetrievalFilter filter) {
        RetrievalStrategy effectiveStrategy = strategy == null
                ? RetrievalStrategy.standardHybrid(5, true, false, "default facade strategy")
                : strategy;
        if (effectiveStrategy.profile() == RetrievalStrategyProfile.HANDOFF_OR_CLARIFY) {
            return RagSearchResultVo.builder()
                    .originalQuery(query)
                    .normalizedQuery(query)
                    .rewrittenQuery(query)
                    .documents(List.of())
                    .sources(List.of())
                    .metadata(Map.of("retrievalBoundary", BOUNDARY_NAME,
                            "strategyProfile", effectiveStrategy.profile().name(),
                            "strategyReason", effectiveStrategy.reason()))
                    .build();
        }
        AdvancedQueryService.QueryRewriteResult rewrite = effectiveStrategy.enableQueryRewrite()
                ? advancedQueryService.rewriteQuery(query)
                : new AdvancedQueryService.QueryRewriteResult(query, List.of(query));
        String primary = rewrite.primaryQuery();
        List<String> variants = rewrite.allQueries();
        if (effectiveStrategy.enableEntityExpansion()) {
            String expanded = advancedQueryService.expandWithEntities(primary);
            if (expanded != null && !expanded.equals(primary)) {
                primary = expanded;
                variants = appendVariant(variants, expanded);
            }
        }
        SearchContext context = SearchContext.builder()
                .originalQuery(query)
                .rewrittenQuery(primary)
                .queryVariants(variants)
                .queryType(rewrite.queryType())
                .topK(effectiveStrategy.topK())
                .enableRerank(effectiveStrategy.enableRerank())
                .strategy(effectiveStrategy)
                .now(System.currentTimeMillis())
                .filter(filter == null ? KnowledgeRetrievalFilter.empty() : filter)
                .metadata(Map.of("retrievalBoundary", BOUNDARY_NAME,
                        "mode", effectiveStrategy.profile().name(),
                        "strategyProfile", effectiveStrategy.profile().name(),
                        "strategyReason", effectiveStrategy.reason(),
                        "enabledChannels", effectiveStrategy.enabledChannels()))
                .build();
        return withResolvedDocuments(retrievalEngine.retrieve(context), effectiveStrategy.profile().name(),
                effectiveStrategy.enableRerank(), effectiveStrategy);
    }

    public List<Document> resolveDocuments(RagSearchResultVo result) {
        return result == null ? List.of() : searchBackendService.resolveDocuments(result.getSources());
    }

    private RagSearchResultVo withResolvedDocuments(RagSearchResultVo result,
                                                    String mode,
                                                    boolean enableRerank,
                                                    RetrievalStrategy strategy) {
        if (result == null) {
            return null;
        }
        List<Document> documents = result.getDocuments();
        boolean resolvedByFacade = false;
        if (documents == null || documents.isEmpty()) {
            documents = searchBackendService.resolveDocuments(result.getSources());
            resolvedByFacade = true;
        }
        if (strategy != null && strategy.enableSentenceWindow()) {
            documents = sentenceWindowService.expand(documents);
        }
        return rebuild(result, documents, mode, enableRerank, resolvedByFacade);
    }

    private RagSearchResultVo rebuild(RagSearchResultVo result,
                                      List<Document> documents,
                                      String mode,
                                      boolean enableRerank,
                                      boolean resolvedByFacade) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (result.getMetadata() != null) {
            metadata.putAll(result.getMetadata());
        }
        metadata.put("retrievalBoundary", BOUNDARY_NAME);
        metadata.put("retrievalMode", mode);
        metadata.put("retrievalEngine", ENGINE_NAME);
        metadata.put("documentResolver", DOCUMENT_RESOLVER);
        metadata.put("documentResolvedByFacade", resolvedByFacade);
        metadata.put("enableRerank", enableRerank);
        metadata.put("denseHitCount", size(result.getDenseSources()));
        metadata.put("sparseHitCount", size(result.getSparseSources()));
        metadata.put("fusedHitCount", size(result.getFusedSources()));
        metadata.put("finalHitCount", size(result.getSources()));
        return RagSearchResultVo.builder()
                .originalQuery(result.getOriginalQuery())
                .normalizedQuery(result.getNormalizedQuery())
                .rewrittenQuery(result.getRewrittenQuery())
                .retrievalTraceId(result.getRetrievalTraceId())
                .denseSources(result.getDenseSources())
                .sparseSources(result.getSparseSources())
                .fusedSources(result.getFusedSources())
                .sources(result.getSources())
                .documents(documents == null ? List.of() : documents)
                .confidenceScore(result.getConfidenceScore())
                .confidenceLevel(result.getConfidenceLevel())
                .correctiveAction(result.getCorrectiveAction())
                .metadata(metadata)
                .build();
    }

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private List<String> appendVariant(List<String> variants, String value) {
        if (variants == null || variants.isEmpty()) {
            return List.of(value);
        }
        if (variants.contains(value)) {
            return variants;
        }
        java.util.ArrayList<String> merged = new java.util.ArrayList<>(variants);
        merged.add(value);
        return merged;
    }
}
