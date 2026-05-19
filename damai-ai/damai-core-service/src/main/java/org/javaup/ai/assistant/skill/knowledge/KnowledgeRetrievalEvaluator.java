package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.service.RagFusionSupport;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

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

        // Phase 1: Fast heuristic score
        double denseTop = firstScore(result.getDenseSources());
        double sparseTop = normalizeSparse(firstScore(result.getSparseSources()));
        double overlap = overlap(result.getDenseSources(), result.getSparseSources());
        double finalCount = Math.min(1D, deduped.size() / 4D);
        double diversity = Math.min(1D, deduped.stream().map(RagSourceVo::getSource).distinct().count() / 2D);
        double heuristicScore = denseTop * 0.35 + sparseTop * 0.25 + overlap * 0.15 + finalCount * 0.15 + diversity * 0.10;

        // Phase 2: Multi-dimensional semantic assessment (LLM)
        String relevanceLevel;
        String coverageLevel;
        boolean hasContradictions;
        String answerabilityLevel;
        String missingInfo;

        if (heuristicScore >= 0.72 && deduped.size() >= 4) {
            // Fast path: trust heuristic for high-confidence results
            relevanceLevel = "HIGH";
            coverageLevel = "HIGH";
            hasContradictions = false;
            answerabilityLevel = "ANSWERABLE";
            missingInfo = "";
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
                relevanceLevel, coverageLevel, hasContradictions, answerabilityLevel, missingInfo);
    }

    private String assessSemanticRelevance(String query, List<RagSourceVo> sources) {
        if (sources.isEmpty()) return "LOW";
        var sample = sources.size() <= 3 ? sources : sampleRandom(sources, 3);
        int relevantCount = 0;
        for (var source : sample) {
            try {
                String prompt = String.format(
                        "判断以下文本块是否与查询相关。只回答YES或NO。\n查询：%s\n文本：%s",
                        query, source.getSnippet());
                String answer = chatClient.prompt().user(prompt).call().content();
                if (answer != null && answer.trim().toUpperCase().startsWith("YES")) {
                    relevantCount++;
                }
            } catch (Exception e) {
                // Treat LLM call failures conservatively
            }
        }
        if (relevantCount >= 2) return "HIGH";
        if (relevantCount == 1) return "MEDIUM";
        return "LOW";
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

    private double firstScore(List<RagSourceVo> sources) {
        if (sources == null || sources.isEmpty() || sources.get(0).getScore() == null) {
            return 0D;
        }
        return Math.min(1D, Math.max(0D, sources.get(0).getScore()));
    }

    private double normalizeSparse(double score) {
        return Math.min(1D, score / 12D);
    }

    private double overlap(List<RagSourceVo> denseSources, List<RagSourceVo> sparseSources) {
        if (denseSources == null || sparseSources == null) {
            return 0D;
        }
        Set<String> denseIds = denseSources.stream().map(RagSourceVo::getChunkId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> sparseIds = sparseSources.stream().map(RagSourceVo::getChunkId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        denseIds.retainAll(sparseIds);
        return Math.min(1D, denseIds.size() / 2D);
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
