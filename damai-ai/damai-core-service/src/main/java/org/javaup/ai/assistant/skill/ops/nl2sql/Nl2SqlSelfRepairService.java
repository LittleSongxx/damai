package org.javaup.ai.assistant.skill.ops.nl2sql;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.config.RetryProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class Nl2SqlSelfRepairService {

    private final ChatClient chatClient;
    private final RetryProperties retryProperties;

    public Nl2SqlSelfRepairService(@Qualifier("unifiedChatClient") ChatClient chatClient,
                                    RetryProperties retryProperties) {
        this.chatClient = chatClient;
        this.retryProperties = retryProperties;
    }

    public String repairSql(String originalQuestion, String failedSql, String errorMessage, String schema) {
        int maxAttempts = retryProperties.getMaxRetries();
        String currentSql = failedSql;
        String currentError = errorMessage;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.info("NL2SQL self-repair attempt {}/{}: error={}", attempt, maxAttempts, currentError);
            String repairPrompt = buildRepairPrompt(originalQuestion, currentSql, currentError, schema);
            try {
                String result = chatClient.prompt().user(repairPrompt).call().content();
                if (result != null && result.trim().toUpperCase().startsWith("SELECT")) {
                    log.info("NL2SQL self-repair succeeded at attempt {}", attempt);
                    return result.trim();
                }
                if (result != null && result.contains("```sql")) {
                    String extracted = extractSql(result);
                    if (extracted != null) {
                        log.info("NL2SQL self-repair succeeded at attempt {} (extracted from markdown)", attempt);
                        return extracted;
                    }
                }
                currentSql = result;
                currentError = "Generated SQL does not start with SELECT";
            } catch (Exception e) {
                log.warn("NL2SQL self-repair attempt {} failed: {}", attempt, e.getMessage());
                currentError = e.getMessage();
            }
        }
        log.warn("NL2SQL self-repair exhausted {} attempts", maxAttempts);
        return null;
    }

    private String buildRepairPrompt(String question, String failedSql, String error, String schema) {
        return String.format("""
                你是一个 SQL 专家。用户的问题是：
                %s
                
                之前生成的 SQL 执行失败了：
                ```sql
                %s
                ```
                
                错误信息：
                %s
                
                数据库 Schema：
                %s
                
                请修复这个 SQL，只输出修复后的 SQL，不要解释。
                """, question, failedSql, error, schema);
    }

    private String extractSql(String text) {
        int start = text.indexOf("```sql");
        if (start < 0) return null;
        start = text.indexOf('\n', start) + 1;
        int end = text.indexOf("```", start);
        if (end < 0) return null;
        return text.substring(start, end).trim();
    }
}
