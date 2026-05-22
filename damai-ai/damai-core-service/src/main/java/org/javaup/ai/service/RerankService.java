package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Rerank重排序服务 —— DashScope qwen3-rerank 精排 → LLM pointwise 降级 → keyword fallback.
 */
@Slf4j
@Service
public class RerankService {

    @Value("${damai.ai.rerank.model:qwen3-rerank}")
    private String rerankModel;

    @Value("${damai.ai.rerank.api-url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}")
    private String rerankApiUrl;

    @Value("${damai.ai.rerank.top-n:10}")
    private int rerankTopN;

    @Value("${damai.ai.rerank.api-key:}")
    private String apiKey;

    // Cross-encoder reranker config (bge-reranker-v2-m3 or similar, via TEI-compatible endpoint)
    @Value("${damai.ai.rerank.cross-encoder.enabled:false}")
    private boolean crossEncoderEnabled;

    @Value("${damai.ai.rerank.cross-encoder.api-url:}")
    private String crossEncoderApiUrl;

    @Value("${damai.ai.rerank.cross-encoder.model:bge-reranker-v2-m3}")
    private String crossEncoderModel;

    // DeepSeek / OpenAI-compatible config for LLM-based rerank fallback
    @Value("${spring.ai.openai.base-url}")
    private String llmBaseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String llmApiKey;

    @Value("${spring.ai.openai.chat.options.model:deepseek-v4-pro}")
    private String llmModel;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 主重排序入口: CrossEncoder → DashScope Rerank → LLM pointwise → keyword fallback。
     */
    public List<Document> rerank(String query, List<Document> documents, int topK) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }
        if (documents.size() <= 2) {
            return documents.subList(0, Math.min(topK, documents.size()));
        }
        // 1. Cross-encoder reranker (bge-reranker-v2-m3 via TEI-compatible API)
        if (crossEncoderEnabled && org.springframework.util.StringUtils.hasText(crossEncoderApiUrl)) {
            try {
                return rerankWithCrossEncoder(query, documents, topK);
            } catch (Exception e) {
                log.warn("CrossEncoder Rerank失败，降级到DashScope: {}", e.getMessage());
            }
        }
        // 2. DashScope 原生 Rerank API
        try {
            return rerankWithDashScope(query, documents, topK);
        } catch (Exception e) {
            log.warn("DashScope Rerank失败，降级到LLM重排序: {}", e.getMessage());
        }
        // 3. LLM pointwise 重排序
        try {
            return rerankWithLlm(query, documents, topK);
        } catch (Exception e) {
            log.warn("LLM Rerank失败，降级到关键词重排序: {}", e.getMessage());
        }
        // 4. 关键词重排序（最终兜底）
        return rerankByKeyword(query, documents, topK);
    }

    /**
     * Cross-encoder reranker using TEI-compatible API (bge-reranker-v2-m3).
     * TEI /rerank endpoint: POST {"query":"...", "texts":["...","..."], "truncate":true}
     * Returns {"scores":[0.9, 0.1, ...]} or [{"index":0,"score":0.9},...]
     */
    public List<Document> rerankWithCrossEncoder(String query, List<Document> documents, int topK) {
        int maxSnippet = 800;
        List<String> docTexts = new ArrayList<>();
        for (Document doc : documents) {
            String text = doc.getText();
            docTexts.add(text != null && text.length() > maxSnippet ? text.substring(0, maxSnippet) : text != null ? text : "");
        }

        JSONObject body = new JSONObject();
        body.put("query", query);
        body.put("texts", docTexts);
        body.put("truncate", true);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(crossEncoderApiUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("CrossEncoder API request failed: " + e.toString(), e);
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("CrossEncoder API returned " + response.statusCode());
        }

        JSONObject result = JSON.parseObject(response.body());
        List<ScoredDocument> scored = new ArrayList<>();

        // Support both TEI formats: array of scores, or array of {index, score}
        Object scoresField = result.get("scores");
        if (scoresField instanceof JSONArray scoresArray && !scoresArray.isEmpty()) {
            Object first = scoresArray.get(0);
            if (first instanceof Number) {
                for (int i = 0; i < scoresArray.size() && i < documents.size(); i++) {
                    scored.add(new ScoredDocument(documents.get(i), scoresArray.getDoubleValue(i)));
                }
            } else if (first instanceof JSONObject) {
                for (int i = 0; i < scoresArray.size(); i++) {
                    JSONObject item = scoresArray.getJSONObject(i);
                    int idx = item.getIntValue("index");
                    double s = item.getDoubleValue("score");
                    if (idx >= 0 && idx < documents.size()) {
                        scored.add(new ScoredDocument(documents.get(idx), s));
                    }
                }
            }
        }

        if (scored.isEmpty()) {
            throw new RuntimeException("CrossEncoder returned empty scores");
        }

        scored.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        log.info("CrossEncoder Rerank({})完成，原{}个文档，返回{}个，最高分{}",
                crossEncoderModel, documents.size(), Math.min(topK, scored.size()),
                scored.isEmpty() ? 0.0 : String.format("%.4f", scored.get(0).getScore()));

        return scored.stream()
                .limit(topK)
                .map(ScoredDocument::getDocument)
                .collect(Collectors.toList());
    }

    /**
     * DashScope Rerank API：调用 qwen3-rerank 模型对文档进行语义精排。
     */
    public List<Document> rerankWithDashScope(String query, List<Document> documents, int topK) {
        int maxSnippet = 800;
        List<String> docTexts = new ArrayList<>();
        for (Document doc : documents) {
            String text = doc.getText();
            if (text != null) {
                docTexts.add(text.length() > maxSnippet ? text.substring(0, maxSnippet) : text);
            } else {
                docTexts.add("");
            }
        }

        JSONObject body = new JSONObject();
        body.put("model", rerankModel);
        JSONObject input = new JSONObject();
        input.put("query", query);
        input.put("documents", docTexts);
        body.put("input", input);
        JSONObject parameters = new JSONObject();
        parameters.put("top_n", Math.min(rerankTopN, documents.size()));
        parameters.put("return_documents", false);
        body.put("parameters", parameters);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rerankApiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.error("DashScope Rerank API request failed: url={}, model={}, error={}",
                    rerankApiUrl, rerankModel, e.toString());
            throw new RuntimeException("DashScope Rerank API 请求失败", e);
        }

        if (response.statusCode() != 200) {
            String bodyPreview = response.body();
            if (bodyPreview != null && bodyPreview.length() > 500) {
                bodyPreview = bodyPreview.substring(0, 500);
            }
            log.error("DashScope Rerank API returned {}: url={}, model={}, response={}",
                    response.statusCode(), rerankApiUrl, rerankModel, bodyPreview);
            throw new RuntimeException("DashScope Rerank API returned " + response.statusCode());
        }

        JSONObject result = JSON.parseObject(response.body());
        JSONArray results = result.getJSONObject("output").getJSONArray("results");
        if (results == null || results.isEmpty()) {
            log.warn("DashScope Rerank 返回结果为空，降级到LLM重排序");
            return rerankWithLlm(query, documents, topK);
        }

        List<ScoredDocument> scored = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            int index = item.getIntValue("index");
            double score = item.getDoubleValue("relevance_score");
            if (index >= 0 && index < documents.size()) {
                scored.add(new ScoredDocument(documents.get(index), score));
            }
        }

        scored.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        log.info("DashScope Rerank({})完成，原{}个文档，返回{}个，最高分{}，最低分{}",
                rerankModel, documents.size(), Math.min(topK, scored.size()),
                scored.isEmpty() ? 0.0 : String.format("%.4f", scored.get(0).getScore()),
                scored.isEmpty() ? 0.0 : String.format("%.4f", scored.get(scored.size() - 1).getScore()));

        return scored.stream()
                .limit(topK)
                .map(ScoredDocument::getDocument)
                .collect(Collectors.toList());
    }

    /**
     * LLM pointwise 重排序：用 DeepSeek 批量为每个文档打分。
     * 一次 LLM 调用处理最多 20 个文档，超过则分批。
     */
    public List<Document> rerankWithLlm(String query, List<Document> documents, int topK) {
        int batchSize = 15;
        List<ScoredDocument> allScored = new ArrayList<>();

        for (int batchStart = 0; batchStart < documents.size(); batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, documents.size());
            List<Document> batch = documents.subList(batchStart, batchEnd);

            StringBuilder docsBlock = new StringBuilder();
            for (int i = 0; i < batch.size(); i++) {
                String text = batch.get(i).getText();
                if (text != null && text.length() > 600) {
                    text = text.substring(0, 600);
                }
                docsBlock.append("[").append(i + 1).append("] ").append(text != null ? text : "").append("\n\n");
            }

            String prompt = String.format("""
                    你是一个文档相关性评分专家。请根据用户查询，为每个文档的相关性打分（0.0-1.0）。
                    1.0 = 高度相关，直接回答了查询中的问题。
                    0.0 = 完全无关。

                    【用户查询】%s

                    【待评分文档】
                    %s

                    请输出JSON（不要Markdown包裹）：
                    {"scores": [0.85, 0.12, 0.93, ...]}

                    注意：scores数组长度必须等于文档数量（%d），按文档编号顺序对应。""",
                    query, docsBlock.toString(), batch.size());

            JSONObject body = new JSONObject();
            body.put("model", llmModel);
            body.put("temperature", 0.0);
            body.put("max_tokens", 512);
            JSONArray messages = new JSONArray();
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            messages.add(userMsg);
            body.put("messages", messages);

            String url = llmBaseUrl.replaceAll("/+$", "") + "/v1/chat/completions";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + llmApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                log.debug("LLM rerank response status={}, body={}", response.statusCode(),
                        response.body() != null ? response.body().substring(0, Math.min(200, response.body().length())) : "null");
                if (response.statusCode() != 200) {
                    log.warn("LLM rerank API error: status={}, body={}", response.statusCode(),
                            response.body() != null ? response.body().substring(0, Math.min(300, response.body().length())) : "null");
                    throw new RuntimeException("LLM rerank API error: " + response.statusCode());
                }
                JSONObject result = JSON.parseObject(response.body());
                JSONArray choices = result.getJSONArray("choices");
                if (choices == null || choices.isEmpty()) {
                    log.warn("LLM rerank: no choices in response, body={}",
                            response.body() != null ? response.body().substring(0, Math.min(300, response.body().length())) : "null");
                    throw new RuntimeException("LLM rerank returned no choices");
                }
                String content = choices.getJSONObject(0)
                        .getJSONObject("message").getString("content");
                if (content == null || content.isBlank()) {
                    // Check for refusal or finish_reason
                    String finishReason = choices.getJSONObject(0).getString("finish_reason");
                    log.warn("LLM rerank returned empty content, finish_reason={}, raw_response_preview={}",
                            finishReason,
                            response.body() != null ? response.body().substring(0, Math.min(500, response.body().length())) : "null");
                    throw new RuntimeException("LLM rerank returned empty content (finish_reason=" + finishReason + ")");
                }

                String jsonStr = content.trim().replaceAll("```\\w*\\n?", "").replaceAll("```", "").trim();
                JSONObject scoresJson = JSON.parseObject(jsonStr);
                JSONArray scores = scoresJson.getJSONArray("scores");
                if (scores == null || scores.size() != batch.size()) {
                    log.warn("LLM rerank scores count mismatch: expected={}, got={}, content={}",
                            batch.size(), scores != null ? scores.size() : 0,
                            content.length() > 200 ? content.substring(0, 200) : content);
                    // fallback: assign uniform scores
                    for (int i = 0; i < batch.size(); i++) {
                        allScored.add(new ScoredDocument(batch.get(i), 0.5));
                    }
                } else {
                    for (int i = 0; i < scores.size(); i++) {
                        allScored.add(new ScoredDocument(batch.get(i), scores.getDoubleValue(i)));
                    }
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                log.error("LLM rerank failed: {}", e.toString());
                throw new RuntimeException("LLM rerank failed", e);
            }
        }

        // Normalize scores across batches to ensure comparability (min-max normalization)
        if (documents.size() > batchSize && !allScored.isEmpty()) {
            double maxScore = allScored.stream().mapToDouble(ScoredDocument::getScore).max().orElse(1.0);
            double minScore = allScored.stream().mapToDouble(ScoredDocument::getScore).min().orElse(0.0);
            if (maxScore > minScore) {
                allScored = allScored.stream()
                        .map(sd -> new ScoredDocument(sd.getDocument(),
                                (sd.getScore() - minScore) / (maxScore - minScore)))
                        .collect(Collectors.toList());
            }
        }

        allScored.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        log.info("LLM Rerank({})完成，原{}个文档，返回{}个，最高分{}，最低分{}",
                llmModel, documents.size(), Math.min(topK, allScored.size()),
                allScored.isEmpty() ? 0.0 : String.format("%.4f", allScored.get(0).getScore()),
                allScored.isEmpty() ? 0.0 : String.format("%.4f", allScored.get(allScored.size() - 1).getScore()));

        return allScored.stream()
                .limit(topK)
                .map(ScoredDocument::getDocument)
                .collect(Collectors.toList());
    }

    /**
     * 基于关键词重叠度的降级重排序。
     */
    public List<Document> rerankByKeyword(String query, List<Document> documents, int topK) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }
        Set<String> queryKeywords = extractKeywords(query);
        List<ScoredDocument> scoredDocs = documents.stream()
                .map(doc -> {
                    String docText = doc.getText();
                    double score = (docText != null) ? computeRelevanceScore(queryKeywords, docText) : 0.0;
                    return new ScoredDocument(doc, score);
                })
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(topK)
                .collect(Collectors.toList());

        log.info("Keyword Rerank完成，原{}个文档，返回{}个", documents.size(), scoredDocs.size());

        return scoredDocs.stream()
                .map(ScoredDocument::getDocument)
                .collect(Collectors.toList());
    }

    private Set<String> extractKeywords(String text) {
        Set<String> keywords = new LinkedHashSet<>();
        for (String word : text.split("[\\s,，。？?！!]+")) {
            if (word.length() > 1) {
                keywords.add(word);
            }
        }
        String chinese = text.replaceAll("[^\\u4e00-\\u9fff]", "");
        for (int i = 0; i < chinese.length() - 1; i++) {
            keywords.add(chinese.substring(i, i + 2));
        }
        return keywords;
    }

    private double computeRelevanceScore(Set<String> queryKeywords, String content) {
        if (queryKeywords.isEmpty()) {
            return 0.0;
        }
        double score = 0.0;
        for (String keyword : queryKeywords) {
            if (content.contains(keyword)) {
                score += 1.0 + Math.log1p(countOccurrences(content, keyword));
            }
        }
        return score / queryKeywords.size();
    }

    private int countOccurrences(String text, String keyword) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(keyword, idx)) != -1) {
            count++;
            idx += keyword.length();
        }
        return count;
    }

    @Data
    @AllArgsConstructor
    private static class ScoredDocument {
        private Document document;
        private double score;
    }
}
