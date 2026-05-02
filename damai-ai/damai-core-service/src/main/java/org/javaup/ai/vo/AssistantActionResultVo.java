package org.javaup.ai.vo;

import lombok.Data;

@Data
public class AssistantActionResultVo {

    private String actionId;

    private String status;

    private String message;

    private String orderNumber;

    private String orderListAddress;
}
