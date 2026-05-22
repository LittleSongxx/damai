package org.javaup.ai.vo;

import lombok.Data;

@Data
public class RagEvalCaseRequest {
    private String question;
    private String expectedAnswer;
    private String expectedChunks;
    private String category;
    private String difficulty;
}
