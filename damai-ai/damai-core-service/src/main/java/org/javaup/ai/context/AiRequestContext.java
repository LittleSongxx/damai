package org.javaup.ai.context;

import lombok.Builder;
import lombok.Data;

/**
 * AI 请求级上下文，贯穿会话、工作流和工具调用。
 */
@Data
@Builder
public class AiRequestContext {

    private AiUserContext user;

    private String conversationId;

    private String runId;

    private String requestType;
}
