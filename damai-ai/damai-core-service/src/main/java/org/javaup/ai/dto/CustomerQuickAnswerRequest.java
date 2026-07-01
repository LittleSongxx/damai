package org.javaup.ai.dto;

import lombok.Data;

import java.util.Map;

@Data
public class CustomerQuickAnswerRequest {

    private String chatId;

    private String message;

    private String intentHint;

    private String hotQuestionId;

    private String programId;

    private String orderNo;

    private String categoryId;

    private Map<String, Object> clientContext;
}
