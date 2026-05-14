package org.javaup.ai.guardrails;

import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.javaup.ai.assistant.AssistantSkillRiskLevel;
import org.javaup.ai.config.AiGuardrailsProperties;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ResponseGuardrailService {

    private final AiGuardrailsProperties properties;
    private final OutputGuardrailService outputGuardrailService;

    public ResponseGuardrailService(AiGuardrailsProperties properties, OutputGuardrailService outputGuardrailService) {
        this.properties = properties;
        this.outputGuardrailService = outputGuardrailService;
    }

    public boolean shouldBufferBeforeStreaming(AssistantSkillDescriptor descriptor) {
        if (!properties.isEnabled() || !properties.isBufferHighRiskStreaming()) {
            return false;
        }
        if (descriptor == null || descriptor.getRiskLevel() == null) {
            return false;
        }
        return descriptor.getRiskLevel() == AssistantSkillRiskLevel.HIGH
                || descriptor.getRiskLevel() == AssistantSkillRiskLevel.CRITICAL
                || descriptor.getRiskLevel() == AssistantSkillRiskLevel.MEDIUM;
    }

    public GuardrailResult check(String content, List<String> evidenceChunks) {
        return outputGuardrailService.check(content, evidenceChunks);
    }
}
