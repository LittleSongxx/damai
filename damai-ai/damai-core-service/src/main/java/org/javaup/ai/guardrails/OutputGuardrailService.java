package org.javaup.ai.guardrails;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.config.AiGuardrailsProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutputGuardrailService {

    private final AiGuardrailsProperties properties;
    private final PiiDetector piiDetector;
    private final ToxicityChecker toxicityChecker;
    private final HallucinationChecker hallucinationChecker;

    public GuardrailResult check(String content, List<String> evidenceChunks) {
        if (!properties.isEnabled() || content == null || content.isEmpty()) {
            return GuardrailResult.pass();
        }

        List<String> allReasons = new ArrayList<>();

        if (properties.isPiiEnabled()) {
            List<String> piiFindings = piiDetector.detect(content);
            if (!piiFindings.isEmpty()) {
                allReasons.addAll(piiFindings);
                if (properties.isBlockOnPii()) {
                    String masked = piiDetector.mask(content);
                    return GuardrailResult.block(
                            "检测到敏感个人信息，已自动脱敏处理。",
                            masked);
                }
            }
        }

        if (properties.isToxicityEnabled()) {
            List<String> toxicFindings = toxicityChecker.detectToxicTerms(content);
            if (!toxicFindings.isEmpty()) {
                allReasons.addAll(toxicFindings);
                return GuardrailResult.block(
                        "检测到不适宜内容，已拦截。",
                        "抱歉，我无法提供此类内容。请换一个话题。");
            }
        }

        if (properties.isHallucinationEnabled() && evidenceChunks != null && !evidenceChunks.isEmpty()) {
            boolean hallucinated = hallucinationChecker.isHallucinated(content, evidenceChunks);
            if (hallucinated) {
                allReasons.add("HALLUCINATION:detected");
                return GuardrailResult.builder()
                        .action(GuardrailResult.Action.WARN)
                        .reasons(allReasons)
                        .sanitizedContent(content + "\n\n注意：以上回答可能包含未经验证的信息，请以官方资料为准。")
                        .build();
            }
        }

        if (!allReasons.isEmpty()) {
            return GuardrailResult.builder()
                    .action(GuardrailResult.Action.WARN)
                    .reasons(allReasons)
                    .build();
        }
        return GuardrailResult.pass();
    }
}
