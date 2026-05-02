package org.javaup.ai.dto;

import lombok.Data;

import java.util.Map;

@Data
public class AssistantRunCreateRequest {

    private String chatId;

    private String message;

    private Map<String, Object> clientContext;
}
