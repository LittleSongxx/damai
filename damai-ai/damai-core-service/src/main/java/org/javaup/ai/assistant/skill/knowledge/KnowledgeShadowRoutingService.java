package org.javaup.ai.assistant.skill.knowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.ai.rag.MarkdownLoader;
import org.javaup.ai.config.KnowledgeRoutingProperties;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeShadowRoutingService {

    private final MarkdownLoader markdownLoader;
    private final KnowledgeRoutingProperties properties;
    private final AtomicReference<List<Document>> documentCache = new AtomicReference<>();

    public KnowledgeShadowRouteResult shadowRoute(String query) {
        if (!properties.isShadowEnabled() || !StringUtils.hasText(query)) {
            return KnowledgeShadowRouteResult.empty(query);
        }
        List<Document> documents = loadDocuments();
        Map<String, CandidateAccumulator> scopes = new LinkedHashMap<>();
        Map<String, CandidateAccumulator> topics = new LinkedHashMap<>();
        Map<String, CandidateAccumulator> sourceFiles = new LinkedHashMap<>();
        for (Document document : documents) {
            Map<String, Object> metadata = document.getMetadata();
            double score = score(query, metadata);
            if (score < properties.getMinScore()) {
                continue;
            }
            List<String> reasons = reasons(query, metadata);
            accumulate(scopes, stringValue(metadata.get("label")), score, reasons);
            accumulate(topics, stringValue(metadata.get("docTitle")), score, reasons);
            accumulate(sourceFiles, stringValue(metadata.get("sourceFile")), score, reasons);
        }
        KnowledgeShadowRouteResult result = KnowledgeShadowRouteResult.builder()
                .query(query)
                .mode("shadow")
                .scopeCandidates(top(scopes, properties.getMaxScopes()))
                .topicCandidates(top(topics, properties.getMaxTopics()))
                .documentCandidates(top(sourceFiles, properties.getMaxDocuments()))
                .build();
        log.info("知识 shadow routing 完成 query='{}', scopes={}, topics={}, documents={}",
                query, result.scopeCandidates().size(), result.topicCandidates().size(), result.documentCandidates().size());
        return result;
    }

    private List<Document> loadDocuments() {
        List<Document> cached = documentCache.get();
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        List<Document> documents = markdownLoader.loadMarkdowns();
        documentCache.set(documents);
        return documents;
    }

    private void accumulate(Map<String, CandidateAccumulator> target, String name, double score, List<String> reasons) {
        if (!StringUtils.hasText(name)) {
            return;
        }
        CandidateAccumulator accumulator = target.computeIfAbsent(name, ignored -> new CandidateAccumulator());
        accumulator.score += score;
        accumulator.reasons.addAll(reasons);
    }

    private List<KnowledgeRouteCandidate> top(Map<String, CandidateAccumulator> accumulators, int limit) {
        return accumulators.entrySet().stream()
                .sorted(Map.Entry.<String, CandidateAccumulator>comparingByValue(Comparator.comparingDouble(value -> value.score)).reversed())
                .limit(limit)
                .map(entry -> KnowledgeRouteCandidate.builder()
                        .name(entry.getKey())
                        .score(round(entry.getValue().score))
                        .reasons(entry.getValue().reasons.stream().filter(StringUtils::hasText).distinct().limit(4).toList())
                        .build())
                .collect(Collectors.toList());
    }

    private double score(String query, Map<String, Object> metadata) {
        double score = 0D;
        score += scoreContains(query, stringValue(metadata.get("label")), 2.0D);
        score += scoreContains(query, stringValue(metadata.get("docTitle")), 1.5D);
        score += scoreContains(query, stringValue(metadata.get("question")), 1.2D);
        score += scoreKeywordOverlap(query, stringValue(metadata.get("keywords")));
        return score;
    }

    private List<String> reasons(String query, Map<String, Object> metadata) {
        Set<String> reasons = new LinkedHashSet<>();
        addReason(query, stringValue(metadata.get("label")), "scope", reasons);
        addReason(query, stringValue(metadata.get("docTitle")), "topic", reasons);
        addReason(query, stringValue(metadata.get("question")), "question", reasons);
        String keywords = stringValue(metadata.get("keywords"));
        if (StringUtils.hasText(keywords)) {
            for (String keyword : keywords.split(",")) {
                String trimmed = keyword == null ? "" : keyword.trim();
                if (trimmed.length() >= 2 && query.contains(trimmed)) {
                    reasons.add("keyword:" + trimmed);
                }
            }
        }
        return new ArrayList<>(reasons);
    }

    private void addReason(String query, String candidate, String prefix, Set<String> reasons) {
        if (StringUtils.hasText(candidate) && candidate.length() >= 2 && query.contains(candidate)) {
            reasons.add(prefix + ":" + candidate);
        }
    }

    private double scoreContains(String query, String candidate, double weight) {
        if (!StringUtils.hasText(candidate)) {
            return 0D;
        }
        return query.contains(candidate) ? weight : 0D;
    }

    private double scoreKeywordOverlap(String query, String keywords) {
        if (!StringUtils.hasText(keywords)) {
            return 0D;
        }
        double score = 0D;
        for (String keyword : keywords.split(",")) {
            String trimmed = keyword == null ? "" : keyword.trim();
            if (trimmed.length() >= 2 && query.contains(trimmed)) {
                score += 0.8D;
            }
        }
        return score;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private double round(double score) {
        return Math.round(score * 100D) / 100D;
    }

    private static final class CandidateAccumulator {
        private double score;
        private final Set<String> reasons = new LinkedHashSet<>();
    }
}
