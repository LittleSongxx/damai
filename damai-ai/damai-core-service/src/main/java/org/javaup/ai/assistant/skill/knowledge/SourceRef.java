package org.javaup.ai.assistant.skill.knowledge;

public record SourceRef(
        String refId,
        String title,
        String section,
        String chunkId,
        String source
) {
}
