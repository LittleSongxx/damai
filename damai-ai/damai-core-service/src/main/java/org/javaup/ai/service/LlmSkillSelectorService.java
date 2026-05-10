package org.javaup.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LlmSkillSelectorService {

    private final ChatClient chatClient;

    public LlmSkillSelectorService(@Qualifier("unifiedChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String selectSkillId(String userMessage, List<AssistantSkillDescriptor> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        String candidateList = candidates.stream()
                .map(d -> String.format("- %s: %s (keywords: %s)",
                        d.getSkillId(),
                        d.getDescription() != null ? d.getDescription() : d.getName(),
                        d.getTriggerKeywords() != null ? String.join(",", d.getTriggerKeywords()) : ""))
                .collect(Collectors.joining("\n"));

        String prompt = String.format("""
                你是一个技能路由器。根据用户消息，从以下候选技能中选择最合适的一个。
                只输出技能ID，不要其他内容。
                
                【候选技能】
                %s
                
                【用户消息】
                %s
                
                【你的选择（只输出技能ID）】
                """, candidateList, userMessage);

        try {
            String result = chatClient.prompt().user(prompt).call().content();
            if (result != null) {
                String selected = result.trim().split("\\s+")[0];
                boolean valid = candidates.stream().anyMatch(c -> c.getSkillId().equals(selected));
                if (valid) {
                    log.info("LLM selected skill: {} for message: {}", selected, userMessage.substring(0, Math.min(50, userMessage.length())));
                    return selected;
                }
            }
        } catch (Exception e) {
            log.warn("LLM skill selection failed: {}", e.getMessage());
        }
        return null;
    }
}
