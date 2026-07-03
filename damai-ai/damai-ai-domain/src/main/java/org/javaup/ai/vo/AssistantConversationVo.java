package org.javaup.ai.vo;

import lombok.Data;

@Data
public class AssistantConversationVo {

    private String chatId;

    private String title;

    private String routeType;

    private String latestRunId;

    private String latestStatus;
}
