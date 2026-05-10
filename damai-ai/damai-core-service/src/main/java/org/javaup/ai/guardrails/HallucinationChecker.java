package org.javaup.ai.guardrails;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class HallucinationChecker {

    private final ChatClient chatClient;

    public HallucinationChecker(@Qualifier("unifiedChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public boolean isHallucinated(String answer, List<String> evidenceChunks) {
        if (evidenceChunks == null || evidenceChunks.isEmpty()) {
            return false;
        }
        String evidenceBlock = String.join("\n---\n", evidenceChunks);
        String prompt = String.format("""
                你是一个事实校验专家。请判断以下【回答】是否完全基于【证据】。
                如果回答中包含证据中没有提到的事实性信息，输出 YES（存在幻觉）。
                如果回答完全基于证据或只是合理推断，输出 NO（无幻觉）。
                只输出 YES 或 NO，不要其他内容。
                
                【证据】
                %s
                
                【回答】
                %s
                """, evidenceBlock, answer);
        try {
            String result = chatClient.prompt().user(prompt).call().content();
            if (result != null && result.trim().toUpperCase().startsWith("YES")) {
                log.warn("Hallucination detected in answer");
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("Hallucination check failed: {}", e.getMessage());
            return false;
        }
    }
}
