package org.javaup.ai.vo;

import lombok.Data;

@Data
public class RagBadCaseConvertRequest {
    private String expectedAnswer;
    private String expectedChunks;
    private String category;
    private String difficulty;
    private String caseType;
    private String datasetId;
    private String datasetVersion;
}
