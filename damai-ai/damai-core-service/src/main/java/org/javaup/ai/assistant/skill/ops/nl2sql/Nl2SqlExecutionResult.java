package org.javaup.ai.assistant.skill.ops.nl2sql;

import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
public record Nl2SqlExecutionResult(
        String sql,
        List<String> columns,
        List<Map<String, Object>> rows,
        int rowCount,
        boolean truncated,
        boolean skipped,
        String skipReason,
        long durationMs
) {
}
