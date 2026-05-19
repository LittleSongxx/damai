package org.javaup.ai.service;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.ai.rag.MarkdownLoader;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.entity.AiRetrievalTrace;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import org.javaup.ai.cache.CacheManager;
import org.javaup.ai.mapper.AiRetrievalTraceMapper;
import org.javaup.ai.metrics.BusinessMetrics;
import org.javaup.ai.resilience.CircuitBreakerService;
import org.javaup.ai.resilience.DegradationService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * FAQ 混合检索服务：Qdrant dense + ES sparse + RRF + rerank。
 *
 * ES 依赖说明：所有 ES 交互通过 Hutool HttpRequest 走 HTTP REST API
 * (/_search, /_bulk, /_aliases)，不依赖 elasticsearch-rest-high-level-client (HLRC)
 * 或 elasticsearch-java API client。Easy-ES 3.0.0 仅支持 ES 7.x 客户端库，
 * 升级 ES 服务端到 8.x 需同步迁移 Easy-ES 至 4.x。
 */
@Slf4j
@Service
public class HybridSearchService {

    private final OpenAiEmbeddingModel embeddingModel;
    private final RerankService rerankService;
    private final MarkdownLoader markdownLoader;
    private final AiRetrievalTraceMapper retrievalTraceMapper;
    private final AdvancedQueryService advancedQueryService;
    private final ContextualCompressionService contextualCompressionService;
    private final QdrantClient qdrantClient;
    private final CacheManager cacheManager;
    private final CircuitBreakerService circuitBreakerService;
    private final DegradationService degradationService;
    private final BusinessMetrics businessMetrics;

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

    private final Map<String, Document> documentCache = new ConcurrentHashMap<>();

    public HybridSearchService(OpenAiEmbeddingModel embeddingModel,
                               RerankService rerankService,
                               MarkdownLoader markdownLoader,
                               AiRetrievalTraceMapper retrievalTraceMapper,
                               AdvancedQueryService advancedQueryService,
                               ContextualCompressionService contextualCompressionService,
                               QdrantClient qdrantClient,
                               CacheManager cacheManager,
                               CircuitBreakerService circuitBreakerService,
                               DegradationService degradationService,
                               BusinessMetrics businessMetrics) {
        this.embeddingModel = embeddingModel;
        this.rerankService = rerankService;
        this.markdownLoader = markdownLoader;
        this.retrievalTraceMapper = retrievalTraceMapper;
        this.advancedQueryService = advancedQueryService;
        this.contextualCompressionService = contextualCompressionService;
        this.qdrantClient = qdrantClient;
        this.cacheManager = cacheManager;
        this.circuitBreakerService = circuitBreakerService;
        this.degradationService = degradationService;
        this.businessMetrics = businessMetrics;
    }

    public void cacheDocuments(List<Document> documents) {
        documentCache.clear();
        for (Document document : documents) {
            String chunkId = chunkId(document);
            if (StringUtils.hasText(chunkId)) {
                documentCache.put(chunkId, document);
            }
        }
        log.info("已缓存 {} 个 FAQ 文档片段", documentCache.size());
    }

    public Map<String, Object> reindexAll() {
        List<Document> documents = markdownLoader.loadMarkdowns();
        cacheDocuments(documents);
        recreateQdrantCollection();
        int qdrantPointCount = bulkUpsertQdrant(documents);
        EsReindexResult esReindexResult = recreateEsIndex(documents);
        MarkdownLoader.LoadStats loadStats = markdownLoader.getLastLoadStats();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", documents.size());
        result.put("fileCount", loadStats.fileCount());
        result.put("documentCount", loadStats.faqCount());
        result.put("chunkCount", documents.size());
        result.put("qdrantCollection", qdrantCollection);
        result.put("qdrantPointCount", qdrantPointCount);
        result.put("faqAlias", faqAlias);
        result.put("physicalIndex", esReindexResult.physicalIndex());
        result.put("esDocumentCount", esReindexResult.documentCount());
        result.put("skippedCount", loadStats.skippedCount());
        return result;
    }

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

        // Evidence gating: filter low-quality results before RRF fusion
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
        List<RagSourceVo> finalSources = enableRerank ? rerankSources(rewrittenQuery, fusedSources, topK) : shrink(fusedSources, topK);
        List<Document> documents = finalSources.stream()
                .map(source -> documentCache.get(source.getChunkId()))
                .filter(Objects::nonNull)
                .toList();
        documents = contextualCompressionService.compress(query, new ArrayList<>(documents));

        AiRetrievalTrace trace = new AiRetrievalTrace();
        trace.setRunId(AiRequestContextHolder.getOptional().map(ctx -> ctx.getRunId()).orElse(null));
        trace.setChatId(AiRequestContextHolder.getOptional().map(ctx -> ctx.getConversationId()).orElse(null));
        trace.setUserId(AiRequestContextHolder.getOptional().map(ctx -> ctx.getUser().getUserId()).orElse(null));
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

        RagSearchResultVo result = RagSearchResultVo.builder()
                .originalQuery(query)
                .normalizedQuery(query)
                .rewrittenQuery(rewrittenQuery)
                .retrievalTraceId(trace.getTraceId())
                .documents(documents)
                .denseSources(denseSources)
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
        List<Document> docs = hybridSearch(query, topK);
        return docs.stream()
                .map(d -> d.getMetadata().getOrDefault("chunkId", d.getId()).toString())
                .toList();
    }

    /**
     * HyDE 检索：生成假想文档，用假想文档的 embedding 做 dense search。
     */
    public List<RagSourceVo> hydeSearch(String query, int topK) {
        try {
            String hypothetical = advancedQueryService.generateHypotheticalDocument(query);
            return denseSearch(hypothetical, topK);
        } catch (Exception e) {
            log.warn("HyDE 检索失败，回退到普通 dense search", e);
            return denseSearch(query, topK);
        }
    }

    /** Resolve RagSourceVo references to full Document objects from the local cache. */
    public List<org.springframework.ai.document.Document> resolveDocuments(List<RagSourceVo> sources) {
        if (sources == null || sources.isEmpty()) return List.of();
        ensureDocumentsLoaded();
        return sources.stream()
                .map(s -> documentCache.get(s.getChunkId()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 增强混合检索：并行运行标准 dense + HyDE dense + sparse，三路 RRF 融合。
     * 将 HyDE 作为首轮检索的并行分支，而非纠正循环中的后备手段。
     */
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

        java.util.Map<String, RagSourceVo> allDenseMap = new java.util.LinkedHashMap<>();
        for (RagSourceVo s : standardDenseSources) allDenseMap.put(s.getChunkId(), s);
        for (RagSourceVo s : hydeSources) allDenseMap.putIfAbsent(s.getChunkId(), s);
        List<RagSourceVo> allDenseSources = new ArrayList<>(allDenseMap.values());

        // Evidence gating: filter low-quality results before RRF fusion
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
        List<Document> documents = finalSources.stream()
                .map(source -> documentCache.get(source.getChunkId()))
                .filter(Objects::nonNull)
                .toList();
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

    /**
     * 增量重索引：只更新 contentHash 发生变化的文档，跳过未变更的。
     */
    public Map<String, Object> incrementalReindex() {
        List<Document> allDocuments = markdownLoader.loadMarkdowns();
        List<Document> changedDocuments = new ArrayList<>();
        List<Document> unchangedDocuments = new ArrayList<>();
        for (Document doc : allDocuments) {
            String chunkId = chunkId(doc);
            Document cached = chunkId != null ? documentCache.get(chunkId) : null;
            if (cached == null || !Objects.equals(
                    cached.getMetadata().get("contentHash"),
                    doc.getMetadata().get("contentHash"))) {
                changedDocuments.add(doc);
            } else {
                unchangedDocuments.add(doc);
            }
        }
        cacheDocuments(allDocuments);
        int qdrantUpserted = 0;
        int esUpserted = 0;
        if (!changedDocuments.isEmpty()) {
            qdrantUpserted = bulkUpsertQdrant(changedDocuments);
            esUpserted = bulkUpsertEs(changedDocuments);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDocuments", allDocuments.size());
        result.put("changedDocuments", changedDocuments.size());
        result.put("unchangedDocuments", unchangedDocuments.size());
        result.put("qdrantUpserted", qdrantUpserted);
        result.put("esUpserted", esUpserted);
        result.put("indexVersion", markdownLoader.getCurrentIndexVersion());
        log.info("增量索引完成: total={}, changed={}, unchanged={}",
                allDocuments.size(), changedDocuments.size(), unchangedDocuments.size());
        return result;
    }

    /**
     * Multi-query dense search: 对多个查询变体分别做 dense search，合并去重。
     */
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
                bestScores.merge(source.getChunkId(), source.getScore() == null ? 0 : source.getScore(), Math::max);
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

    private void ensureDocumentsLoaded() {
        if (documentCache.isEmpty()) {
            cacheDocuments(markdownLoader.loadMarkdowns());
        }
    }

    public List<RagSourceVo> denseSearch(String query, int topK) {
        try {
            float[] vector = cacheManager.getEmbedding(query);
            if (vector == null) {
                vector = embeddingModel.embed(query);
                cacheManager.putEmbedding(query, vector);
            }
            List<Float> vectorList = new ArrayList<>(vector.length);
            for (float v : vector) {
                vectorList.add(v);
            }
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
                sources.add(buildSourceFromGrpc(payloadMap, (double) point.getScore()));
            }
            return sources;
        } catch (Exception ex) {
            log.warn("Qdrant dense 检索失败，回退为空结果", ex);
            return List.of();
        }
    }

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
            if (hits == null) {
                return List.of();
            }
            List<RagSourceVo> sources = new ArrayList<>();
            for (int i = 0; i < hits.size(); i++) {
                JSONObject hit = hits.getJSONObject(i);
                JSONObject source = hit.getJSONObject("_source");
                source.put("chunkId", source.getString("chunkId"));
                sources.add(buildSource(source, hit.getDouble("_score")));
            }
            return sources;
        } catch (Exception ex) {
            log.warn("Elasticsearch sparse 检索失败，回退为空结果", ex);
            return List.of();
        }
    }

    private List<RagSourceVo> mergeWithRrf(List<RagSourceVo> denseSources, List<RagSourceVo> sparseSources, int topK) {
        return RagFusionSupport.reciprocalRankFusion(denseSources, sparseSources, topK);
    }

    /**
     * Evidence gating: filter dense results below the minimum cosine similarity threshold.
     * Ensures only semantically relevant chunks enter RRF fusion.
     */
    private List<RagSourceVo> gateDenseResults(List<RagSourceVo> denseSources) {
        if (denseSources == null || denseSources.isEmpty() || minVectorSimilarity <= 0) {
            return denseSources != null ? denseSources : List.of();
        }
        return denseSources.stream()
                .filter(s -> s.getScore() != null && s.getScore() >= minVectorSimilarity)
                .toList();
    }

    /**
     * Evidence gating: filter sparse (BM25) results whose score is below a relative
     * percentage of the top result. Ensures only competitive keyword matches enter RRF.
     */
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
        if (CollectionUtil.isEmpty(fusedSources)) {
            return List.of();
        }
        Map<String, Document> docMap = fusedSources.stream()
                .map(source -> documentCache.get(source.getChunkId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(doc -> chunkId(doc), doc -> doc, (left, right) -> left, LinkedHashMap::new));
        List<Document> reranked = rerankService.rerank(query, new ArrayList<>(docMap.values()), topK);
        if (CollectionUtil.isEmpty(reranked)) {
            return shrink(fusedSources, topK);
        }
        Map<String, RagSourceVo> sourceMap = fusedSources.stream()
                .collect(Collectors.toMap(RagSourceVo::getChunkId, source -> source, (left, right) -> left));
        return reranked.stream()
                .map(this::chunkId)
                .map(sourceMap::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<RagSourceVo> shrink(List<RagSourceVo> sources, int topK) {
        return RagFusionSupport.limit(sources, topK);
    }

    private void recreateQdrantCollection() {
        try {
            qdrantClient.deleteCollectionAsync(qdrantCollection).get();
        } catch (Exception ignored) {
        }
        try {
            qdrantClient.createCollectionAsync(qdrantCollection,
                    VectorParams.newBuilder()
                            .setSize(embeddingDimensions)
                            .setDistance(Distance.Cosine)
                            .build()
            ).get();
            log.info("Qdrant collection '{}' 创建成功 (dim={}, Cosine)", qdrantCollection, embeddingDimensions);
        } catch (Exception ex) {
            log.error("Qdrant collection 创建失败", ex);
        }
    }

    private int bulkUpsertQdrant(List<Document> documents) {
        List<PointStruct> points = new ArrayList<>();
        for (Document document : documents) {
            String chunkId = chunkId(document);
            if (!StringUtils.hasText(chunkId) || !StringUtils.hasText(document.getText())) {
                continue;
            }
            float[] vector = embeddingModel.embed(document.getText());
            List<Float> vectorList = new ArrayList<>(vector.length);
            for (float v : vector) {
                vectorList.add(v);
            }
            Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payloadMap = new HashMap<>();
            for (Map.Entry<String, Object> entry : document.getMetadata().entrySet()) {
                if (entry.getValue() != null) {
                    payloadMap.put(entry.getKey(), io.qdrant.client.ValueFactory.value(String.valueOf(entry.getValue())));
                }
            }
            payloadMap.put("text", io.qdrant.client.ValueFactory.value(document.getText()));
            String title = document.getMetadata().getOrDefault("title",
                    document.getMetadata().getOrDefault("name", "FAQ")).toString();
            payloadMap.put("title", io.qdrant.client.ValueFactory.value(title));

            points.add(PointStruct.newBuilder()
                    .setId(io.qdrant.client.PointIdFactory.id(chunkId.hashCode() & 0xFFFFFFFFL))
                    .setVectors(io.qdrant.client.VectorsFactory.vectors(vectorList))
                    .putAllPayload(payloadMap)
                    .build());
        }
        if (points.isEmpty()) {
            return 0;
        }
        try {
            qdrantClient.upsertAsync(qdrantCollection, points).get();
        } catch (Exception ex) {
            log.error("Qdrant bulk upsert 失败", ex);
        }
        return points.size();
    }

    /**
     * 增量 upsert ES：只更新变更的文档而不重建整个索引。
     */
    private int bulkUpsertEs(List<Document> documents) {
        if (documents.isEmpty()) {
            return 0;
        }
        StringBuilder bulk = new StringBuilder();
        int count = 0;
        for (Document document : documents) {
            String cid = chunkId(document);
            if (!StringUtils.hasText(cid) || !StringUtils.hasText(document.getText())) {
                continue;
            }
            bulk.append(JSON.toJSONString(Map.of("index", Map.of("_index", faqAlias, "_id", cid)))).append('\n');
            Map<String, Object> source = new HashMap<>(document.getMetadata());
            source.put("chunkId", cid);
            source.put("title", source.getOrDefault("title", source.getOrDefault("name", "FAQ")));
            source.put("text", document.getText());
            source.putIfAbsent("searchText", document.getText());
            bulk.append(JSON.toJSONString(source)).append('\n');
            count++;
        }
        if (count > 0) {
            HttpRequest request = HttpRequest.post("http://" + esAddress + "/_bulk")
                    .header("Authorization", esAuthorization())
                    .header("Content-Type", "application/x-ndjson")
                    .body(bulk.toString());
            request.execute().body();
        }
        return count;
    }

    private EsReindexResult recreateEsIndex(List<Document> documents) {
        String physicalIndex = "damai-ai-faq-" + System.currentTimeMillis();
        JSONObject mapping = new JSONObject();
        JSONObject properties = new JSONObject();
        properties.put("chunkId", field("keyword"));
        properties.put("source", field("keyword"));
        properties.put("sourceFile", field("keyword"));
        properties.put("chunkType", field("keyword"));
        properties.put("label", field("keyword"));
        properties.put("indexVersion", field("keyword"));
        properties.put("docVersion", field("keyword"));
        properties.put("contentHash", field("keyword"));
        properties.put("title", field("text"));
        properties.put("docTitle", field("text"));
        properties.put("section", field("text"));
        properties.put("question", field("text"));
        properties.put("keywords", field("text"));
        properties.put("searchText", field("text"));
        properties.put("text", field("text"));
        mapping.put("properties", properties);
        executeEs("/" + physicalIndex, new JSONObject(Map.of("mappings", mapping)).toJSONString(), "PUT");

        StringBuilder bulk = new StringBuilder();
        int documentCount = 0;
        for (Document document : documents) {
            String chunkId = chunkId(document);
            if (!StringUtils.hasText(chunkId) || !StringUtils.hasText(document.getText())) {
                continue;
            }
            bulk.append(JSON.toJSONString(Map.of("index", Map.of("_index", physicalIndex, "_id", chunkId)))).append('\n');
            Map<String, Object> source = new HashMap<>(document.getMetadata());
            source.put("chunkId", chunkId);
            source.put("title", source.getOrDefault("title", source.getOrDefault("name", "FAQ")));
            source.put("text", document.getText());
            source.putIfAbsent("searchText", document.getText());
            bulk.append(JSON.toJSONString(source)).append('\n');
            documentCount++;
        }
        if (documentCount > 0) {
            HttpRequest request = HttpRequest.post("http://" + esAddress + "/_bulk")
                    .header("Authorization", esAuthorization())
                    .header("Content-Type", "application/x-ndjson")
                    .body(bulk.toString());
            String response = request.execute().body();
            log.info("ES bulk reindex response: {}", response);
        }

        JSONObject aliasBody = new JSONObject();
        JSONArray actions = new JSONArray();
        actions.add(new JSONObject(Map.of("remove", Map.of("index", "*", "alias", faqAlias, "ignore_unavailable", true))));
        actions.add(new JSONObject(Map.of("add", Map.of("index", physicalIndex, "alias", faqAlias))));
        aliasBody.put("actions", actions);
        executeEs("/_aliases", aliasBody.toJSONString(), "POST");
        return new EsReindexResult(physicalIndex, documentCount);
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

    private RagSourceVo buildSourceFromGrpc(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payloadMap, Double score) {
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
        if (value == null || !value.hasStringValue()) {
            return null;
        }
        return value.getStringValue();
    }

    private String chunkId(Document document) {
        Object value = document.getMetadata().get("chunkId");
        return value == null ? null : String.valueOf(value);
    }

    private record EsReindexResult(String physicalIndex, int documentCount) {
    }
}
