package org.javaup.ai.rag.channel;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.resilience.CircuitBreakerService;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
public class DenseSearchChannel {

    private final org.javaup.ai.service.HybridSearchService hybridSearchService;
    private final CircuitBreakerService circuitBreakerService;

    public CompletableFuture<SearchChannel.SearchChannelResult> search(SearchContext context) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            List<String> queries = context.getQueryVariants() != null && !context.getQueryVariants().isEmpty()
                    ? context.getQueryVariants() : List.of(context.getRewrittenQuery());
            int limit = context.getCandidateTopK() > 0 ? context.getCandidateTopK() : context.getTopK();
            List<RagSourceVo> sources = circuitBreakerService.executeQdrant(
                    () -> hybridSearchService.multiQueryDenseSearch(queries, limit),
                    List.of());
            long latency = System.currentTimeMillis() - start;
            return new SearchChannel.SearchChannelResult("dense", sources, latency);
        });
    }
}
