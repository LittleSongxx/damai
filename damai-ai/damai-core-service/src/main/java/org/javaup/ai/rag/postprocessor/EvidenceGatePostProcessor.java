package org.javaup.ai.rag.postprocessor;

import org.javaup.ai.rag.channel.SearchContext;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Filters low-quality retrieval results before RRF fusion.
 * - Dense results below minVectorSimilarity are dropped.
 * - Sparse results below a relative percentage of the top score are dropped.
 */
@Component
public class EvidenceGatePostProcessor implements SearchResultPostProcessor {

    @Value("${damai.ai.retrieval.min-vector-similarity:0.45}")
    private double minVectorSimilarity;

    @Value("${damai.ai.retrieval.keyword-relative-score-floor:0.35}")
    private double keywordRelativeScoreFloor;

    @Override
    public String name() { return "evidence-gate"; }

    @Override
    public int order() { return 5; }

    @Override
    public List<RagSourceVo> process(List<RagSourceVo> sources, SearchContext context) {
        if (sources == null || sources.isEmpty()) return List.of();
        // Gate dense (vector) results by absolute similarity threshold
        if (minVectorSimilarity > 0) {
            sources = sources.stream()
                    .filter(s -> s.getScore() == null || s.getScore() >= minVectorSimilarity)
                    .toList();
        }
        // Gate sparse (keyword) results by relative score floor (only if scores are BM25-scale, >1)
        if (keywordRelativeScoreFloor > 0 && !sources.isEmpty()) {
            double topScore = sources.stream()
                    .filter(s -> s.getScore() != null)
                    .mapToDouble(RagSourceVo::getScore)
                    .max().orElse(0D);
            if (topScore > 1.0) { // BM25 scores are typically >1, cosine scores 0-1
                double floor = topScore * keywordRelativeScoreFloor;
                sources = sources.stream()
                        .filter(s -> s.getScore() == null || s.getScore() >= floor)
                        .toList();
            }
        }
        return sources;
    }
}
