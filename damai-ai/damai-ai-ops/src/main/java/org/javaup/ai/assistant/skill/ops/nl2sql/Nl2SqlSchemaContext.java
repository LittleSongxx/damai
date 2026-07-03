package org.javaup.ai.assistant.skill.ops.nl2sql;

import java.util.List;

public record Nl2SqlSchemaContext(
        List<Nl2SqlProperties.Table> tables,
        List<Nl2SqlProperties.Term> terms,
        List<Nl2SqlProperties.Example> examples,
        String formattedSchema
) {
}
