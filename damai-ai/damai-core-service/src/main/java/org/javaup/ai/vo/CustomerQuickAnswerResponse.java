package org.javaup.ai.vo;

import lombok.Builder;
import lombok.Data;
import org.javaup.ai.entity.EscalationTicket;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class CustomerQuickAnswerResponse {

    private Boolean hit;

    private String answerMode;

    private String hotQuestionId;

    private String intentCode;

    private String routeHint;

    private String directAnswer;

    private List<Map<String, Object>> actionButtons;

    private List<Map<String, Object>> sourceRefs;

    private List<CustomerHotQuestionVo> suggestions;

    private Map<String, Object> clientContext;

    private Map<String, Object> sentiment;

    private EscalationTicket escalationTicket;

    private Long latencyMs;
}
