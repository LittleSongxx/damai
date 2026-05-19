package org.javaup.ai.rag.channel;

import org.javaup.ai.vo.RagSourceVo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Pluggable retrieval channel — each implementation performs a specific kind of search
 * (dense vector, sparse keyword, HyDE, etc.) and returns scored results.
 *
 * Inspired by ragent's SearchChannel pattern.
 */
public interface SearchChannel {

    String channelName();

    /** Whether this channel should be activated for the given search context. */
    boolean isEnabled(SearchContext context);

    /** Execute search asynchronously, returning a future with channel-scored results. */
    CompletableFuture<SearchChannelResult> search(SearchContext context);

    /** Sync wrapper for simpler call sites. */
    default SearchChannelResult searchSync(SearchContext context) {
        return search(context).join();
    }

    record SearchChannelResult(String channelName, List<RagSourceVo> sources, long latencyMs) {
        public static SearchChannelResult empty(String channelName) {
            return new SearchChannelResult(channelName, List.of(), 0);
        }
    }
}
