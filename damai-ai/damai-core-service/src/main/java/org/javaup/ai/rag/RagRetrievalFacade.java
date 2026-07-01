package org.javaup.ai.rag;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.rag.channel.SearchContext;
import org.javaup.ai.rag.engine.MultiChannelRetrievalEngine;
import org.javaup.ai.service.AdvancedQueryService;
import org.javaup.ai.service.HybridSearchService;
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
    private static final String LEGACY_RESOLVER = "HybridSearchService.resolveDocuments";

    private final MultiChannelRetrievalEngine retrievalEngine;
    private final AdvancedQueryService advancedQueryService;
    private final HybridSearchService legacySearchService;

    public RagSearchResultVo retrieveSimple(String query, int topK) {
        SearchContext context = SearchContext.builder()
                .originalQuery(query)
                .rewrittenQuery(query)
                .queryVariants(List.of(query))
                .queryType(AdvancedQueryService.QueryType.MIXED)
                .topK(topK)
                .enableRerank(false)
                .now(System.currentTimeMillis())
                .metadata(Map.of("retrievalBoundary", BOUNDARY_NAME, "mode", "simple"))
                .build();
        return withResolvedDocuments(retrievalEngine.retrieveSimple(context), "simple", false);
    }

    public RagSearchResultVo retrieve(String query, int topK, boolean enableRerank) {
        AdvancedQueryService.QueryRewriteResult rewrite = advancedQueryService.rewriteQuery(query);
        SearchContext context = SearchContext.builder()
                .originalQuery(query)
                .rewrittenQuery(rewrite.primaryQuery())
                .queryVariants(rewrite.allQueries())
                .queryType(rewrite.queryType())
                .topK(topK)
                .enableRerank(enableRerank)
                .now(System.currentTimeMillis())
                .metadata(Map.of("retrievalBoundary", BOUNDARY_NAME, "mode", "full"))
                .build();
        return withResolvedDocuments(retrievalEngine.retrieve(context), "full", enableRerank);
    }

    public List<Document> resolveDocuments(RagSearchResultVo result) {
        return result == null ? List.of() : legacySearchService.resolveDocuments(result.getSources());
    }

    private RagSearchResultVo withResolvedDocuments(RagSearchResultVo result,
                                                    String mode,
                                                    boolean enableRerank) {
        if (result == null) {
            return null;
        }
        List<Document> documents = result.getDocuments();
        boolean resolvedByFacade = false;
        if (documents == null || documents.isEmpty()) {
            documents = legacySearchService.resolveDocuments(result.getSources());
            resolvedByFacade = true;
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
        metadata.put("documentResolver", LEGACY_RESOLVER);
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
}
