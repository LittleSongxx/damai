package org.javaup.ai.tracing.model;

import lombok.Data;

@Data
public class ConversationLimitStats {
    private int modelCallsUsed;
    private int modelCallsRunLimit;
    private int toolCallsUsed;
    private int toolCallsRunLimit;
}
