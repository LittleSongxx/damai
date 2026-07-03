package org.javaup.ai.rag.channel;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.resilience.CircuitBreakerService;
import org.javaup.ai.service.RagSearchBackendService;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Sparse keyword (BM25) search via Elasticsearch.
 */
@Component
@RequiredArgsConstructor
public class SparseSearchChannel {

    private final RagSearchBackendService searchBackendService;
    private final CircuitBreakerService circuitBreakerService;

    public CompletableFuture<SearchChannel.SearchChannelResult> search(SearchContext context) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            int limit = context.getCandidateTopK() > 0 ? context.getCandidateTopK() : context.getTopK();
            List<RagSourceVo> sources = circuitBreakerService.executeEs(
                    () -> searchBackendService.sparseSearch(context.getRewrittenQuery(), limit, context.effectiveFilter()),
                    List.of());
            long latency = System.currentTimeMillis() - start;
            return new SearchChannel.SearchChannelResult("sparse", sources, latency);
        });
    }
}
