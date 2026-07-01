package org.javaup.ai.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CustomerEscalationRequest {

    private String runId;

    private String conversationId;

    private String userQuestion;

    private String aiAnswer;

    private String sentiment;

    private Double sentimentIntensity;

    private String intentCode;

    private List<Map<String, Object>> sourceRefs;

    private Map<String, Object> businessContext;

    private String reason;

    private String suggestedReply;
}
