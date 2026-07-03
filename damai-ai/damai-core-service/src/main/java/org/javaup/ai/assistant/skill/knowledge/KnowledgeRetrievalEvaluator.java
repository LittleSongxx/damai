package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.service.RagFusionSupport;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class KnowledgeRetrievalEvaluator {

    private final ChatClient chatClient;

    public KnowledgeRetrievalEvaluator(
            @Qualifier("unifiedKnowledgeChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public KnowledgeRetrievalAssessment assess(RagSearchResultVo result,
                                                List<RagSourceVo> supportSources,
                                                String correctiveAction,
                                                KnowledgeRetrievalPlan plan) {
        List<RagSourceVo> mergedSources = new ArrayList<>();
        if (result.getSources() != null) mergedSources.addAll(result.getSources());
        if (supportSources != null) mergedSources.addAll(supportSources);
        List<RagSourceVo> deduped = dedupe(mergedSources);

        // Phase 1: Distribution-aware heuristic score
        // Normalize scores relative to the result set rather than using magic constants
        double denseTop = normalizeDenseScore(result.getDenseSources());
        double sparseTop = normalizeSparseScore(result.getSparseSources());
        double overlap = jaccardOverlap(result.getDenseSources(), result.getSparseSources());
        double avgScore = averageScoreOfTopK(deduped, 3);
        double finalCount = Math.min(1D, deduped.size() / 3D);
        double diversity = Math.min(1D, sourceDiversity(deduped) / 1.5D);
        // Overlap is the strongest consensus signal; avgScore captures overall quality
        double heuristicScore = denseTop * 0.20 + sparseTop * 0.15 + overlap * 0.25
                + avgScore * 0.15 + finalCount * 0.15 + diversity * 0.10;

        // Phase 2: Multi-dimensional semantic assessment (LLM)
        String relevanceLevel;
        String coverageLevel;
        boolean hasContradictions;
        String answerabilityLevel;
        String missingInfo;
        List<String> verifiedClaims = List.of();

        if (heuristicScore >= 0.78 && deduped.size() >= 3 && (overlap >= 0.34D || (supportSources != null && !supportSources.isEmpty()))) {
            // Fast path: strong heuristic = skip expensive LLM assessment
            // Lower threshold justified by better normalization and overlap weighting
            relevanceLevel = "HIGH";
            coverageLevel = deduped.size() >= 3 ? "HIGH" : "MEDIUM";
            hasContradictions = false;
            answerabilityLevel = "ANSWERABLE";
            missingInfo = "";
            verifiedClaims = extractClaimAnchors(plan.normalizedQuery(), deduped);
        } else if (deduped.isEmpty()) {
            relevanceLevel = "LOW";
            coverageLevel = "LOW";
            hasContradictions = false;
            answerabilityLevel = "NOT_ANSWERABLE";
            missingInfo = "no documents found";
        } else {
            // LLM-based deep assessment
            List<Document> docs = result.getDocuments() != null ? result.getDocuments() : List.of();
            List<Document> topDocs = deduped.stream()
                    .limit(3)
                    .map(s -> docs.stream()
                            .filter(d -> Objects.equals(s.getChunkId(),
                                    d.getMetadata().get("chunkId")))
                            .findFirst().orElse(null))
                    .filter(Objects::nonNull)
                    .toList();

            relevanceLevel = assessSemanticRelevance(plan.normalizedQuery(), deduped);
            coverageLevel = topDocs.isEmpty() ? "LOW" : assessCoverage(plan.normalizedQuery(), topDocs);
            hasContradictions = detectContradictions(topDocs);
            missingInfo = getMissingInfo(plan.normalizedQuery(), topDocs);
            answerabilityLevel = computeAnswerability(relevanceLevel, coverageLevel, hasContradictions);
            if ("ANSWERABLE".equals(answerabilityLevel)) {
                verifiedClaims = extractClaimAnchors(plan.normalizedQuery(), deduped);
            }
        }

        // Phase 3: CRAG three-way classification
        String level;
        if ("HIGH".equals(relevanceLevel) && "HIGH".equals(coverageLevel) && !hasContradictions) {
            level = "CORRECT";
        } else if ("LOW".equals(relevanceLevel) || hasContradictions) {
            level = "INCORRECT";
        } else {
            level = "AMBIGUOUS";
        }

        return new KnowledgeRetrievalAssessment(heuristicScore, level, correctiveAction,
                RagFusionSupport.evidenceBudget(deduped, plan.evidenceSourceLimit(), plan.evidenceSnippetLimit()),
                relevanceLevel, coverageLevel, hasContradictions, answerabilityLevel, missingInfo,
                verifiedClaims);
    }

    /**
     * Extract claim anchors: key factual statements that the evidence supports.
     * These serve as confidence markers for answer generation.
     */
    private List<String> extractClaimAnchors(String query, List<RagSourceVo> sources) {
        if (sources == null || sources.isEmpty()) return List.of();
        try {
            String evidenceBlock = sources.stream()
                    .limit(4)
                    .map(s -> "- " + limit(s.getSnippet(), 200))
                    .collect(Collectors.joining("\n"));
            String prompt = String.format("""
                    根据以下检索到的证据，提取2-5个可以确认的关键事实声明。
                    每个声明一行，以"✓ "开头。只提取证据明确支持的声明，不要推断。
                    如果证据不足，输出空。

                    查询：%s

                    证据：%s

                    关键事实声明：
                    """, query, evidenceBlock);
            String raw = chatClient.prompt().user(prompt).call().content();
            if (raw == null || raw.isBlank()) return List.of();
            return raw.lines()
                    .map(String::trim)
                    .filter(line -> line.startsWith("✓"))
                    .map(line -> line.substring(1).trim())
                    .filter(line -> !line.isEmpty())
                    .limit(5)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private String assessSemanticRelevance(String query, List<RagSourceVo> sources) {
        if (sources.isEmpty()) return "LOW";
        var sample = sources.size() <= 5 ? sources : sampleRandom(sources, 5);
        int relevantCount = batchRelevanceCheck(query, sample);
        if (relevantCount < 0) relevantCount = perDocumentRelevanceFallback(query, sample);
        int threshold = Math.max(1, sample.size() / 2);
        if (relevantCount >= threshold + 1) return "HIGH";
        if (relevantCount >= threshold) return "MEDIUM";
        return "LOW";
    }

    /**
     * Batch-grade up to 5 documents in a single LLM call.
     * Returns the count of relevant documents, or -1 on failure (triggers fallback).
     */
    private int batchRelevanceCheck(String query, List<RagSourceVo> sample) {
        try {
            StringBuilder docsBlock = new StringBuilder();
            for (int i = 0; i < sample.size(); i++) {
                docsBlock.append(String.format("[文档%d] %s\n\n", i, limit(sample.get(i).getSnippet(), 400)));
            }
            String prompt = String.format("""
                    判断以下%d个文档块是否与查询相关。对每个文档输出一个JSON对象，包含id和relevant字段。
                    只输出一个JSON数组，不要任何其他内容。

                    查询：%s

                    %s
                    输出（JSON数组）：
                    """, sample.size(), query, docsBlock.toString());

            String raw = chatClient.prompt().user(prompt).call().content();
            if (raw == null || raw.isBlank()) return -1;

            String jsonStr = raw.trim();
            int arrayStart = jsonStr.indexOf('[');
            if (arrayStart >= 0) jsonStr = jsonStr.substring(arrayStart);
            int arrayEnd = jsonStr.lastIndexOf(']');
            if (arrayEnd >= 0) jsonStr = jsonStr.substring(0, arrayEnd + 1);

            JSONArray arr = JSON.parseArray(jsonStr);
            if (arr == null || arr.isEmpty()) return -1;

            int count = 0;
            for (int i = 0; i < arr.size(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String rel = obj.getString("relevant");
                if ("YES".equalsIgnoreCase(rel) || "true".equalsIgnoreCase(rel) || "是".equals(rel)) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Fallback: individual YES/NO calls when batch grading fails. */
    private int perDocumentRelevanceFallback(String query, List<RagSourceVo> sample) {
        int count = 0;
        for (var source : sample) {
            try {
                String prompt = String.format(
                        "判断以下文本块是否与查询相关。只回答YES或NO。\n查询：%s\n文本：%s",
                        query, limit(source.getSnippet(), 400));
                String answer = chatClient.prompt().user(prompt).call().content();
                if (answer != null && answer.trim().toUpperCase().startsWith("YES")) count++;
            } catch (Exception e) {
                // Treat LLM call failures conservatively
            }
        }
        return count;
    }

    private String assessCoverage(String query, List<Document> documents) {
        if (documents.isEmpty()) return "LOW";
        try {
            String context = documents.stream()
                    .map(d -> limit(d.getText(), 300))
                    .collect(Collectors.joining("\n---\n"));
            String prompt = String.format(
                    "根据以下上下文，判断是否足以回答用户的问题。信息充足回复SUFFICIENT，不足回复INSUFFICIENT。\n" +
                    "用户问题：%s\n上下文：%s", query, context);
            String answer = chatClient.prompt().user(prompt).call().content();
            return answer != null && answer.contains("SUFFICIENT") ? "HIGH" : "LOW";
        } catch (Exception e) {
            return "LOW";
        }
    }

    private boolean detectContradictions(List<Document> documents) {
        if (documents.size() < 2) return false;
        int checks = 0;
        for (int i = 0; i < documents.size() - 1 && checks < 2; i++) {
            for (int j = i + 1; j < documents.size() && checks < 2; j++) {
                try {
                    String prompt = String.format(
                            "判断以下两段文本是否包含相互矛盾的事实。只回答YES或NO。\n" +
                            "文本A：%s\n文本B：%s",
                            limit(documents.get(i).getText(), 200),
                            limit(documents.get(j).getText(), 200));
                    String answer = chatClient.prompt().user(prompt).call().content();
                    if (answer != null && answer.trim().toUpperCase().startsWith("YES")) return true;
                    checks++;
                } catch (Exception e) {
                    // Treat errors conservatively
                }
            }
        }
        return false;
    }

    private String getMissingInfo(String query, List<Document> documents) {
        if (documents.isEmpty()) return "no documents found";
        try {
            String context = documents.stream()
                    .map(d -> limit(d.getText(), 200))
                    .collect(Collectors.joining("\n---\n"));
            String prompt = String.format(
                    "用户问题：%s\n已检索到的信息：%s\n" +
                    "如果要完整回答这个问题，还缺少什么关键信息？用一句话描述，不超过50字。",
                    query, context);
            String answer = chatClient.prompt().user(prompt).call().content();
            return answer != null ? answer.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String computeAnswerability(String relevance, String coverage, boolean contradiction) {
        if (contradiction) return "NOT_ANSWERABLE";
        if ("LOW".equals(relevance) || "LOW".equals(coverage)) return "NOT_ANSWERABLE";
        if ("MEDIUM".equals(relevance) || "MEDIUM".equals(coverage)) return "PARTIALLY_ANSWERABLE";
        return "ANSWERABLE";
    }

    private List<RagSourceVo> dedupe(List<RagSourceVo> sources) {
        Map<String, RagSourceVo> deduped = new LinkedHashMap<>();
        for (RagSourceVo source : sources) {
            deduped.putIfAbsent(source.getChunkId(), source);
        }
        return new ArrayList<>(deduped.values());
    }

    /**
     * Normalize dense (Qdrant cosine) score: map [minVectorSimilarity, 1.0] → [0, 1].
     * Scores below the floor map to 0; scores near ceil map to ~1.0.
     */
    private double normalizeDenseScore(List<RagSourceVo> sources) {
        if (sources == null || sources.isEmpty() || sources.get(0).getScore() == null) return 0D;
        double raw = sources.get(0).getScore();
        double floor = 0.40;
        double ceil = 0.90;
        if (raw <= floor) return 0D;
        if (raw >= ceil) return 1D;
        return (raw - floor) / (ceil - floor);
    }

    /**
     * Normalize sparse (BM25) score using relative scaling within the result set.
     * Divides by the top score so the best result always maps to ~1.0.
     */
    private double normalizeSparseScore(List<RagSourceVo> sources) {
        if (sources == null || sources.isEmpty() || sources.get(0).getScore() == null) return 0D;
        double top = sources.get(0).getScore();
        if (top <= 0) return 0D;
        double saturation = 20D;
        return Math.min(1D, top / saturation);
    }

    /**
     * Jaccard-like overlap: intersection/union of top-N chunk IDs from dense and sparse.
     * Captures consensus between the two retrieval channels.
     */
    private double jaccardOverlap(List<RagSourceVo> denseSources, List<RagSourceVo> sparseSources) {
        if (denseSources == null || sparseSources == null) return 0D;
        int n = Math.min(5, Math.min(denseSources.size(), sparseSources.size()));
        if (n == 0) return 0D;
        Set<String> denseTop = denseSources.stream().limit(n).map(RagSourceVo::getChunkId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> sparseTop = sparseSources.stream().limit(n).map(RagSourceVo::getChunkId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> intersection = new LinkedHashSet<>(denseTop);
        intersection.retainAll(sparseTop);
        Set<String> union = new LinkedHashSet<>(denseTop);
        union.addAll(sparseTop);
        return union.isEmpty() ? 0D : (double) intersection.size() / union.size();
    }

    /**
     * Average score of top-K deduped sources, normalized to [0, 1].
     */
    private double averageScoreOfTopK(List<RagSourceVo> sources, int k) {
        if (sources == null || sources.isEmpty()) return 0D;
        return sources.stream()
                .limit(k)
                .mapToDouble(s -> {
                    Double score = s.getScore();
                    return score != null ? Math.min(1D, Math.max(0D, score)) : 0D;
                })
                .average()
                .orElse(0D);
    }

    /**
     * Count of distinct source identifiers for diversity scoring.
     */
    private double sourceDiversity(List<RagSourceVo> sources) {
        if (sources == null || sources.isEmpty()) return 0D;
        return sources.stream()
                .map(RagSourceVo::getSource)
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    private List<RagSourceVo> sampleRandom(List<RagSourceVo> sources, int count) {
        List<RagSourceVo> shuffled = new ArrayList<>(sources);
        java.util.Collections.shuffle(shuffled);
        return shuffled.subList(0, Math.min(count, shuffled.size()));
    }

    private String limit(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }
}
