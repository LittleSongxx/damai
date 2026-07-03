package org.javaup.ai.assistant.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.context.AiUserContext;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssistantRunRequestMessage implements Serializable {
    private String runId;
    private String conversationId;
    private Long userId;
    private AiUserContext user;
    private String userMessage;
    private String clientContextJson;
}
