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
 * @program: 大麦-ai智能服务项目。 添加 阿星不是程序员 微信，添加时备注 ai 来获取项目的完整资料
 * @description: Rerank重排序服务 - DashScope qwen3-rerank 精排 + keyword fallback
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class RerankService {

    @Value("${damai.ai.rerank.model:qwen3-rerank}")
    private String rerankModel;

    @Value("${damai.ai.rerank.api-url:https://dashscope.aliyuncs.com/api/v1/services/rerank}")
    private String rerankApiUrl;

    @Value("${damai.ai.rerank.top-n:10}")
    private int rerankTopN;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 主重排序入口：优先使用 DashScope Rerank API，失败时降级到关键词重排序。
     */
    public List<Document> rerank(String query, List<Document> documents, int topK) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }
        if (documents.size() <= 2) {
            return documents.subList(0, Math.min(topK, documents.size()));
        }
        try {
            return rerankWithDashScope(query, documents, topK);
        } catch (Exception e) {
            log.warn("DashScope Rerank失败，降级到关键词重排序", e);
            return rerankByKeyword(query, documents, topK);
        }
    }

    /**
     * DashScope Rerank API：调用 qwen3-rerank 模型对文档进行语义精排。
     * API 返回每个文档的 relevance_score，按分数降序截取 topK。
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
            throw new RuntimeException("DashScope Rerank API 请求失败", e);
        }

        if (response.statusCode() != 200) {
            throw new RuntimeException("DashScope Rerank API 返回 " + response.statusCode() + ": " + response.body());
        }

        JSONObject result = JSON.parseObject(response.body());
        JSONArray results = result.getJSONObject("output").getJSONArray("results");
        if (results == null || results.isEmpty()) {
            log.warn("DashScope Rerank 返回结果为空，降级到关键词重排序");
            return rerankByKeyword(query, documents, topK);
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
