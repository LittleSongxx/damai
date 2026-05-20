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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Multi-channel retrieval engine: runs parallel search channels, merges results
 * with RRF fusion, then applies ordered post-processors. Provides the same
 * API surface as HybridSearchService but with pluggable extensibility.
 *
 * Inspired by ragent's MultiChannelRetrievalEngine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiChannelRetrievalEngine {

    private final DenseSearchChannel denseChannel;
    private final SparseSearchChannel sparseChannel;
    private final HydeSearchChannel hydeChannel;
    private final List<SearchResultPostProcessor> postProcessors;

    public RagSearchResultVo retrieve(SearchContext context) {
        // Phase 1: Parallel channel execution
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

        // Phase 2: Merge all channel results with RRF
        List<RagSourceVo> allDenseSources = new ArrayList<>();
        List<RagSourceVo> allSparseSources = new ArrayList<>();
        for (var result : results) {
            var sources = result.sources();
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

        List<RagSourceVo> fusedSources;
        if (allDenseSources.isEmpty() && allSparseSources.isEmpty()) {
            fusedSources = List.of();
        } else if (allSparseSources.isEmpty()) {
            fusedSources = shrink(allDenseSources, context.getTopK() * 2);
        } else if (allDenseSources.isEmpty()) {
            fusedSources = shrink(allSparseSources, context.getTopK() * 2);
        } else {
            fusedSources = RagFusionSupport.reciprocalRankFusion(allDenseSources, allSparseSources, context.getTopK() * 2);
        }

        // Phase 3: Ordered post-processing chain
        List<SearchResultPostProcessor> ordered = postProcessors.stream()
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::order))
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
                .documents(List.of())
                .build();
    }

    private List<RagSourceVo> shrink(List<RagSourceVo> sources, int topK) {
        return RagFusionSupport.limit(sources, topK);
    }

    public RagSearchResultVo retrieveSimple(SearchContext context) {
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
            var sources = result.sources();
            if (sources == null) continue;
            switch (result.channelName()) {
                case "dense" -> allDenseSources.addAll(sources);
                case "sparse" -> allSparseSources.addAll(sources);
            }
        }

        List<RagSourceVo> fusedSources;
        if (allDenseSources.isEmpty() && allSparseSources.isEmpty()) {
            fusedSources = List.of();
        } else if (allSparseSources.isEmpty()) {
            fusedSources = shrink(allDenseSources, context.getTopK() * 2);
        } else if (allDenseSources.isEmpty()) {
            fusedSources = shrink(allSparseSources, context.getTopK() * 2);
        } else {
            fusedSources = RagFusionSupport.reciprocalRankFusion(allDenseSources, allSparseSources, context.getTopK() * 2);
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
                .documents(List.of())
                .build();
    }
}
