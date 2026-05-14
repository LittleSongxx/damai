package org.javaup.ai.guardrails;

import org.javaup.ai.config.AiGuardrailsProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class InputGuardrailService {

    private final AiGuardrailsProperties properties;
    private final PiiDetector piiDetector;
    private final PromptInjectionChecker promptInjectionChecker;

    public InputGuardrailService(AiGuardrailsProperties properties,
                                 PiiDetector piiDetector,
                                 PromptInjectionChecker promptInjectionChecker) {
        this.properties = properties;
        this.piiDetector = piiDetector;
        this.promptInjectionChecker = promptInjectionChecker;
    }

    public GuardrailResult check(String content) {
        if (!properties.isEnabled() || content == null || content.isBlank()) {
            return GuardrailResult.pass();
        }
        List<String> reasons = new ArrayList<>();
        if (properties.isPromptInjectionEnabled()) {
            List<String> injectionFindings = promptInjectionChecker.detect(content);
            if (!injectionFindings.isEmpty()) {
                reasons.addAll(injectionFindings);
                if (properties.isBlockOnPromptInjection()) {
                    return GuardrailResult.block(
                            "检测到疑似绕过系统约束或窃取内部提示的请求，已拦截。",
                            "抱歉，这个请求涉及系统提示、内部配置或越权操作，我不能继续执行。");
                }
            }
        }
        if (properties.isPiiEnabled()) {
            List<String> piiFindings = piiDetector.detect(content);
            if (!piiFindings.isEmpty()) {
                reasons.addAll(piiFindings);
            }
        }
        if (reasons.isEmpty()) {
            return GuardrailResult.pass();
        }
        return GuardrailResult.builder()
                .action(GuardrailResult.Action.WARN)
                .reasons(reasons)
                .build();
    }
}
