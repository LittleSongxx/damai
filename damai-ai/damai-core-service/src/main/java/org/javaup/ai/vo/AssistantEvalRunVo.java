package org.javaup.ai.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class AssistantEvalRunVo {

    private String suite;

    private String status;

    private String evalRunId;

    private Integer totalCases;

    private String resultType;

    private Integer completedCases;

    private Double progress;

    private Map<String, Object> metrics;

    private Map<String, Object> qualityGate;

    private List<String> nextActions;
}
