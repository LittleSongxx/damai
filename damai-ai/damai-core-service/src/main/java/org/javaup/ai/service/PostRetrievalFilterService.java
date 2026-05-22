package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM-based post-retrieval filter that removes irrelevant documents before
 * they pollute the generation context. Targets Context Relevance metric.
 *
 * <p>Unlike ContextualCompressionService (which truncates/compresses document text),
 * this service rejects ENTIRE documents that the LLM judges as irrelevant to the
 * query, reducing noise at the document level rather than the sentence level.
 */
@Slf4j
@Service
public class PostRetrievalFilterService {

    private final ChatClient judgeClient;

    @Value("${damai.ai.retrieval.post-filter.enabled:true}")
    private boolean enabled;

    @Value("${damai.ai.retrieval.post-filter.relevance-threshold:0.35}")
    private double relevanceThreshold;

    @Value("${damai.ai.retrieval.post-filter.min-docs-to-keep:2}")
    private int minDocsToKeep;

    public PostRetrievalFilterService(@Qualifier("unifiedKnowledgeChatClient") ChatClient judgeClient) {
        this.judgeClient = judgeClient;
    }

    /**
     * Filter documents by LLM relevance judgment. Documents below the relevance
     * threshold are removed. Always keeps at least {@code minDocsToKeep} documents.
     */
    public List<Document> filter(String query, List<Document> documents) {
        if (!enabled || documents == null || documents.size() <= minDocsToKeep) {
            return documents != null ? documents : List.of();
        }
        try {
            return filterWithLLM(query, documents);
        } catch (Exception e) {
            log.warn("Post-retrieval LLM filter failed, returning original documents: {}", e.getMessage());
            return documents;
        }
    }

    /**
     * Unified filter + compress in a single LLM call: judges relevance AND
     * extracts key snippets from relevant documents. Reduces LLM round trips
     * from 2 (compress then filter) to 1.
     *
     * <p>For each document the LLM outputs:
     * <pre>{@code
     * [DOC_0] RELEVANT|0.85
     * 压缩后的关键内容...
     * [DOC_1] IRRELEVANT
     * }</pre>
     */
    public List<Document> filterAndCompress(String query, List<Document> documents) {
        if (!enabled || documents == null || documents.size() <= minDocsToKeep) {
            return documents != null ? documents : List.of();
        }
        try {
            return filterAndCompressWithLLM(query, documents);
        } catch (Exception e) {
            log.warn("Unified filter+compress failed, falling back to separate calls: {}", e.getMessage());
            // Fallback: still try individual filter
            return filter(query, documents);
        }
    }

    private List<Document> filterAndCompressWithLLM(String query, List<Document> documents) {
        StringBuilder docsBlock = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            String text = documents.get(i).getText();
            if (text == null) text = "";
            String snippet = text.length() > 500 ? text.substring(0, 500) + "..." : text;
            docsBlock.append("[").append(i).append("] ").append(snippet).append("\n\n");
        }

        String prompt = String.format("""
                你是检索质量优化专家。对每个文档，同时完成两项任务：
                1. 判断其与用户问题的相关度 (0.0-1.0)
                2. 从相关文档中提取与问题直接相关的关键句子（去除无关内容）

                【用户问题】%s

                【候选文档】
                %s

                请输出（不要Markdown包裹）：
                [DOC_0] RELEVANT|0.85
                提取的关键内容（保留原文措辞，只保留与问题相关的句子）
                [DOC_1] IRRELEVANT
                [DOC_2] RELEVANT|0.72
                提取的关键内容...

                规则：
                - 分数>=0.4标记为RELEVANT并提取关键内容，否则标记IRRELEVANT
                - IRRELEVANT的文档不需要提取内容
                - 保持原文措辞，不要改写
                - 每个文档的处理结果用 [DOC_N] 开头""", query, docsBlock.toString());

        String raw = judgeClient.prompt().user(prompt).call().content();
        if (raw == null || raw.isBlank()) return documents;

        return parseFilterAndCompressResult(raw, documents);
    }

    private List<Document> parseFilterAndCompressResult(String raw, List<Document> originalDocs) {
        List<Document> results = new ArrayList<>();
        String[] sections = raw.split("\\[DOC_");
        for (String section : sections) {
            if (section == null || section.isBlank()) continue;
            try {
                int bracketEnd = section.indexOf(']');
                if (bracketEnd <= 0) continue;
                int docIdx = Integer.parseInt(section.substring(0, bracketEnd).trim());
                if (docIdx < 0 || docIdx >= originalDocs.size()) continue;

                String content = section.substring(bracketEnd + 1).trim();
                boolean relevant = content.startsWith("RELEVANT");
                if (!relevant) continue;

                // Extract the relevance score and the compressed text
                int newlineIdx = content.indexOf('\n');
                String compressedText;
                if (newlineIdx > 0) {
                    compressedText = content.substring(newlineIdx + 1).trim();
                } else {
                    // Just the RELEVANT|score line, use original text
                    String[] parts = content.split("\\|");
                    compressedText = originalDocs.get(docIdx).getText();
                }

                if (compressedText.length() < 10) continue;

                Map<String, Object> meta = new HashMap<>(originalDocs.get(docIdx).getMetadata());
                meta.put("compressed", true);
                meta.put("filtered", true);
                meta.put("originalLength", originalDocs.get(docIdx).getText() != null
                        ? originalDocs.get(docIdx).getText().length() : 0);
                results.add(new Document(compressedText, meta));
            } catch (NumberFormatException ignored) {
            }
        }

        // Ensure minimum docs
        while (results.size() < minDocsToKeep && results.size() < originalDocs.size()) {
            for (Document doc : originalDocs) {
                if (!results.contains(doc)) {
                    Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                    meta.put("fallback", true);
                    results.add(new Document(doc.getText(), meta));
                    if (results.size() >= minDocsToKeep) break;
                }
            }
        }

        log.info("Unified filter+compress: {} docs → {} relevant+compressed",
                originalDocs.size(), results.size());
        return results;
    }

    private List<Document> filterWithLLM(String query, List<Document> documents) {
        StringBuilder docsBlock = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            String text = documents.get(i).getText();
            if (text == null) text = "";
            String snippet = text.length() > 400 ? text.substring(0, 400) + "..." : text;
            docsBlock.append("[").append(i).append("] ").append(snippet).append("\n\n");
        }

        String prompt = String.format("""
                你是检索相关性判断专家。判断每个文档是否与用户问题相关。

                【用户问题】%s

                【候选文档】
                %s

                请输出JSON（不要Markdown包裹）：
                {
                  "relevance": [0.85, 0.12, 0.67, ...]
                }

                relevance 数组中每个值对应文档[i]与问题的相关度，0.0=完全不相关，1.0=高度相关。
                只输出JSON，不要其他内容。""", query, docsBlock.toString());

        String raw = judgeClient.prompt().user(prompt).call().content();
        if (raw == null || raw.isBlank()) return documents;

        String jsonStr = raw.trim().replaceAll("```\\w*\\n?", "").replaceAll("```", "").trim();
        JSONObject json = JSON.parseObject(jsonStr);
        JSONArray relArray = json.getJSONArray("relevance");
        if (relArray == null) return documents;

        List<Document> filtered = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            double score = i < relArray.size() ? relArray.getDoubleValue(i) : 0.5;
            if (score >= relevanceThreshold) {
                filtered.add(documents.get(i));
            }
        }

        while (filtered.size() < minDocsToKeep && filtered.size() < documents.size()) {
            // Re-add the highest-scoring removed document
            int bestIdx = -1;
            double bestScore = -1;
            for (int i = 0; i < documents.size(); i++) {
                double score = i < relArray.size() ? relArray.getDoubleValue(i) : 0.5;
                if (score < relevanceThreshold && score > bestScore && !filtered.contains(documents.get(i))) {
                    bestScore = score;
                    bestIdx = i;
                }
            }
            if (bestIdx >= 0) {
                filtered.add(documents.get(bestIdx));
            } else {
                break;
            }
        }

        log.info("Post-retrieval filter: {} docs → {} relevant (threshold={})",
                documents.size(), filtered.size(), relevanceThreshold);
        return filtered;
    }
}
