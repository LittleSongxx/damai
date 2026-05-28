package org.javaup.ai.vo;

import lombok.Data;

@Data
public class RagBadCaseRequest {
    private String traceId;
    private String question;
    private String generatedAnswer;
    private String retrievedChunksJson;
    private String expectedAnswer;
    private String expectedChunks;
    private String feedbackType;
    private String failureType;
    private String category;
    private String difficulty;
    private String caseType;
    private String operatorNote;
    private String reviewStatus;
}
