package org.javaup.ai.vo;

import lombok.Data;

@Data
public class RagEvalCaseRequest {
    private String question;
    private String expectedAnswer;
    private String expectedChunks;
    private String category;
    private String difficulty;
    private String datasetId;
    private String datasetVersion;
    private String caseType;
    private String tags;
    private String requiredFacts;
    private String forbiddenFacts;
    private String expectedCitations;
    private String reviewStatus;
}
