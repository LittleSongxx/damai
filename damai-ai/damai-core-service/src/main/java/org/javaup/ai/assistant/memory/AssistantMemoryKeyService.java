package org.javaup.ai.assistant.memory;

import org.javaup.ai.context.AiRequestContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AssistantMemoryKeyService {

    public String currentUserConversationKey(String conversationId) {
        return userConversationKey(AiRequestContextHolder.getRequiredUser().getUserId(), conversationId);
    }

    public String userConversationKey(Long userId, String conversationId) {
        if (userId == null || !StringUtils.hasText(conversationId)) {
            return conversationId;
        }
        return "user_" + userId + ":" + conversationId;
    }
}
