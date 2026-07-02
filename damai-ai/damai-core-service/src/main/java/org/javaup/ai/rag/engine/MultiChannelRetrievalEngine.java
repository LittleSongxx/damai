package org.javaup.ai.rag.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.rag.channel.DenseSearchChannel;
import org.javaup.ai.rag.channel.HydeSearchChannel;
import org.javaup.ai.rag.channel.SearchChannel;
import org.javaup.ai.rag.channel.SearchContext;
import org.javaup.ai.rag.channel.SparseSearchChannel;
import org.javaup.ai.rag.postprocessor.SearchResultPostProcessor;
import org.javaup.ai.service.RagFusionSupport;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Multi-channel retrieval engine: runs parallel search channels, merges results
 * with RRF fusion, then applies ordered post-processors.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiChannelRetrievalEngine {

    private final DenseSearchChannel denseChannel;
    private final SparseSearchChannel sparseChannel;
    private final HydeSearchChannel hydeChannel;
    private final List<SearchResultPostProcessor> postProcessors;

    @Value("${damai.ai.retrieval.candidate-multiplier:5}")
    private int candidateMultiplier;

    @Value("${damai.ai.retrieval.max-candidates:50}")
    private int maxCandidates;

    @Value("${damai.ai.retrieval.rrf-k:60}")
    private int rrfK;

    public RagSearchResultVo retrieve(SearchContext context) {
        context.setCandidateTopK(candidateTopK(context));
        List<CompletableFuture<SearchChannel.SearchChannelResult>> futures = new ArrayList<>();
        futures.add(denseChannel.search(context));
        futures.add(hydeChannel.search(context));
        futures.add(sparseChannel.search(context));

        List<SearchChannel.SearchChannelResult> results;
        try {
            results = futures.stream()
                    .map(f -> {
                        try { return f.get(30, TimeUnit.SECONDS); }
                        catch (Exception e) {
                            log.warn("Channel search timed out or failed: {}", e.getMessage());
                            return SearchChannel.SearchChannelResult.empty("failed");
                        }
                    })
                    .toList();
        } catch (Exception e) {
            log.error("Channel execution failed", e);
            return RagSearchResultVo.builder()
                    .originalQuery(context.getOriginalQuery())
                    .normalizedQuery(context.getOriginalQuery())
                    .rewrittenQuery(context.getRewrittenQuery())
                    .documents(List.of()).sources(List.of()).build();
        }

        List<RagSourceVo> allDenseSources = new ArrayList<>();
        List<RagSourceVo> allSparseSources = new ArrayList<>();
        for (var result : results) {
            var sources = markChannel(result.sources(), result.channelName());
            if (sources == null) continue;
            switch (result.channelName()) {
                case "dense" -> allDenseSources.addAll(sources);
                case "hyde" -> {
                    for (RagSourceVo s : sources) {
                        boolean exists = allDenseSources.stream().anyMatch(d -> d.getChunkId().equals(s.getChunkId()));
                        if (!exists) allDenseSources.add(s);
                    }
                }
                case "sparse" -> allSparseSources.addAll(sources);
            }
        }

        allDenseSources = applyNamedProcessor("evidence-gate", allDenseSources, context);
        allSparseSources = applyNamedProcessor("evidence-gate", allSparseSources, context);

        int candidatePool = Math.max(context.getTopK() * 3, context.getCandidateTopK());
        List<RagSourceVo> fusedSources;
        if (allDenseSources.isEmpty() && allSparseSources.isEmpty()) {
            fusedSources = List.of();
        } else if (allSparseSources.isEmpty()) {
            fusedSources = shrink(allDenseSources, candidatePool);
        } else if (allDenseSources.isEmpty()) {
            fusedSources = shrink(allSparseSources, candidatePool);
        } else {
            fusedSources = RagFusionSupport.weightedReciprocalRankFusion(
                    allDenseSources, allSparseSources, candidatePool, effectiveRrfK(), context.getQueryType());
        }

        List<SearchResultPostProcessor> ordered = postProcessors.stream()
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::order))
                .filter(pp -> !"evidence-gate".equals(pp.name()))
                .toList();
        List<RagSourceVo> processed = fusedSources;
        for (var pp : ordered) {
            processed = pp.process(processed, context);
        }

        List<RagSourceVo> finalSources = shrink(processed, context.getTopK());

        return RagSearchResultVo.builder()
                .originalQuery(context.getOriginalQuery())
                .normalizedQuery(context.getOriginalQuery())
                .rewrittenQuery(context.getRewrittenQuery())
                .denseSources(allDenseSources)
                .sparseSources(allSparseSources)
                .fusedSources(fusedSources)
                .sources(finalSources)
                .documents(List.of()) // resolve documents in facade/orchestrator
                .build();
    }

    private List<RagSourceVo> shrink(List<RagSourceVo> sources, int topK) {
        return RagFusionSupport.limit(sources, topK);
    }

    public RagSearchResultVo retrieveSimple(SearchContext context) {
        context.setCandidateTopK(candidateTopK(context));
        List<CompletableFuture<SearchChannel.SearchChannelResult>> futures = new ArrayList<>();
        futures.add(denseChannel.search(context));
        futures.add(sparseChannel.search(context));

        List<SearchChannel.SearchChannelResult> results;
        try {
            results = futures.stream()
                    .map(f -> {
                        try { return f.get(25, TimeUnit.SECONDS); }
                        catch (Exception e) {
                            log.warn("Simple channel search failed: {}", e.getMessage());
                            return SearchChannel.SearchChannelResult.empty("failed");
                        }
                    })
                    .toList();
        } catch (Exception e) {
            log.error("Simple channel execution failed", e);
            return RagSearchResultVo.builder()
                    .originalQuery(context.getOriginalQuery())
                    .normalizedQuery(context.getOriginalQuery())
                    .rewrittenQuery(context.getRewrittenQuery())
                    .documents(List.of()).sources(List.of()).build();
        }

        List<RagSourceVo> allDenseSources = new ArrayList<>();
        List<RagSourceVo> allSparseSources = new ArrayList<>();
        for (var result : results) {
            var sources = markChannel(result.sources(), result.channelName());
            if (sources == null) continue;
            switch (result.channelName()) {
                case "dense" -> allDenseSources.addAll(sources);
                case "sparse" -> allSparseSources.addAll(sources);
            }
        }

        allDenseSources = applyNamedProcessor("evidence-gate", allDenseSources, context);
        allSparseSources = applyNamedProcessor("evidence-gate", allSparseSources, context);

        int candidatePool = Math.max(context.getTopK() * 3, context.getCandidateTopK());
        List<RagSourceVo> fusedSources;
        if (allDenseSources.isEmpty() && allSparseSources.isEmpty()) {
            fusedSources = List.of();
        } else if (allSparseSources.isEmpty()) {
            fusedSources = shrink(allDenseSources, candidatePool);
        } else if (allDenseSources.isEmpty()) {
            fusedSources = shrink(allSparseSources, candidatePool);
        } else {
            fusedSources = RagFusionSupport.weightedReciprocalRankFusion(
                    allDenseSources, allSparseSources, candidatePool, effectiveRrfK(), context.getQueryType());
        }

        List<RagSourceVo> finalSources = shrink(fusedSources, context.getTopK());

        return RagSearchResultVo.builder()
                .originalQuery(context.getOriginalQuery())
                .normalizedQuery(context.getOriginalQuery())
                .rewrittenQuery(context.getRewrittenQuery())
                .denseSources(allDenseSources)
                .sparseSources(allSparseSources)
                .fusedSources(fusedSources)
                .sources(finalSources)
                .documents(List.of()) // resolve documents in caller
                .build();
    }

    private int candidateTopK(SearchContext context) {
        int topK = Math.max(1, context.getTopK());
        int multiplier = candidateMultiplier > 0 ? candidateMultiplier : 5;
        int computed = Math.max(topK, topK * multiplier);
        int cap = maxCandidates > 0 ? maxCandidates : computed;
        return Math.min(cap, computed);
    }

    private int effectiveRrfK() {
        return rrfK > 0 ? rrfK : 60;
    }

    private List<RagSourceVo> markChannel(List<RagSourceVo> sources, String channelName) {
        if (sources == null) return null;
        for (RagSourceVo source : sources) {
            if (source.getChannelName() == null || source.getChannelName().isBlank()) {
                source.setChannelName(channelName);
            }
        }
        return sources;
    }

    private List<RagSourceVo> applyNamedProcessor(String name, List<RagSourceVo> sources, SearchContext context) {
        if (sources == null || sources.isEmpty()) return sources == null ? List.of() : sources;
        for (SearchResultPostProcessor processor : postProcessors) {
            if (name.equals(processor.name())) {
                return processor.process(sources, context);
            }
        }
        return sources;
    }
}
