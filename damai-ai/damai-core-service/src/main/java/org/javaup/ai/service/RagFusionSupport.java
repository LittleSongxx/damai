package org.javaup.ai.service;

import org.javaup.ai.vo.RagSourceVo;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * FAQ 混合检索的纯算法辅助类，便于单元测试。
 */
public final class RagFusionSupport {

    private RagFusionSupport() {
    }

    public static List<RagSourceVo> reciprocalRankFusion(List<RagSourceVo> denseSources,
                                                         List<RagSourceVo> sparseSources,
                                                         int topK) {
        return reciprocalRankFusion(denseSources, sparseSources, topK, 60);
    }

    public static List<RagSourceVo> reciprocalRankFusion(List<RagSourceVo> denseSources,
                                                         List<RagSourceVo> sparseSources,
                                                         int topK,
                                                         int rrfK) {
        return weightedReciprocalRankFusion(denseSources, sparseSources, topK, rrfK, null);
    }

    /**
     * Adaptive weighted RRF: applies channel weights based on query type.
     * SEMANTIC queries favor dense (0.7/0.3), KEYWORD queries favor sparse (0.3/0.7),
     * MIXED uses equal weights (0.5/0.5).
     */
    public static List<RagSourceVo> weightedReciprocalRankFusion(
            List<RagSourceVo> denseSources,
            List<RagSourceVo> sparseSources,
            int topK,
            int rrfK,
            org.javaup.ai.service.AdvancedQueryService.QueryType queryType) {
        double denseWeight = 0.5;
        double sparseWeight = 0.5;
        if (queryType != null) {
            switch (queryType) {
                case SEMANTIC -> { denseWeight = 0.65; sparseWeight = 0.35; }
                case KEYWORD  -> { denseWeight = 0.30; sparseWeight = 0.70; }
                default       -> { denseWeight = 0.45; sparseWeight = 0.55; }
            }
        }
        Map<String, Double> scores = new HashMap<>();
        Map<String, RagSourceVo> sourceMap = new HashMap<>();
        weightedMerge(scores, sourceMap, denseSources, rrfK, denseWeight);
        weightedMerge(scores, sourceMap, sparseSources, rrfK, sparseWeight);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> copyWithScore(sourceMap.get(entry.getKey()), entry.getValue()))
                .collect(Collectors.toList());
    }

    public static List<RagSourceVo> limit(List<RagSourceVo> sources, int topK) {
        return sources.stream()
                .limit(topK)
                .map(source -> copyWithScore(source, source.getScore()))
                .collect(Collectors.toList());
    }

    public static List<RagSourceVo> evidenceBudget(List<RagSourceVo> sources, int maxSources, int maxSnippetChars) {
        if (sources == null || sources.isEmpty() || maxSources <= 0) {
            return List.of();
        }
        Map<String, RagSourceVo> deduped = new LinkedHashMap<>();
        for (RagSourceVo source : sources) {
            if (source == null || source.getChunkId() == null || deduped.containsKey(source.getChunkId())) {
                continue;
            }
            deduped.put(source.getChunkId(), copyWithSnippet(source, maxSnippetChars));
            if (deduped.size() >= maxSources) {
                break;
            }
        }
        return List.copyOf(deduped.values());
    }

    private static void merge(Map<String, Double> scores,
                              Map<String, RagSourceVo> sourceMap,
                              List<RagSourceVo> sources,
                              int k) {
        weightedMerge(scores, sourceMap, sources, k, 1.0);
    }

    private static void weightedMerge(Map<String, Double> scores,
                                      Map<String, RagSourceVo> sourceMap,
                                      List<RagSourceVo> sources,
                                      int k,
                                      double weight) {
        for (int i = 0; i < sources.size(); i++) {
            RagSourceVo source = sources.get(i);
            scores.merge(source.getChunkId(), weight / (k + i + 1), Double::sum);
            sourceMap.putIfAbsent(source.getChunkId(), source);
        }
    }

    private static RagSourceVo copyWithScore(RagSourceVo source, Double score) {
        return RagSourceVo.builder()
                .chunkId(source.getChunkId())
                .title(source.getTitle())
                .source(source.getSource())
                .section(source.getSection())
                .snippet(source.getSnippet())
                .score(score)
                .parentBlockId(source.getParentBlockId())
                .channelName(source.getChannelName())
                .build();
    }

    private static RagSourceVo copyWithSnippet(RagSourceVo source, int maxSnippetChars) {
        String snippet = source.getSnippet();
        if (snippet != null && maxSnippetChars > 0 && snippet.length() > maxSnippetChars) {
            snippet = snippet.substring(0, maxSnippetChars);
        }
        return RagSourceVo.builder()
                .chunkId(source.getChunkId())
                .title(source.getTitle())
                .source(source.getSource())
                .section(source.getSection())
                .snippet(snippet)
                .score(source.getScore())
                .parentBlockId(source.getParentBlockId())
                .channelName(source.getChannelName())
                .build();
    }
}
