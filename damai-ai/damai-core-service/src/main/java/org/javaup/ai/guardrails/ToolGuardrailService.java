package org.javaup.ai.guardrails;

import com.alibaba.fastjson2.JSON;
import org.javaup.ai.config.AiGuardrailsProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ToolGuardrailService {

    private final AiGuardrailsProperties properties;
    private final PiiDetector piiDetector;
    private final ToxicityChecker toxicityChecker;

    public ToolGuardrailService(AiGuardrailsProperties properties,
                                PiiDetector piiDetector,
                                ToxicityChecker toxicityChecker) {
        this.properties = properties;
        this.piiDetector = piiDetector;
        this.toxicityChecker = toxicityChecker;
    }

    public GuardrailResult check(String toolName, String stage, Object payload) {
        if (!properties.isEnabled() || payload == null) {
            return GuardrailResult.pass();
        }
        String content = payload instanceof String ? (String) payload : JSON.toJSONString(payload);
        if (content == null || content.isBlank()) {
            return GuardrailResult.pass();
        }
        List<String> reasons = new ArrayList<>();
        if (properties.isToxicityEnabled()) {
            List<String> toxicFindings = toxicityChecker.detectToxicTerms(content);
            if (!toxicFindings.isEmpty()) {
                reasons.addAll(toxicFindings);
                return GuardrailResult.block(
                        "工具 " + toolName + " 的" + stage + "命中不适宜内容，已拦截。",
                        "抱歉，此次工具调用涉及不适宜内容，已被拦截。");
            }
        }
        if (properties.isPiiEnabled()) {
            List<String> piiFindings = piiDetector.detect(content);
            if (!piiFindings.isEmpty()) {
                reasons.addAll(piiFindings);
                String masked = piiDetector.mask(content);
                return GuardrailResult.builder()
                        .action("output".equalsIgnoreCase(stage) && properties.isBlockOnPii()
                                ? GuardrailResult.Action.BLOCK
                                : GuardrailResult.Action.WARN)
                        .reasons(reasons)
                        .sanitizedContent(masked)
                        .build();
            }
        }
        return reasons.isEmpty()
                ? GuardrailResult.pass()
                : GuardrailResult.builder().action(GuardrailResult.Action.WARN).reasons(reasons).build();
    }
}
