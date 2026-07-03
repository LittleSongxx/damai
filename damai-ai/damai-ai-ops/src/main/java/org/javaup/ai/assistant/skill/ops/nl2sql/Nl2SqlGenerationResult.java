package org.javaup.ai.assistant.skill.ops.nl2sql;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Nl2SqlGenerationResult {

    private boolean needSql = true;

    private String sql = "";

    private List<String> tables = new ArrayList<>();

    private String explanation = "";

    private String chartType = "table";

    private Double confidence = 0.0D;

    private List<String> assumptions = new ArrayList<>();
}
