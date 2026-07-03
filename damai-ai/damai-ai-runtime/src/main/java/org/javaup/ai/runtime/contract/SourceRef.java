package org.javaup.ai.runtime.contract;

public record SourceRef(
        String refId,
        String title,
        String section,
        String chunkId,
        String source
) {
}
