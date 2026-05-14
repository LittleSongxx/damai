package org.javaup.ai.guardrails;

import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.entity.AiGuardrailHit;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.mapper.AiGuardrailHitMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GuardrailAuditService {

    private final AssistantRunService assistantRunService;
    private final AiGuardrailHitMapper guardrailHitMapper;

    public GuardrailAuditService(AssistantRunService assistantRunService, AiGuardrailHitMapper guardrailHitMapper) {
        this.assistantRunService = assistantRunService;
        this.guardrailHitMapper = guardrailHitMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void publish(String runId, String stage, GuardrailResult result, String contentPreview) {
        if (runId == null || result == null || result.getAction() == GuardrailResult.Action.PASS) {
            return;
        }
        AiRun run = assistantRunService.getRunInternal(runId);
        if (run == null) {
            return;
        }
        AiGuardrailHit hit = new AiGuardrailHit();
        hit.setHitId("gr_" + UUID.randomUUID().toString().replace("-", ""));
        hit.setRunId(runId);
        hit.setConversationId(run.getConversationId());
        hit.setUserId(run.getUserId());
        hit.setStage(stage);
        hit.setGuardrailAction(result.getAction().name());
        hit.setRuleNames(String.join(",", result.getReasons()));
        hit.setContentPreview(truncate(contentPreview));
        hit.setStatus(1);
        guardrailHitMapper.insert(hit);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("stage", stage);
        payload.put("action", result.getAction().name());
        payload.put("reasons", result.getReasons());
        payload.put("contentPreview", truncate(contentPreview));
        if (result.getSanitizedContent() != null) {
            payload.put("sanitizedContent", result.getSanitizedContent());
        }
        assistantRunService.appendEvent(runId,
                result.getAction() == GuardrailResult.Action.BLOCK ? AssistantEventTypes.GUARDRAIL_TRIGGERED : AssistantEventTypes.GUARDRAIL_WARN,
                payload);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 240) {
            return value;
        }
        return value.substring(0, 240);
    }
}
