package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.cache.EmbeddingCacheService;
import org.javaup.ai.entity.RagChunk;
import org.javaup.ai.mapper.RagChunkMapper;
import org.javaup.ai.rag.channel.KnowledgeRetrievalFilter;
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
import java.util.stream.Collectors;

/**
 * Low-level RAG search backend used by retrieval channels and the retrieval facade.
 */
@Slf4j
@Service
public class RagSearchBackendService {

    private final OpenAiEmbeddingModel embeddingModel;
    private final AdvancedQueryService advancedQueryService;
    private final QdrantClient qdrantClient;
    private final EmbeddingCacheService embeddingCacheService;
    private final RagChunkMapper chunkMapper;
    private final DocumentIngestionService documentIngestionService;
    private final EsClientHelper esClient;

    @Value("${damai.ai.faq.alias:damai-ai-faq-current}")
    private String faqAlias;

    @Value("${damai.ai.retrieval.min-vector-similarity:0.45}")
    private double minVectorSimilarity;

    public RagSearchBackendService(OpenAiEmbeddingModel embeddingModel,
                                   AdvancedQueryService advancedQueryService,
                                   QdrantClient qdrantClient,
                                   EmbeddingCacheService embeddingCacheService,
                                   RagChunkMapper chunkMapper,
                                   DocumentIngestionService documentIngestionService,
                                   EsClientHelper esClient) {
        this.embeddingModel = embeddingModel;
        this.advancedQueryService = advancedQueryService;
        this.qdrantClient = qdrantClient;
        this.embeddingCacheService = embeddingCacheService;
        this.chunkMapper = chunkMapper;
        this.documentIngestionService = documentIngestionService;
        this.esClient = esClient;
    }

    public List<RagSourceVo> hydeSearch(String query, int topK) {
        return hydeSearch(query, topK, KnowledgeRetrievalFilter.empty());
    }

    public List<RagSourceVo> hydeSearch(String query, int topK, KnowledgeRetrievalFilter filter) {
        try {
            String hypothetical = advancedQueryService.generateHypotheticalDocument(query);
            List<RagSourceVo> sources = denseSearch(hypothetical, topK, filter);
            sources.forEach(source -> source.setChannelName("hyde"));
            return sources;
        } catch (Exception e) {
            log.warn("HyDE search failed, falling back to dense search", e);
            List<RagSourceVo> sources = denseSearch(query, topK, filter);
            sources.forEach(source -> source.setChannelName("hyde"));
            return sources;
        }
    }

    public List<Document> resolveDocuments(List<RagSourceVo> sources) {
        return resolveDocumentsFromAllSources(sources);
    }

    public List<RagSourceVo> multiQueryDenseSearch(List<String> queries, int topK) {
        return multiQueryDenseSearch(queries, topK, KnowledgeRetrievalFilter.empty());
    }

    public List<RagSourceVo> multiQueryDenseSearch(List<String> queries, int topK, KnowledgeRetrievalFilter filter) {
        if (queries.size() <= 1) {
            return denseSearch(queries.isEmpty() ? "" : queries.get(0), topK, filter);
        }
        Map<String, RagSourceVo> merged = new LinkedHashMap<>();
        Map<String, Double> bestScores = new HashMap<>();
        for (String q : queries) {
            List<RagSourceVo> results = denseSearch(q, topK, filter);
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

    public List<RagSourceVo> denseSearch(String query, int topK) {
        return denseSearch(query, topK, KnowledgeRetrievalFilter.empty());
    }

    public List<RagSourceVo> denseSearch(String query, int topK, KnowledgeRetrievalFilter filter) {
        try {
            float[] vector = embeddingCacheService.get(query);
            if (vector == null) {
                vector = embeddingModel.embed(query);
                embeddingCacheService.put(query, vector);
            }
            List<Float> vectorList = new ArrayList<>(vector.length);
            for (float v : vector) {
                vectorList.add(v);
            }

            KnowledgeRetrievalFilter effectiveFilter = filter == null ? KnowledgeRetrievalFilter.empty() : filter;
            SearchPoints.Builder searchBuilder = SearchPoints.newBuilder()
                            .setCollectionName(documentIngestionService.qdrantSearchAlias())
                            .addAllVector(vectorList)
                            .setLimit(effectiveLimit(topK, effectiveFilter))
                            .setScoreThreshold((float) minVectorSimilarity)
                            .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(true));
            Points.Filter qdrantFilter = buildQdrantFilter(effectiveFilter);
            if (qdrantFilter != null) {
                searchBuilder.setFilter(qdrantFilter);
            }
            List<ScoredPoint> scoredPoints = qdrantClient.searchAsync(searchBuilder.build()).get();

            List<RagSourceVo> sources = new ArrayList<>();
            for (ScoredPoint point : scoredPoints) {
                Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payloadMap = point.getPayloadMap();
                if (!matchesFilter(payloadMap, effectiveFilter)) {
                    continue;
                }
                RagSourceVo source = buildSourceFromGrpc(payloadMap, (double) point.getScore());
                source.setChannelName("dense");
                String parentBlockId = grpcString(payloadMap, "parentBlockId");
                if (parentBlockId != null) {
                    source.setParentBlockId(parentBlockId);
                }
                sources.add(source);
                if (sources.size() >= topK) {
                    break;
                }
            }
            return sources;
        } catch (Exception ex) {
            log.warn("Qdrant dense search failed, returning empty results", ex);
            return List.of();
        }
    }

    public List<RagSourceVo> sparseSearch(String query, int topK) {
        return sparseSearch(query, topK, KnowledgeRetrievalFilter.empty());
    }

    public List<RagSourceVo> sparseSearch(String query, int topK, KnowledgeRetrievalFilter filter) {
        try {
            KnowledgeRetrievalFilter effectiveFilter = filter == null ? KnowledgeRetrievalFilter.empty() : filter;
            JSONObject multiMatch = new JSONObject();
            multiMatch.put("query", query);
            multiMatch.put("fields", List.of("searchText^4", "question^3", "keywords^2", "text^2", "docTitle^1"));

            JSONObject boolQuery = new JSONObject(Map.of("must", List.of(
                    Map.of("multi_match", multiMatch)
            )));
            boolQuery.put("filter", buildEsFilters(effectiveFilter));

            JSONObject body = new JSONObject();
            body.put("size", effectiveLimit(topK, effectiveFilter));
            body.put("query", boolQuery);

            JSONObject response = esClient.execute("/" + faqAlias + "/_search", body.toJSONString(), "POST");
            JSONObject hitsObj = response.getJSONObject("hits");
            if (hitsObj == null) {
                return List.of();
            }
            JSONArray hits = hitsObj.getJSONArray("hits");
            if (hits == null) {
                return List.of();
            }

            List<RagSourceVo> sources = new ArrayList<>();
            for (int i = 0; i < hits.size(); i++) {
                JSONObject hit = hits.getJSONObject(i);
                JSONObject source = hit.getJSONObject("_source");
                source.put("chunkId", source.getString("chunkId"));
                if (!matchesFilter(source, effectiveFilter)) {
                    continue;
                }
                RagSourceVo vo = buildSource(source, hit.getDouble("_score"));
                vo.setChannelName("sparse");
                String parentBlockId = source.getString("parentBlockId");
                if (parentBlockId != null) {
                    vo.setParentBlockId(parentBlockId);
                }
                sources.add(vo);
                if (sources.size() >= topK) {
                    break;
                }
            }
            return sources;
        } catch (Exception ex) {
            log.warn("Elasticsearch sparse search failed, returning empty results", ex);
            return List.of();
        }
    }

    private List<Document> resolveDocumentsFromAllSources(List<RagSourceVo> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        ensureDocumentsLoaded();
        Map<String, Document> cache = documentIngestionService.getDocumentCache();
        List<Document> documents = new ArrayList<>();
        for (RagSourceVo source : sources) {
            Document cached = cache.get(source.getChunkId());
            if (cached != null) {
                documents.add(cached);
                continue;
            }
            RagChunk chunk = chunkMapper.selectByChunkUid(source.getChunkId());
            if (chunk != null && StringUtils.hasText(chunk.getText())) {
                Map<String, Object> meta = new HashMap<>();
                if (StringUtils.hasText(chunk.getMetadataJson())) {
                    try {
                        Map<String, Object> parsed = JSON.parseObject(chunk.getMetadataJson(),
                                new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
                        meta.putAll(parsed);
                    } catch (Exception ignored) {
                    }
                }
                meta.put("chunkId", chunk.getChunkUid());
                meta.put("chunkType", chunk.getChunkType());
                meta.put("question", chunk.getQuestion());
                meta.put("headingPath", chunk.getHeadingPath());
                documents.add(new Document(chunk.getText(), meta));
            }
        }
        return documents;
    }

    private void ensureDocumentsLoaded() {
        if (documentIngestionService.getDocumentCache().isEmpty()) {
            documentIngestionService.cacheDocuments(documentIngestionService.loadMarkdownsForCache());
        }
    }

    private RagSourceVo buildSource(JSONObject payload, Double score) {
        String text = payload.getString("text");
        Long validUntil = payload.getLong("validUntil");
        Integer version = payload.getInteger("version");
        return RagSourceVo.builder()
                .chunkId(payload.getString("chunkId"))
                .title(payload.getString("title"))
                .source(payload.getString("source"))
                .section(payload.getString("section"))
                .snippet(text == null ? "" : text.substring(0, Math.min(200, text.length())))
                .score(score)
                .parentBlockId(payload.getString("parentBlockId"))
                .validUntil(validUntil)
                .version(version)
                .scope(firstText(payload, "scope", "label", "fm_scope"))
                .topic(firstText(payload, "topic", "docTitle", "fm_topic"))
                .documentId(firstText(payload, "documentId", "sourceFile", "fm_document"))
                .audience(firstText(payload, "audience", "fm_audience"))
                .region(firstText(payload, "region", "fm_region"))
                .docStatus(firstText(payload, "docStatus", "fm_doc_status"))
                .build();
    }

    private RagSourceVo buildSourceFromGrpc(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payloadMap,
                                            Double score) {
        String text = grpcString(payloadMap, "text");
        Long validUntil = grpcLong(payloadMap, "validUntil");
        Integer version = grpcInt(payloadMap, "version");
        return RagSourceVo.builder()
                .chunkId(grpcString(payloadMap, "chunkId"))
                .title(grpcString(payloadMap, "title"))
                .source(grpcString(payloadMap, "source"))
                .section(grpcString(payloadMap, "section"))
                .snippet(text == null ? "" : text.substring(0, Math.min(200, text.length())))
                .score(score)
                .parentBlockId(grpcString(payloadMap, "parentBlockId"))
                .validUntil(validUntil)
                .version(version)
                .scope(firstGrpcString(payloadMap, "scope", "label", "fm_scope"))
                .topic(firstGrpcString(payloadMap, "topic", "docTitle", "fm_topic"))
                .documentId(firstGrpcString(payloadMap, "documentId", "sourceFile", "fm_document"))
                .audience(firstGrpcString(payloadMap, "audience", "fm_audience"))
                .region(firstGrpcString(payloadMap, "region", "fm_region"))
                .docStatus(firstGrpcString(payloadMap, "docStatus", "fm_doc_status"))
                .build();
    }

    private int effectiveLimit(int topK, KnowledgeRetrievalFilter filter) {
        int safeTopK = Math.max(1, topK);
        if (filter != null && filter.hasAnyConstraint()) {
            return Math.min(100, Math.max(safeTopK, safeTopK * 4));
        }
        return safeTopK;
    }

    private Points.Filter buildQdrantFilter(KnowledgeRetrievalFilter filter) {
        if (filter == null || !filter.hasAnyConstraint()) {
            return null;
        }
        Points.Filter.Builder builder = Points.Filter.newBuilder();
        addQdrantKeyword(builder, "scope", filter.getScope());
        addQdrantKeyword(builder, "topic", filter.getTopic());
        addQdrantKeyword(builder, "audience", filter.getAudience());
        addQdrantKeyword(builder, "region", filter.getRegion());
        addQdrantKeyword(builder, "channel", filter.getChannel());
        addQdrantKeyword(builder, "userScope", filter.getUserScope());
        List<String> documentIds = filter.normalizedDocumentIds();
        if (documentIds.size() == 1) {
            addQdrantKeyword(builder, "documentId", documentIds.get(0));
        }
        return builder.getMustCount() == 0 ? null : builder.build();
    }

    private void addQdrantKeyword(Points.Filter.Builder builder, String field, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        builder.addMust(Points.Condition.newBuilder()
                .setField(Points.FieldCondition.newBuilder()
                        .setKey(field)
                        .setMatch(Points.Match.newBuilder().setKeyword(value.trim()).build())
                        .build())
                .build());
    }

    private List<Object> buildEsFilters(KnowledgeRetrievalFilter filter) {
        List<Object> filters = new ArrayList<>();
        long validAt = filter != null && filter.getValidAt() != null ? filter.getValidAt() : System.currentTimeMillis();
        filters.add(Map.of("bool", Map.of("should", List.of(
                Map.of("bool", Map.of("must_not", Map.of("exists", Map.of("field", "validUntil")))),
                Map.of("range", Map.of("validUntil", Map.of("gte", validAt)))
        ), "minimum_should_match", 1)));
        if (filter == null) {
            return filters;
        }
        addEsTerm(filters, "scope", filter.getScope());
        addEsTerm(filters, "topic", filter.getTopic());
        addEsTerm(filters, "audience", filter.getAudience());
        addEsTerm(filters, "region", filter.getRegion());
        addEsTerm(filters, "channel", filter.getChannel());
        addEsTerm(filters, "userScope", filter.getUserScope());
        if (!filter.normalizedDocumentIds().isEmpty()) {
            filters.add(Map.of("terms", Map.of("documentId", filter.normalizedDocumentIds())));
        }
        return filters;
    }

    private void addEsTerm(List<Object> filters, String field, String value) {
        if (StringUtils.hasText(value)) {
            filters.add(Map.of("term", Map.of(field, value.trim())));
        }
    }

    private boolean matchesFilter(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payloadMap,
                                  KnowledgeRetrievalFilter filter) {
        if (filter == null || !filter.hasAnyConstraint()) {
            return true;
        }
        return matchesFilterValue(firstGrpcString(payloadMap, "scope", "label", "fm_scope"), filter.getScope())
                && matchesFilterValue(firstGrpcString(payloadMap, "topic", "docTitle", "fm_topic"), filter.getTopic())
                && matchesDocument(firstGrpcString(payloadMap, "documentId", "sourceFile", "fm_document"), filter.normalizedDocumentIds())
                && matchesFilterValue(firstGrpcString(payloadMap, "audience", "fm_audience"), filter.getAudience())
                && matchesFilterValue(firstGrpcString(payloadMap, "region", "fm_region"), filter.getRegion())
                && matchesFilterValue(firstGrpcString(payloadMap, "channel", "fm_channel"), filter.getChannel())
                && matchesFilterValue(firstGrpcString(payloadMap, "userScope", "fm_user_scope"), filter.getUserScope())
                && !isExpired(grpcLong(payloadMap, "validUntil"), filter.getValidAt());
    }

    private boolean matchesFilter(JSONObject payload, KnowledgeRetrievalFilter filter) {
        if (filter == null || !filter.hasAnyConstraint()) {
            return true;
        }
        return matchesFilterValue(firstText(payload, "scope", "label", "fm_scope"), filter.getScope())
                && matchesFilterValue(firstText(payload, "topic", "docTitle", "fm_topic"), filter.getTopic())
                && matchesDocument(firstText(payload, "documentId", "sourceFile", "fm_document"), filter.normalizedDocumentIds())
                && matchesFilterValue(firstText(payload, "audience", "fm_audience"), filter.getAudience())
                && matchesFilterValue(firstText(payload, "region", "fm_region"), filter.getRegion())
                && matchesFilterValue(firstText(payload, "channel", "fm_channel"), filter.getChannel())
                && matchesFilterValue(firstText(payload, "userScope", "fm_user_scope"), filter.getUserScope())
                && !isExpired(payload.getLong("validUntil"), filter.getValidAt());
    }

    private boolean matchesFilterValue(String actual, String expected) {
        return !StringUtils.hasText(expected)
                || (StringUtils.hasText(actual) && actual.equalsIgnoreCase(expected.trim()));
    }

    private boolean matchesDocument(String actual, List<String> expectedIds) {
        return expectedIds == null || expectedIds.isEmpty()
                || (StringUtils.hasText(actual) && expectedIds.stream().anyMatch(id -> actual.equalsIgnoreCase(id)));
    }

    private boolean isExpired(Long validUntil, Long validAt) {
        return validUntil != null && validUntil > 0 && validUntil < (validAt == null ? System.currentTimeMillis() : validAt);
    }

    private String firstText(JSONObject payload, String... keys) {
        for (String key : keys) {
            String value = payload.getString(key);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String firstGrpcString(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> map, String... keys) {
        for (String key : keys) {
            String value = grpcString(map, key);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String grpcString(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> map, String key) {
        io.qdrant.client.grpc.JsonWithInt.Value value = map.get(key);
        if (value == null || !value.hasStringValue()) {
            return null;
        }
        return value.getStringValue();
    }

    private Long grpcLong(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> map, String key) {
        io.qdrant.client.grpc.JsonWithInt.Value value = map.get(key);
        if (value == null || !value.hasIntegerValue()) {
            return null;
        }
        return value.getIntegerValue();
    }

    private Integer grpcInt(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> map, String key) {
        io.qdrant.client.grpc.JsonWithInt.Value value = map.get(key);
        if (value == null || !value.hasIntegerValue()) {
            return null;
        }
        return (int) value.getIntegerValue();
    }
}
