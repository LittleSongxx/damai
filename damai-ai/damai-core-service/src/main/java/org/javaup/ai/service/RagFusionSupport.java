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
        Map<String, Double> scores = new HashMap<>();
        Map<String, RagSourceVo> sourceMap = new HashMap<>();
        int k = 60;
        merge(scores, sourceMap, denseSources, k);
        merge(scores, sourceMap, sparseSources, k);
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
        for (int i = 0; i < sources.size(); i++) {
            RagSourceVo source = sources.get(i);
            scores.merge(source.getChunkId(), 1.0 / (k + i + 1), Double::sum);
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
                .build();
    }
}
