package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.service.RagFusionSupport;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeRetrievalEvaluator {

    public KnowledgeRetrievalAssessment assess(RagSearchResultVo result, List<RagSourceVo> supportSources, String correctiveAction, KnowledgeRetrievalPlan plan) {
        List<RagSourceVo> mergedSources = new ArrayList<>();
        if (result.getSources() != null) {
            mergedSources.addAll(result.getSources());
        }
        if (supportSources != null) {
            mergedSources.addAll(supportSources);
        }
        List<RagSourceVo> deduped = dedupe(mergedSources);
        double denseTop = firstScore(result.getDenseSources());
        double sparseTop = normalizeSparse(firstScore(result.getSparseSources()));
        double overlap = overlap(result.getDenseSources(), result.getSparseSources());
        double finalCount = Math.min(1D, deduped.size() / 4D);
        double diversity = Math.min(1D, deduped.stream().map(RagSourceVo::getSource).distinct().count() / 2D);
        double score = denseTop * 0.35 + sparseTop * 0.25 + overlap * 0.15 + finalCount * 0.15 + diversity * 0.10;
        String level = score >= 0.72 ? "HIGH" : score >= 0.45 ? "MEDIUM" : "LOW";
        return new KnowledgeRetrievalAssessment(score, level, correctiveAction,
                RagFusionSupport.evidenceBudget(deduped, plan.evidenceSourceLimit(), plan.evidenceSnippetLimit()));
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
        Set<String> denseIds = denseSources.stream().map(RagSourceVo::getChunkId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> sparseIds = sparseSources.stream().map(RagSourceVo::getChunkId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        denseIds.retainAll(sparseIds);
        return Math.min(1D, denseIds.size() / 2D);
    }
}
