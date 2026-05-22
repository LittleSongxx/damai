package org.javaup.ai.vo;

import lombok.Data;

import java.util.List;

@Data
public class AssistantActionResultVo {

    private String actionId;

    private String status;

    private String message;

    private String orderNumber;

    private String orderListAddress;

    /**
     * 审批失败后的替代方案推荐 —— 遵循 Dify 智能重新提问设计。
     *
     * <p>当用户拒绝购票预览或预览过期时，提供可操作的下步建议，
     * 而非仅告知"已取消"。每条建议为面向用户的自然语言文本。
     */
    private List<String> suggestedAlternatives;
}
