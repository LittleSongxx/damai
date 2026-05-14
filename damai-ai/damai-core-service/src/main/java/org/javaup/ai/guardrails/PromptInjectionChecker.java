package org.javaup.ai.guardrails;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class PromptInjectionChecker {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("忽略(之前|以上|前面).{0,20}(指令|规则|提示)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(system prompt|developer message|hidden prompt)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(bypass|override).{0,20}(policy|guardrail|rule)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(泄露|导出|显示).{0,20}(token|密钥|system|prompt|内部配置)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(调用|执行).{0,20}(所有工具|任意工具|隐藏工具)", Pattern.CASE_INSENSITIVE)
    );

    public List<String> detect(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return INJECTION_PATTERNS.stream()
                .filter(pattern -> pattern.matcher(normalized).find())
                .map(pattern -> "PROMPT_INJECTION:" + pattern.pattern())
                .toList();
    }
}
