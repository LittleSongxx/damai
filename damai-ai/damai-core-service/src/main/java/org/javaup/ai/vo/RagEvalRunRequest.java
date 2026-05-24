package org.javaup.ai.vo;

import lombok.Data;

import java.util.List;

@Data
public class RagEvalRunRequest {
    private String category;
    private String difficulty;
    private List<String> caseIds;
    private Integer limit;
    private Integer topK;
    private Boolean enableRerank;
}
