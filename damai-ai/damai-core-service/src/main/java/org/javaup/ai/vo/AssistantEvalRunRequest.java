package org.javaup.ai.vo;

import lombok.Data;

import java.util.List;

@Data
public class AssistantEvalRunRequest {

    private String datasetId;

    private String datasetVersion;

    private String category;

    private String difficulty;

    private List<String> caseIds;

    private Integer limit;

    private String baselineRunId;

    private String promptVersion;

    private String modelVersion;
}
