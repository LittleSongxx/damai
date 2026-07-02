package org.javaup.ai.rag.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.service.RagSearchBackendService;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class HydeSearchChannel {

    private final RagSearchBackendService searchBackendService;

    public CompletableFuture<SearchChannel.SearchChannelResult> search(SearchContext context) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            List<RagSourceVo> sources;
            try {
                int limit = context.getCandidateTopK() > 0 ? context.getCandidateTopK() : context.getTopK();
                sources = searchBackendService.hydeSearch(context.getOriginalQuery(), limit);
                for (RagSourceVo source : sources) {
                    source.setChannelName("hyde");
                }
            } catch (Exception e) {
                log.warn("HyDE search failed, returning empty: {}", e.getMessage());
                sources = List.of();
            }
            long latency = System.currentTimeMillis() - start;
            return new SearchChannel.SearchChannelResult("hyde", sources, latency);
        });
    }
}
