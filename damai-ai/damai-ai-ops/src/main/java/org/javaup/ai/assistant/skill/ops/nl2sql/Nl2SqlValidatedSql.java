package org.javaup.ai.assistant.skill.ops.nl2sql;

import java.util.List;

public record Nl2SqlValidatedSql(String sql, List<String> tables) {
}
