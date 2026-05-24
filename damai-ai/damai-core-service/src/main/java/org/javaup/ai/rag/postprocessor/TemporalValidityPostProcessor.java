package org.javaup.ai.rag.postprocessor;

import org.javaup.ai.rag.channel.SearchContext;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Filters expired chunks and applies time-decay weighting to retrieval results.
 * <p>
 * Chunks whose {@code validUntil} precedes the reference time ({@code context.now})
 * are dropped entirely. Remaining chunks receive a multiplicative decay factor
 * based on their age relative to a configurable half-life.
 */
@Component
public class TemporalValidityPostProcessor implements SearchResultPostProcessor {

    @Value("${damai.ai.retrieval.time-decay-half-life-days:90}")
    private int halfLifeDays;

    @Override
    public String name() { return "temporal-validity"; }

    @Override
    public int order() { return 7; }

    @Override
    public List<RagSourceVo> process(List<RagSourceVo> sources, SearchContext context) {
        if (sources == null || sources.isEmpty()) return List.of();
        long now = context.getNow() != null ? context.getNow() : System.currentTimeMillis();
        long halfLifeMs = (long) halfLifeDays * 24 * 3600 * 1000L;

        return sources.stream()
                .filter(s -> !isExpired(s, now))
                .map(s -> applyTimeDecay(s, now, halfLifeMs))
                .toList();
    }

    private boolean isExpired(RagSourceVo source, long now) {
        if (source.getValidUntil() == null) return false;
        return source.getValidUntil() < now;
    }

    /**
     * Exponential decay: factor = 2^(-age / half_life).
     * Chunks older than half-life receive score penalty; newer chunks are unaffected.
     * When no version/validUntil metadata is available, the source passes through unchanged.
     */
    private RagSourceVo applyTimeDecay(RagSourceVo source, long now, long halfLifeMs) {
        if (source.getValidUntil() == null || source.getScore() == null) return source;

        // Approximate age: assume document was created (halfLifeDays) days before expiry.
        // If validUntil is 90 days from now, chunk is "fresh" → factor close to 1.0.
        // If validUntil is in the past but not yet expired, decay starts.
        long remainingMs = source.getValidUntil() - now;
        if (remainingMs >= halfLifeMs) return source; // still fresh

        // Clamp age so we don't divide by zero or get extreme values
        long ageMs = Math.max(0, halfLifeMs - remainingMs);
        double decay = Math.pow(2.0, -(double) ageMs / halfLifeMs); // range (0, 1]
        RagSourceVo decayed = RagSourceVo.builder()
                .chunkId(source.getChunkId())
                .title(source.getTitle())
                .source(source.getSource())
                .section(source.getSection())
                .snippet(source.getSnippet())
                .score(source.getScore() * decay)
                .parentBlockId(source.getParentBlockId())
                .channelName(source.getChannelName())
                .validUntil(source.getValidUntil())
                .version(source.getVersion())
                .build();
        return decayed;
    }
}