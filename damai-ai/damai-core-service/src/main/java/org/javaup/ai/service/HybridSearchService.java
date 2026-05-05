package org.javaup.ai.service;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
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
 */
@Slf4j
@Service
public class HybridSearchService {

    private final OpenAiEmbeddingModel embeddingModel;
    private final RerankService rerankService;
    private final MarkdownLoader markdownLoader;
    private final AiWorkflowService workflowService;

    @Value("${damai.ai.qdrant.url:http://127.0.0.1:16333}")
    private String qdrantUrl;

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

    private final Map<String, Document> documentCache = new ConcurrentHashMap<>();

    public HybridSearchService(OpenAiEmbeddingModel embeddingModel,
                               RerankService rerankService,
                               MarkdownLoader markdownLoader,
                               AiWorkflowService workflowService) {
        this.embeddingModel = embeddingModel;
        this.rerankService = rerankService;
        this.markdownLoader = markdownLoader;
        this.workflowService = workflowService;
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
        String rewrittenQuery = rewriteQuery(query);

        List<RagSourceVo> denseSources = denseSearch(rewrittenQuery, topK * 2);
        List<RagSourceVo> sparseSources = sparseSearch(rewrittenQuery, topK * 2);
        List<RagSourceVo> fusedSources = mergeWithRrf(denseSources, sparseSources, topK * 2);
        List<RagSourceVo> finalSources = enableRerank ? rerankSources(rewrittenQuery, fusedSources, topK) : shrink(fusedSources, topK);
        List<Document> documents = finalSources.stream()
                .map(source -> documentCache.get(source.getChunkId()))
                .filter(Objects::nonNull)
                .toList();

        AiRetrievalTrace trace = new AiRetrievalTrace();
        trace.setRunId(AiRequestContextHolder.getOptional().map(ctx -> ctx.getRunId()).orElse(null));
        trace.setChatId(AiRequestContextHolder.getOptional().map(ctx -> ctx.getConversationId()).orElse(null));
        trace.setUserId(AiRequestContextHolder.getOptional().map(ctx -> ctx.getUser().getUserId()).orElse(null));
        trace.setOriginalQuery(query);
        trace.setRewrittenQuery(rewrittenQuery);
        trace.setDenseHitsJson(JSON.toJSONString(denseSources));
        trace.setSparseHitsJson(JSON.toJSONString(sparseSources));
        trace.setFusedHitsJson(JSON.toJSONString(fusedSources));
        trace.setFinalHitsJson(JSON.toJSONString(finalSources));
        workflowService.saveRetrievalTrace(trace);

        return RagSearchResultVo.builder()
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
    }

    public List<Document> hybridSearch(String query, int topK, boolean enableRerank) {
        return hybridSearchWithTrace(query, topK, enableRerank).getDocuments();
    }

    public List<Document> hybridSearch(String query, int topK) {
        return hybridSearch(query, topK, true);
    }

    public String rewriteQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return query;
        }
        String rewritten = query;
        Map<String, String> synonymMap = Map.ofEntries(
                Map.entry("退票", "退票 退款 取消订单 条件退 手续费"),
                Map.entry("退款", "退款 退票 退钱 原路退回 到账"),
                Map.entry("买票", "买票 购票 订票 下单 抢票"),
                Map.entry("购票", "购票 买票 订票 下单 限购"),
                Map.entry("取消", "取消 作废 退订 延期"),
                Map.entry("演出", "演出 节目 表演 演唱会"),
                Map.entry("门票", "门票 票 入场券 票档"),
                Map.entry("实名", "实名 实名认证 身份证 证件 观演人"),
                Map.entry("观演人", "观演人 实名 入场人 证件"),
                Map.entry("电子票", "电子票 二维码 身份证电子票 数字票 票夹 换票"),
                Map.entry("数字票", "数字票 电子票 转赠 票夹"),
                Map.entry("转赠", "转赠 转票 赠送 数字票"),
                Map.entry("入场", "入场 安检 检票 场馆 证件核验"),
                Map.entry("安检", "安检 禁带 违禁品 摄录设备 液体"),
                Map.entry("儿童", "儿童票 儿童 亲子 身高 年龄 监护人"),
                Map.entry("配送", "配送 快递 收货地址 物流"),
                Map.entry("取票", "取票 自取 现场取票 换票"),
                Map.entry("支付", "支付 付款 超时 重复支付 支付失败"),
                Map.entry("订单", "订单 订单状态 支付超时 取消订单"),
                Map.entry("安全", "安全 防诈骗 验证码 私下交易 非官方渠道")
        );
        for (Map.Entry<String, String> entry : synonymMap.entrySet()) {
            if (query.contains(entry.getKey())) {
                rewritten = rewritten + " " + entry.getValue();
            }
        }
        return rewritten;
    }

    private void ensureDocumentsLoaded() {
        if (documentCache.isEmpty()) {
            cacheDocuments(markdownLoader.loadMarkdowns());
        }
    }

    private List<RagSourceVo> denseSearch(String query, int topK) {
        try {
            float[] vector = embeddingModel.embed(query);
            JSONObject request = new JSONObject();
            request.put("vector", vector);
            request.put("limit", topK);
            request.put("with_payload", true);
            JSONObject response = executeQdrant("/collections/" + qdrantCollection + "/points/search", request.toJSONString(), "POST");
            JSONArray result = response.getJSONArray("result");
            if (result == null) {
                return List.of();
            }
            List<RagSourceVo> sources = new ArrayList<>();
            for (int i = 0; i < result.size(); i++) {
                JSONObject item = result.getJSONObject(i);
                JSONObject payload = item.getJSONObject("payload");
                sources.add(buildSource(payload, item.getDouble("score")));
            }
            return sources;
        } catch (Exception ex) {
            log.warn("Qdrant dense 检索失败，回退为空结果", ex);
            return List.of();
        }
    }

    private List<RagSourceVo> sparseSearch(String query, int topK) {
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
        JSONObject body = new JSONObject();
        JSONObject vectors = new JSONObject();
        vectors.put("size", embeddingDimensions);
        vectors.put("distance", "Cosine");
        body.put("vectors", vectors);
        executeQdrant("/collections/" + qdrantCollection, body.toJSONString(), "PUT");
    }

    private int bulkUpsertQdrant(List<Document> documents) {
        JSONArray points = new JSONArray();
        for (Document document : documents) {
            String chunkId = chunkId(document);
            if (!StringUtils.hasText(chunkId) || !StringUtils.hasText(document.getText())) {
                continue;
            }
            JSONObject point = new JSONObject();
            point.put("id", chunkId);
            point.put("vector", embeddingModel.embed(document.getText()));
            JSONObject payload = new JSONObject(new HashMap<>(document.getMetadata()));
            payload.put("text", document.getText());
            payload.put("title", payload.getOrDefault("title", payload.getOrDefault("name", "FAQ")));
            point.put("payload", payload);
            points.add(point);
        }
        if (points.isEmpty()) {
            return 0;
        }
        JSONObject body = new JSONObject();
        body.put("points", points);
        executeQdrant("/collections/" + qdrantCollection + "/points?wait=true", body.toJSONString(), "PUT");
        return points.size();
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

    private JSONObject executeQdrant(String path, String body, String method) {
        String url = qdrantUrl + path;
        HttpRequest request = buildJsonRequest(url, method, body);
        String response = request.execute().body();
        return JSON.parseObject(response == null ? "{}" : response);
    }

    private JSONObject executeEs(String path, String body, String method) {
        String url = "http://" + esAddress + path;
        HttpRequest request = buildJsonRequest(url, method, body)
                .header("Authorization", esAuthorization());
        String response = request.execute().body();
        return JSON.parseObject(response == null ? "{}" : response);
    }

    private HttpRequest buildJsonRequest(String url, String method, String body) {
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

    private String chunkId(Document document) {
        Object value = document.getMetadata().get("chunkId");
        return value == null ? null : String.valueOf(value);
    }

    private record EsReindexResult(String physicalIndex, int documentCount) {
    }
}
