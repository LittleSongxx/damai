package org.javaup.ai.guardrails;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ExternalToolResponseValidator {

    private static final int MAX_RESPONSE_LENGTH = 100_000;
    private static final Pattern SCRIPT_TAG = Pattern.compile("(?i)<script[^>]*>");
    private static final Pattern SQL_INJECTION = Pattern.compile(
            "(?i)(drop\\s+table|alter\\s+table|truncate\\s+table|delete\\s+from|insert\\s+into|update\\s+.*\\s+set)");
    private static final Pattern COMMAND_INJECTION = Pattern.compile(
            "(?i)(\\|\\s*/bin/|\\|\\s*/usr/|;\\s*rm\\s+-|\\$\\(.*\\)|`[^`]*`)");

    private final List<String> blockedResponsePatterns = List.of(
            "忽略之前的指令", "ignore previous instructions", "ignore all previous",
            "你是", "you are now", "system prompt", "系统提示词",
            "DAN mode", "jailbreak"
    );

    public ValidationResult validate(String toolName, String responseContent) {
        if (responseContent == null || responseContent.isEmpty()) {
            return ValidationResult.PASS;
        }

        if (responseContent.length() > MAX_RESPONSE_LENGTH) {
            log.warn("external tool {} returned oversized response ({} chars), truncating", toolName, responseContent.length());
            return new ValidationResult(false, "response exceeds maximum length of " + MAX_RESPONSE_LENGTH + " characters",
                    responseContent.substring(0, MAX_RESPONSE_LENGTH));
        }

        for (String blockedPattern : blockedResponsePatterns) {
            if (responseContent.toLowerCase().contains(blockedPattern.toLowerCase())) {
                log.warn("external tool {} returned content matching blocked pattern: {}", toolName, blockedPattern);
                return new ValidationResult(false, "response contains potentially malicious content pattern",
                        "[content blocked for security]");
            }
        }

        if (SCRIPT_TAG.matcher(responseContent).find()) {
            log.warn("external tool {} returned <script> tag content", toolName);
            return new ValidationResult(false, "response contains script tags",
                    SCRIPT_TAG.matcher(responseContent).replaceAll("[script removed]"));
        }

        if (SQL_INJECTION.matcher(responseContent).find()) {
            log.warn("external tool {} returned content matching SQL injection pattern", toolName);
            return new ValidationResult(false, "response contains SQL patterns",
                    "[content filtered for SQL safety]");
        }

        if (COMMAND_INJECTION.matcher(responseContent).find()) {
            log.warn("external tool {} returned content matching command injection pattern", toolName);
            return new ValidationResult(false, "response contains command injection patterns",
                    "[content filtered for command safety]");
        }

        return ValidationResult.PASS;
    }

    public record ValidationResult(boolean passed, String reason, String sanitizedContent) {

        public static final ValidationResult PASS = new ValidationResult(true, "", null);

        public String effectiveContent(String original) {
            return passed ? original : sanitizedContent;
        }
    }
}
