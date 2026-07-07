package org.javaup.ai.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class EvaluationRunRequest {

    private String domain;

    private String datasetId;

    private String datasetVersion;

    private String baselineConfigId;

    private String candidateConfigId;

    private String judgeMode = "LLM_DEFAULT";

    private String sampleMode = "FULL";

    private List<String> caseIds;

    private Integer limit;

    private String category;

    private String difficulty;

    private String baselineRunId;

    private String promptVersion;

    private String modelVersion;

    private Map<String, Object> runTags;
}
