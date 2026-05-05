package org.javaup.ai.assistant.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssistantRunCompletedMessage {

    private String runId;

    private String conversationId;

    private Long userId;

    private String routeType;

    private Date occurredAt;
}
