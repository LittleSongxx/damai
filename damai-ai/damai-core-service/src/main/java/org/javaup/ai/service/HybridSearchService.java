package org.javaup.ai.service;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.cache.CacheManager;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.entity.AiRetrievalTrace;
import org.javaup.ai.entity.RagChunk;
import org.javaup.ai.mapper.AiRetrievalTraceMapper;
import org.javaup.ai.mapper.RagChunkMapper;
import org.javaup.ai.metrics.BusinessMetrics;
import org.javaup.ai.resilience.CircuitBreakerService;
import org.javaup.ai.resilience.DegradationService;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * FAQ hybrid search service: Qdrant dense + ES sparse + RRF + rerank.
 * Now backed by DocumentIngestionService for chunk metadata persistence
 * and Redis-persisted embedding cache.
 */
@Slf4j
@Service
public class HybridSearchService {

    private final OpenAiEmbeddingModel embeddingModel;
    private final RerankService rerankService;
    private final AdvancedQueryService advancedQueryService;
    private final ContextualCompressionService contextualCompressionService;
    private final QdrantClient qdrantClient;
    private final CacheManager cacheManager;
    private final CircuitBreakerService circuitBreakerService;
    private final DegradationService degradationService;
    private final BusinessMetrics businessMetrics;
    private final AiRetrievalTraceMapper retrievalTraceMapper;
    private final RagChunkMapper chunkMapper;
    private final DocumentIngestionService documentIngestionService;

    @Value("${damai.ai.qdrant.collection:damai_ai_faq}")
    private String qdrantCollection;

    @Value("${damai.ai.faq.alias:damai-ai-faq-current}")
    private String faqAlias;

    @Value("${DAMAI_ES_ADDR:127.0.0.1:19200}")
    private String esAddress;

    @Value("${DAMAI_ES_USERNAME:elastic}")
    private String esUsername;

    @Value("${DAMAI_ES_PASSWORD:elastic}")
    private String esPassword;

    @Value("${DAMAI_AI_OPENAI_EMBEDDING_DIMENSIONS:1024}")
    private Integer embeddingDimensions;

    @Value("${damai.ai.retrieval.min-vector-similarity:0.45}")
    private double minVectorSimilarity;

    @Value("${damai.ai.retrieval.keyword-relative-score-floor:0.35}")
    private double keywordRelativeScoreFloor;

    public HybridSearchService(OpenAiEmbeddingModel embeddingModel,
                                RerankService rerankService,
                                AdvancedQueryService advancedQueryService,
                                ContextualCompressionService contextualCompressionService,
                                QdrantClient qdrantClient,
                                CacheManager cacheManager,
                                CircuitBreakerService circuitBreakerService,
                                DegradationService degradationService,
                                BusinessMetrics businessMetrics,
                                AiRetrievalTraceMapper retrievalTraceMapper,
                                RagChunkMapper chunkMapper,
                                DocumentIngestionService documentIngestionService) {
        this.embeddingModel = embeddingModel;
        this.rerankService = rerankService;
        this.advancedQueryService = advancedQueryService;
        this.contextualCompressionService = contextualCompressionService;
        this.qdrantClient = qdrantClient;
        this.cacheManager = cacheManager;
        this.circuitBreakerService = circuitBreakerService;
        this.degradationService = degradationService;
        this.businessMetrics = businessMetrics;
        this.retrievalTraceMapper = retrievalTraceMapper;
        this.chunkMapper = chunkMapper;
        this.documentIngestionService = documentIngestionService;
    }

    // ======================== Ingestion (delegated) ========================

    public Map<String, Object> reindexAll() {
        return documentIngestionService.reindexAll();
    }

    public Map<String, Object> incrementalReindex() {
        return documentIngestionService.incrementalReindex();
    }

    public void cacheDocuments(List<Document> documents) {
        documentIngestionService.cacheDocuments(documents);
    }

    // ======================== Retrieval ========================

    public RagSearchResultVo hybridSearchWithTrace(String query, int topK, boolean enableRerank) {
        ensureDocumentsLoaded();

        String cacheKey = query + "#" + topK + "#" + enableRerank;
        String cached = cacheManager.getFaqSearch(cacheKey);
        if (cached != null) {
            return JSON.parseObject(cached, RagSearchResultVo.class);
        }

        AdvancedQueryService.QueryRewriteResult rewriteResult = advancedQueryService.rewriteQuery(query);
        String rewrittenQuery = rewriteResult.primaryQuery();

        List<RagSourceVo> denseSources = circuitBreakerService.executeQdrant(
                () -> multiQueryDenseSearch(rewriteResult.allQueries(), topK * 2),
                List.of());
        if (denseSources.isEmpty()) {
            degradationService.begin("dense_search").degradedTo("sparse_only");
        }

        List<RagSourceVo> sparseSources = circuitBreakerService.executeEs(
                () -> sparseSearch(rewrittenQuery, topK * 2),
                List.of());
        if (sparseSources.isEmpty()) {
            degradationService.begin("sparse_search").degradedTo("dense_only");
        }

        denseSources = gateDenseResults(denseSources);
        sparseSources = gateSparseResults(sparseSources);

        if (denseSources.isEmpty() && sparseSources.isEmpty()) {
            log.warn("Both dense and sparse search returned empty results for query: {}", query);
            return RagSearchResultVo.builder()
                    .originalQuery(query).normalizedQuery(query).rewrittenQuery(rewrittenQuery)
                    .documents(List.of()).sources(List.of()).build();
        }

        List<RagSourceVo> fusedSources = denseSources.isEmpty() ? sparseSources
                : sparseSources.isEmpty() ? shrink(denseSources, topK * 2)
                : mergeWithRrf(denseSources, sparseSources, topK * 2);
        List<RagSourceVo> finalSources = enableRerank
                ? rerankSources(rewrittenQuery, fusedSources, topK)
                : shrink(fusedSources, topK);

        // Resolve to Documents: try DB-backed chunk lookup first, then cache
        List<Document> documents = resolveDocumentsFromAllSources(finalSources);

        documents = contextualCompressionService.compress(query, new ArrayList<>(documents));

        persistRetrievalTrace(query, rewrittenQuery, rewriteResult, denseSources, sparseSources,
                fusedSources, finalSources, documents, topK, enableRerank);

        RagSearchResultVo result = RagSearchResultVo.builder()
                .originalQuery(query)
                .normalizedQuery(query)
                .rewrittenQuery(rewrittenQuery)
                .retrievalTraceId("retrieval_" + java.util.UUID.randomUUID().toString().replace("-", ""))
                .documents(documents)
                .denseSources(denseSources)
                .sparseSources(sparseSources)
                .fusedSources(fusedSources)
                .sources(finalSources)
                .build();

        cacheManager.putFaqSearch(cacheKey, JSON.toJSONString(result));
        return result;
    }

    public RagSearchResultVo hybridSearchWithHyde(String query, int topK, boolean enableRerank) {
        ensureDocumentsLoaded();

        AdvancedQueryService.QueryRewriteResult rewriteResult = advancedQueryService.rewriteQuery(query);
        String rewrittenQuery = rewriteResult.primaryQuery();

        String cacheKey = rewrittenQuery + "#" + topK + "#" + enableRerank;
        String cached = cacheManager.getFaqSearch(cacheKey);
        if (cached != null) {
            return JSON.parseObject(cached, RagSearchResultVo.class);
        }

        List<RagSourceVo> standardDenseSources = circuitBreakerService.executeQdrant(
                () -> multiQueryDenseSearch(rewriteResult.allQueries(), topK * 2),
                List.of());

        List<RagSourceVo> hydeSources = List.of();
        try {
            hydeSources = hydeSearch(query, topK * 2);
        } catch (Exception e) {
            log.warn("HyDE in first pass failed, continuing without it: {}", e.getMessage());
        }

        List<RagSourceVo> sparseSources = circuitBreakerService.executeEs(
                () -> sparseSearch(rewrittenQuery, topK * 2),
                List.of());

        if (standardDenseSources.isEmpty() && hydeSources.isEmpty() && sparseSources.isEmpty()) {
            log.warn("All search paths returned empty for query: {}", query);
            return RagSearchResultVo.builder()
                    .originalQuery(query).normalizedQuery(query).rewrittenQuery(rewrittenQuery)
                    .documents(List.of()).sources(List.of()).build();
        }

        Map<String, RagSourceVo> allDenseMap = new LinkedHashMap<>();
        for (RagSourceVo s : standardDenseSources) allDenseMap.put(s.getChunkId(), s);
        for (RagSourceVo s : hydeSources) allDenseMap.putIfAbsent(s.getChunkId(), s);
        List<RagSourceVo> allDenseSources = new ArrayList<>(allDenseMap.values());

        int denseBefore = allDenseSources.size();
        int sparseBefore = sparseSources.size();
        allDenseSources = gateDenseResults(allDenseSources);
        sparseSources = gateSparseResults(sparseSources);
        if (denseBefore != allDenseSources.size() || sparseBefore != sparseSources.size()) {
            log.debug("Evidence gating: dense {}→{}, sparse {}→{}",
                    denseBefore, allDenseSources.size(), sparseBefore, sparseSources.size());
        }

        List<RagSourceVo> fusedSources = sparseSources.isEmpty()
                ? shrink(allDenseSources, topK * 2)
                : RagFusionSupport.reciprocalRankFusion(allDenseSources, sparseSources, topK * 2);
        List<RagSourceVo> finalSources = enableRerank
                ? rerankSources(rewrittenQuery, fusedSources, topK)
                : shrink(fusedSources, topK);

        List<Document> documents = resolveDocumentsFromAllSources(finalSources);
        documents = contextualCompressionService.compress(query, new ArrayList<>(documents));

        RagSearchResultVo result = RagSearchResultVo.builder()
                .originalQuery(query)
                .normalizedQuery(query)
                .rewrittenQuery(rewrittenQuery)
                .retrievalTraceId("retrieval_" + java.util.UUID.randomUUID().toString().replace("-", ""))
                .documents(documents)
                .denseSources(allDenseSources)
                .sparseSources(sparseSources)
                .fusedSources(fusedSources)
                .sources(finalSources)
                .build();

        cacheManager.putFaqSearch(cacheKey, JSON.toJSONString(result));
        return result;
    }

    public List<Document> hybridSearch(String query, int topK, boolean enableRerank) {
        return hybridSearchWithTrace(query, topK, enableRerank).getDocuments();
    }

    public List<Document> hybridSearch(String query, int topK) {
        return hybridSearch(query, topK, true);
    }

    public List<String> searchChunkIds(String query, int topK) {
        return hybridSearch(query, topK).stream()
                .map(d -> d.getMetadata().getOrDefault("chunkId", d.getId()).toString())
                .toList();
    }

    public List<RagSourceVo> hydeSearch(String query, int topK) {
        try {
            String hypothetical = advancedQueryService.generateHypotheticalDocument(query);
            return denseSearch(hypothetical, topK);
        } catch (Exception e) {
            log.warn("HyDE search failed, falling back to dense search", e);
            return denseSearch(query, topK);
        }
    }

    public List<Document> resolveDocuments(List<RagSourceVo> sources) {
        if (sources == null || sources.isEmpty()) return List.of();
        ensureDocumentsLoaded();
        Map<String, Document> cache = documentIngestionService.getDocumentCache();
        return sources.stream()
                .map(s -> cache.get(s.getChunkId()))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Resolve documents: first try DB-backed chunk text, then fall back to Qdrant/ES metadata.
     * This ensures the retrieval path works even without a full cache reload.
     */
    private List<Document> resolveDocumentsFromAllSources(List<RagSourceVo> sources) {
        if (sources == null || sources.isEmpty()) return List.of();
        ensureDocumentsLoaded();
        Map<String, Document> cache = documentIngestionService.getDocumentCache();
        List<Document> documents = new ArrayList<>();
        for (RagSourceVo s : sources) {
            Document cached = cache.get(s.getChunkId());
            if (cached != null) {
                documents.add(cached);
                continue;
            }
            // Try DB-backed chunk
            RagChunk chunk = chunkMapper.selectByChunkUid(s.getChunkId());
            if (chunk != null && StringUtils.hasText(chunk.getText())) {
                Map<String, Object> meta = new HashMap<>();
                if (StringUtils.hasText(chunk.getMetadataJson())) {
                    try {
                        Map<String, Object> parsed = JSON.parseObject(chunk.getMetadataJson(),
                                new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
                        meta.putAll(parsed);
                    } catch (Exception ignored) {}
                }
                meta.put("chunkId", chunk.getChunkUid());
                meta.put("chunkType", chunk.getChunkType());
                meta.put("question", chunk.getQuestion());
                meta.put("headingPath", chunk.getHeadingPath());
                Document doc = new Document(chunk.getText(), meta);
                documents.add(doc);
            }
        }
        return documents;
    }

    public List<RagSourceVo> multiQueryDenseSearch(List<String> queries, int topK) {
        if (queries.size() <= 1) {
            return denseSearch(queries.isEmpty() ? "" : queries.get(0), topK);
        }
        Map<String, RagSourceVo> merged = new LinkedHashMap<>();
        Map<String, Double> bestScores = new HashMap<>();
        for (String q : queries) {
            List<RagSourceVo> results = denseSearch(q, topK);
            for (RagSourceVo source : results) {
                merged.putIfAbsent(source.getChunkId(), source);
                bestScores.merge(source.getChunkId(),
                        source.getScore() == null ? 0 : source.getScore(), Math::max);
            }
        }
        return merged.entrySet().stream()
                .sorted((a, b) -> Double.compare(
                        bestScores.getOrDefault(b.getKey(), 0D),
                        bestScores.getOrDefault(a.getKey(), 0D)))
                .limit(topK)
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    // ======================== Dense Search ========================

    public List<RagSourceVo> denseSearch(String query, int topK) {
        try {
            float[] vector = cacheManager.getEmbedding(query);
            if (vector == null) {
                vector = embeddingModel.embed(query);
                cacheManager.putEmbedding(query, vector);
            }
            List<Float> vectorList = new ArrayList<>(vector.length);
            for (float v : vector) vectorList.add(v);

            List<ScoredPoint> scoredPoints = qdrantClient.searchAsync(
                    SearchPoints.newBuilder()
                            .setCollectionName(qdrantCollection)
                            .addAllVector(vectorList)
                            .setLimit(topK)
                            .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(true))
                            .build()
            ).get();

            List<RagSourceVo> sources = new ArrayList<>();
            for (ScoredPoint point : scoredPoints) {
                Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payloadMap = point.getPayloadMap();
                RagSourceVo source = buildSourceFromGrpc(payloadMap, (double) point.getScore());
                // Enrich with parentBlockId from metadata
                String parentBlockId = grpcString(payloadMap, "parentBlockId");
                if (parentBlockId != null) {
                    source.setParentBlockId(parentBlockId);
                }
                sources.add(source);
            }
            return sources;
        } catch (Exception ex) {
            log.warn("Qdrant dense search failed, returning empty results", ex);
            return List.of();
        }
    }

    // ======================== Sparse Search ========================

    public List<RagSourceVo> sparseSearch(String query, int topK) {
        try {
            JSONObject multiMatch = new JSONObject();
            multiMatch.put("query", query);
            multiMatch.put("fields", List.of("searchText^4", "question^3", "keywords^2", "text^2", "docTitle^1"));

            JSONObject body = new JSONObject();
            body.put("size", topK);
            body.put("query", new JSONObject(Map.of("multi_match", multiMatch)));

            JSONObject response = executeEs("/" + faqAlias + "/_search", body.toJSONString(), "POST");
            JSONArray hits = response.getJSONObject("hits").getJSONArray("hits");
            if (hits == null) return List.of();

            List<RagSourceVo> sources = new ArrayList<>();
            for (int i = 0; i < hits.size(); i++) {
                JSONObject hit = hits.getJSONObject(i);
                JSONObject source = hit.getJSONObject("_source");
                source.put("chunkId", source.getString("chunkId"));
                RagSourceVo vo = buildSource(source, hit.getDouble("_score"));
                String parentBlockId = source.getString("parentBlockId");
                if (parentBlockId != null) {
                    vo.setParentBlockId(parentBlockId);
                }
                sources.add(vo);
            }
            return sources;
        } catch (Exception ex) {
            log.warn("Elasticsearch sparse search failed, returning empty results", ex);
            return List.of();
        }
    }

    // ======================== Private Helpers ========================

    private void ensureDocumentsLoaded() {
        if (documentIngestionService.getDocumentCache().isEmpty()) {
            // Delegate to DocumentIngestionService which uses the Spring-injected MarkdownLoader
            documentIngestionService.cacheDocuments(
                    documentIngestionService.loadMarkdownsForCache());
        }
    }

    private List<RagSourceVo> mergeWithRrf(List<RagSourceVo> denseSources, List<RagSourceVo> sparseSources, int topK) {
        return RagFusionSupport.reciprocalRankFusion(denseSources, sparseSources, topK);
    }

    private List<RagSourceVo> gateDenseResults(List<RagSourceVo> denseSources) {
        if (denseSources == null || denseSources.isEmpty() || minVectorSimilarity <= 0) {
            return denseSources != null ? denseSources : List.of();
        }
        return denseSources.stream()
                .filter(s -> s.getScore() != null && s.getScore() >= minVectorSimilarity)
                .toList();
    }

    private List<RagSourceVo> gateSparseResults(List<RagSourceVo> sparseSources) {
        if (sparseSources == null || sparseSources.isEmpty() || keywordRelativeScoreFloor <= 0) {
            return sparseSources != null ? sparseSources : List.of();
        }
        double topScore = sparseSources.stream()
                .filter(s -> s.getScore() != null)
                .mapToDouble(RagSourceVo::getScore)
                .max().orElse(0D);
        if (topScore <= 0) return sparseSources;
        double floor = topScore * keywordRelativeScoreFloor;
        return sparseSources.stream()
                .filter(s -> s.getScore() != null && s.getScore() >= floor)
                .toList();
    }

    private List<RagSourceVo> rerankSources(String query, List<RagSourceVo> fusedSources, int topK) {
        if (CollectionUtil.isEmpty(fusedSources)) return List.of();
        Map<String, Document> docMap = new LinkedHashMap<>();
        Map<String, Document> cache = documentIngestionService.getDocumentCache();
        for (RagSourceVo s : fusedSources) {
            Document doc = cache.get(s.getChunkId());
            if (doc == null) {
                RagChunk chunk = chunkMapper.selectByChunkUid(s.getChunkId());
                if (chunk != null && StringUtils.hasText(chunk.getText())) {
                    doc = new Document(chunk.getText(), Map.of("chunkId", chunk.getChunkUid()));
                }
            }
            if (doc != null) {
                docMap.putIfAbsent(s.getChunkId(), doc);
            }
        }
        if (docMap.isEmpty()) return shrink(fusedSources, topK);

        List<Document> reranked = rerankService.rerank(query, new ArrayList<>(docMap.values()), topK);
        if (CollectionUtil.isEmpty(reranked)) return shrink(fusedSources, topK);

        Map<String, RagSourceVo> sourceMap = fusedSources.stream()
                .collect(Collectors.toMap(RagSourceVo::getChunkId, s -> s, (a, b) -> a));
        return reranked.stream()
                .map(d -> d.getMetadata().get("chunkId") != null
                        ? sourceMap.get(String.valueOf(d.getMetadata().get("chunkId")))
                        : null)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<RagSourceVo> shrink(List<RagSourceVo> sources, int topK) {
        return RagFusionSupport.limit(sources, topK);
    }

    private void persistRetrievalTrace(String query, String rewrittenQuery,
                                        AdvancedQueryService.QueryRewriteResult rewriteResult,
                                        List<RagSourceVo> denseSources, List<RagSourceVo> sparseSources,
                                        List<RagSourceVo> fusedSources, List<RagSourceVo> finalSources,
                                        List<Document> documents, int topK, boolean enableRerank) {
        try {
            AiRetrievalTrace trace = new AiRetrievalTrace();
            trace.setRunId(AiRequestContextHolder.getOptional()
                    .map(ctx -> ctx.getRunId()).orElse(null));
            trace.setChatId(AiRequestContextHolder.getOptional()
                    .map(ctx -> ctx.getConversationId()).orElse(null));
            trace.setUserId(AiRequestContextHolder.getOptional()
                    .map(ctx -> ctx.getUser().getUserId()).orElse(null));
            trace.setTraceType("snapshot");
            trace.setStepKey("knowledge.hybrid_search");
            trace.setOriginalQuery(query);
            trace.setRewrittenQuery(rewrittenQuery);
            trace.setDenseHitsJson(JSON.toJSONString(denseSources));
            trace.setSparseHitsJson(JSON.toJSONString(sparseSources));
            trace.setFusedHitsJson(JSON.toJSONString(fusedSources));
            trace.setFinalHitsJson(JSON.toJSONString(finalSources));
            trace.setMetadataJson(JSON.toJSONString(Map.of(
                    "topK", topK,
                    "enableRerank", enableRerank,
                    "queryVariants", rewriteResult.allQueries(),
                    "documentCount", documents.size()
            )));
            trace.setCreateTime(new java.util.Date());
            trace.setEditTime(new java.util.Date());
            trace.setStatus(1);
            if (trace.getTraceId() == null) {
                trace.setTraceId("retrieval_" + java.util.UUID.randomUUID().toString().replace("-", ""));
            }
            retrievalTraceMapper.insert(trace);
        } catch (Exception e) {
            log.warn("Failed to persist retrieval trace: {}", e.getMessage());
        }
    }

    // ======================== Source Builders ========================

    private RagSourceVo buildSource(JSONObject payload, Double score) {
        String text = payload.getString("text");
        return RagSourceVo.builder()
                .chunkId(payload.getString("chunkId"))
                .title(payload.getString("title"))
                .source(payload.getString("source"))
                .section(payload.getString("section"))
                .snippet(text == null ? "" : text.substring(0, Math.min(200, text.length())))
                .score(score)
                .build();
    }

    private RagSourceVo buildSourceFromGrpc(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payloadMap,
                                             Double score) {
        String text = grpcString(payloadMap, "text");
        return RagSourceVo.builder()
                .chunkId(grpcString(payloadMap, "chunkId"))
                .title(grpcString(payloadMap, "title"))
                .source(grpcString(payloadMap, "source"))
                .section(grpcString(payloadMap, "section"))
                .snippet(text == null ? "" : text.substring(0, Math.min(200, text.length())))
                .score(score)
                .build();
    }

    private String grpcString(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> map, String key) {
        io.qdrant.client.grpc.JsonWithInt.Value value = map.get(key);
        if (value == null || !value.hasStringValue()) return null;
        return value.getStringValue();
    }

    private JSONObject field(String type) {
        return new JSONObject(Map.of("type", type));
    }

    private JSONObject executeEs(String path, String body, String method) {
        String url = "http://" + esAddress + path;
        HttpRequest request = buildEsRequest(url, method, body)
                .header("Authorization", esAuthorization());
        String response = request.execute().body();
        return JSON.parseObject(response == null ? "{}" : response);
    }

    private HttpRequest buildEsRequest(String url, String method, String body) {
        HttpRequest request = switch (method) {
            case "PUT" -> HttpRequest.put(url);
            case "POST" -> HttpRequest.post(url);
            default -> HttpRequest.get(url);
        };
        return request.contentType(ContentType.JSON.getValue()).body(body);
    }

    private String esAuthorization() {
        return "Basic " + Base64.encode(esUsername + ":" + esPassword);
    }
}
