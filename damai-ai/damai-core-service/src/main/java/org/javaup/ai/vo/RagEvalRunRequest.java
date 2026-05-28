package org.javaup.ai.vo;

import lombok.Data;

import java.util.List;

@Data
public class RagEvalRunRequest {
    private String datasetId;
    private String datasetVersion;
    private String category;
    private String difficulty;
    private List<String> caseTypes;
    private List<String> tags;
    private List<String> caseIds;
    private Integer limit;
    private Integer topK;
    private List<Integer> kValues;
    private Boolean enableRerank;
    private String retrievalConfigId;
    private String judgeConfigId;
    private String baselineRunId;
    private Boolean enableGenerationEval;
    private Boolean enableRetrieverEval;
    private Boolean enableLatencyEval;
    private Boolean enableCostEval;
    private String gitCommit;
    private String modelVersion;
    private String promptVersion;
}
