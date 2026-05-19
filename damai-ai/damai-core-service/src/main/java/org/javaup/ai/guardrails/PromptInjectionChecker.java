package org.javaup.ai.guardrails;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class PromptInjectionChecker {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // Original patterns
            Pattern.compile("忽略(之前|以上|前面).{0,20}(指令|规则|提示)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(system prompt|developer message|hidden prompt)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(bypass|override).{0,20}(policy|guardrail|rule)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(泄露|导出|显示).{0,20}(token|密钥|system|prompt|内部配置)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(调用|执行).{0,20}(所有工具|任意工具|隐藏工具)", Pattern.CASE_INSENSITIVE),

            // DAN / role-playing bypass
            Pattern.compile("(DAN|do anything now|jailbreak)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("你.{0,10}(现在|从现在开始).{0,10}(是|扮演).{0,15}(角色|身份)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(假装|模拟|角色扮演).{0,20}(没有|无|不需要).{0,20}(限制|规则)", Pattern.CASE_INSENSITIVE),

            // Encoding tricks
            Pattern.compile("(base64|unicode|hex).{0,20}(解码|decod)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("ignore.{0,5}(all|全部).{0,10}(previous|之前|以上).{0,10}(instruction|指令)", Pattern.CASE_INSENSITIVE),

            // Prompt extraction
            Pattern.compile("output.{0,5}(your|你的).{0,5}(prompt|提示词|system|系统).{0,10}(message|信息|指令)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(tell|显示|输出|打印).{0,5}me.{0,5}(your|你的).{0,5}(rule|规则|secret|秘密|password|密码)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("现在.{0,10}你.{0,20}(不再|不是|解除).{0,10}(限制|规则|guardrail)", Pattern.CASE_INSENSITIVE),

            // Forced disclosure
            Pattern.compile("forget.{0,5}(everything|一切).{0,5}(you|你).{0,10}(know|知道)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(token|api.?key|secret.?key).{0,10}(泄露|导出|显示|打印|输出|reveal|expose)", Pattern.CASE_INSENSITIVE),

            // Multi-language injection
            Pattern.compile("(我需要|我想要).{0,20}(绕过|破解|黑入|hack)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(repeat|重复|复述|reproduce).{0,5}(after|之后|以下).{0,5}(me|我)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("你是.{0,5}(自由|不受限制|没有限制)", Pattern.CASE_INSENSITIVE)
    );

    public List<String> detect(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        List<String> findings = new ArrayList<>();
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                findings.add("PROMPT_INJECTION:" + pattern.pattern());
            }
        }
        return findings;
    }
}
