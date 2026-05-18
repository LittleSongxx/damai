package org.javaup.ai.assistant.executor;

import com.alibaba.fastjson.JSON;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantExecutionMode;
import org.javaup.ai.assistant.AssistantRouteDecision;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillDecision;
import org.javaup.ai.assistant.AssistantSkillDefinitionService;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.javaup.ai.assistant.AssistantSkillRegistry;
import org.javaup.ai.assistant.AssistantSkillResourceBundle;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.AssistantSkillPolicyGuard;
import org.javaup.ai.assistant.AssistantSkillSchemaValidator;
import org.javaup.ai.assistant.memory.AssistantMemoryContext;
import org.javaup.ai.assistant.memory.AssistantMemoryService;
import org.javaup.ai.assistant.mq.AssistantRunCompletedPublisher;
import org.javaup.ai.assistant.profile.AssistantUserProfileContext;
import org.javaup.ai.assistant.profile.AssistantUserProfileService;
import org.javaup.ai.assistant.tool.AssistantSkillToolRegistry;
import org.javaup.ai.assistant.tool.AssistantSkillToolScope;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.guardrails.GuardrailAuditService;
import org.javaup.ai.guardrails.GuardrailResult;
import org.javaup.ai.guardrails.ResponseGuardrailService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SkillExecutor implements AssistantExecutor {

    private final AssistantRunService runService;
    private final AssistantSkillRegistry skillRegistry;
    private final AssistantMessageEmitter messageEmitter;
    private final AssistantMemoryService memoryService;
    private final AssistantUserProfileService userProfileService;
    private final AssistantRunCompletedPublisher runCompletedPublisher;
    private final AssistantSkillDefinitionService skillDefinitionService;
    private final AssistantSkillPolicyGuard policyGuard;
    private final AssistantSkillSchemaValidator schemaValidator;
    private final AssistantSkillToolRegistry toolRegistry;
    private final ResponseGuardrailService responseGuardrailService;
    private final GuardrailAuditService guardrailAuditService;

    public SkillExecutor(AssistantRunService runService,
                         AssistantSkillRegistry skillRegistry,
                         AssistantMessageEmitter messageEmitter,
                         AssistantMemoryService memoryService,
                         AssistantUserProfileService userProfileService,
                         AssistantRunCompletedPublisher runCompletedPublisher) {
        this(runService, skillRegistry, messageEmitter, memoryService, userProfileService, runCompletedPublisher,
                (AssistantSkillDefinitionService) null, null, null, null, null, null);
    }

    @Autowired
    public SkillExecutor(AssistantRunService runService,
                         AssistantSkillRegistry skillRegistry,
                         AssistantMessageEmitter messageEmitter,
                         AssistantMemoryService memoryService,
                         AssistantUserProfileService userProfileService,
                         AssistantRunCompletedPublisher runCompletedPublisher,
                         ObjectProvider<AssistantSkillDefinitionService> skillDefinitionServiceProvider,
                         ObjectProvider<AssistantSkillPolicyGuard> policyGuardProvider,
                         ObjectProvider<AssistantSkillSchemaValidator> schemaValidatorProvider,
                         ObjectProvider<AssistantSkillToolRegistry> toolRegistryProvider,
                         ObjectProvider<ResponseGuardrailService> responseGuardrailServiceProvider,
                         ObjectProvider<GuardrailAuditService> guardrailAuditServiceProvider) {
        this(runService, skillRegistry, messageEmitter, memoryService, userProfileService, runCompletedPublisher,
                skillDefinitionServiceProvider == null ? null : skillDefinitionServiceProvider.getIfAvailable(),
                policyGuardProvider == null ? null : policyGuardProvider.getIfAvailable(),
                schemaValidatorProvider == null ? null : schemaValidatorProvider.getIfAvailable(),
                toolRegistryProvider == null ? null : toolRegistryProvider.getIfAvailable(),
                responseGuardrailServiceProvider == null ? null : responseGuardrailServiceProvider.getIfAvailable(),
                guardrailAuditServiceProvider == null ? null : guardrailAuditServiceProvider.getIfAvailable());
    }

    private SkillExecutor(AssistantRunService runService,
                          AssistantSkillRegistry skillRegistry,
                          AssistantMessageEmitter messageEmitter,
                          AssistantMemoryService memoryService,
                          AssistantUserProfileService userProfileService,
                          AssistantRunCompletedPublisher runCompletedPublisher,
                          AssistantSkillDefinitionService skillDefinitionService,
                          AssistantSkillPolicyGuard policyGuard,
                          AssistantSkillSchemaValidator schemaValidator,
                          AssistantSkillToolRegistry toolRegistry,
                          ResponseGuardrailService responseGuardrailService,
                          GuardrailAuditService guardrailAuditService) {
        this.runService = runService;
        this.skillRegistry = skillRegistry;
        this.messageEmitter = messageEmitter;
        this.memoryService = memoryService;
        this.userProfileService = userProfileService;
        this.runCompletedPublisher = runCompletedPublisher;
        this.skillDefinitionService = skillDefinitionService;
        this.policyGuard = policyGuard;
        this.schemaValidator = schemaValidator;
        this.toolRegistry = toolRegistry;
        this.responseGuardrailService = responseGuardrailService;
        this.guardrailAuditService = guardrailAuditService;
    }

    @Override
    public AssistantExecutionMode mode() {
        return AssistantExecutionMode.SKILL;
    }

    @Override
    public void execute(AssistantExecutionContext context) {
        AiRun run = context.getRun();
        AssistantRouteDecision decision = context.getPlan().getRouteDecision();
        AssistantSkillDecision skillDecision = context.getPlan().getSkillDecision();
        AssistantSkill skill = skillDecision != null && skillDecision.getDescriptor() != null
                ? skillRegistry.getRequired(skillDecision.getDescriptor().getSkillId())
                : skillRegistry.getRequired(decision.getRouteType());
        AssistantSkillDescriptor descriptor = skillDecision != null && skillDecision.getDescriptor() != null
                ? skillDecision.getDescriptor()
                : skillRegistry.getDescriptor(skill);
        AssistantSkillResourceBundle resources = skillDefinitionService == null || descriptor == null
                ? AssistantSkillResourceBundle.empty()
                : skillDefinitionService.loadResources(descriptor.getSkillId());
        if (policyGuard != null) {
            policyGuard.verifyBeforeExecution(context.getUser(), descriptor);
        }
        runService.bindSkill(run, descriptor);
        long skillStartTime = System.currentTimeMillis();
        runService.appendEvent(run.getRunId(), AssistantEventTypes.SKILL_STARTED, skillEventPayload(run, decision, skill, descriptor, skillDecision, null));
        boolean requiresMemory = descriptor == null || !Boolean.FALSE.equals(descriptor.getRequiresMemory());
        AssistantMemoryContext memoryContext = requiresMemory
                ? memoryService.load(run.getConversationId(), run.getUserId())
                : AssistantMemoryContext.empty();
        AssistantUserProfileContext userProfileContext = userProfileService.load(run.getUserId());
        AssistantSkillContext skillContext = AssistantSkillContext.of(run, context.getUser(), context.getRequest(), memoryContext, userProfileContext, descriptor, resources);
        if (schemaValidator != null) {
            schemaValidator.validateInput(descriptor, skillContext);
        }
        AssistantSkillResult result;
        AssistantSkillToolScope.ScopeHandle toolScope = toolRegistry == null
                ? AssistantSkillToolScope.open("", java.util.List.of("*"))
                : toolRegistry.openScope(descriptor);
        try {
            result = skill.execute(skillContext);
        } finally {
            toolScope.close();
        }
        if (schemaValidator != null) {
            schemaValidator.validateOutput(descriptor, result);
        }
        if (policyGuard != null) {
            policyGuard.verifyAfterExecution(descriptor, result);
        }
        runService.appendEvent(run.getRunId(), AssistantEventTypes.SKILL_COMPLETED, skillEventPayload(run, decision, skill, descriptor, skillDecision, System.currentTimeMillis() - skillStartTime));
        if (result.getMessageStream() != null) {
            String fullAnswer;
            if (responseGuardrailService != null && responseGuardrailService.shouldBufferBeforeStreaming(descriptor)) {
                fullAnswer = messageEmitter.collectStream(result.getMessageStream());
                String guarded = applyResponseGuardrails(run, result, fullAnswer);
                result.setMessage(guarded);
                result.setResponseSummary(guarded);
                messageEmitter.emitMessage(run.getRunId(), run.getConversationId(), guarded);
            } else {
                fullAnswer = messageEmitter.emitStream(run.getRunId(), run.getConversationId(), result.getMessageStream());
                String guarded = applyResponseGuardrails(run, result, fullAnswer);
                result.setMessage(guarded);
                result.setResponseSummary(guarded);
                emitReplacementIfNeeded(run, fullAnswer, guarded);
            }
        } else {
            String guarded = applyResponseGuardrails(run, result, result.getMessage());
            result.setMessage(guarded);
            result.setResponseSummary(guarded);
            messageEmitter.emitMessage(run.getRunId(), run.getConversationId(), guarded);
        }
        runService.appendEvent(run.getRunId(), AssistantEventTypes.MESSAGE_COMPLETED, Map.of(
                "runId", run.getRunId(),
                "chatId", run.getConversationId()
        ));
        AiAction pendingAction = result.getPendingAction();
        if (pendingAction != null) {
            runService.appendEvent(run.getRunId(), AssistantEventTypes.ACTION_REQUIRED, actionPayload(run.getRunId(), pendingAction));
            runService.markWaitingAction(run, "WAITING_ACTION", result.getResponseSummary());
        } else {
            runService.markCompleted(run, "RESPONDED", result.getResponseSummary());
        }
        publishRunCompleted(run.getRunId());
        runService.appendEvent(run.getRunId(), AssistantEventTypes.RUN_COMPLETED, Map.of(
                "runId", run.getRunId(),
                "status", runService.getRun(run.getRunId()).getRunStatus()
        ));
    }

    private void publishRunCompleted(String runId) {
        try {
            runCompletedPublisher.publish(runService.getRun(runId));
        } catch (RuntimeException ignored) {
        }
    }

    private Map<String, Object> skillEventPayload(AiRun run,
                                                  AssistantRouteDecision decision,
                                                  AssistantSkill skill,
                                                  AssistantSkillDescriptor descriptor,
                                                  AssistantSkillDecision skillDecision,
                                                  Long durationMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getRunId());
        payload.put("chatId", run.getConversationId());
        payload.put("routeType", decision.getRouteType().getCode());
        payload.put("skill", skill.getClass().getSimpleName());
        payload.put("skillId", descriptor == null ? "" : safe(descriptor.getSkillId()));
        payload.put("skillName", descriptor == null ? skill.getClass().getSimpleName() : safe(descriptor.getName()));
        payload.put("version", descriptor == null ? "" : safe(descriptor.getVersion()));
        payload.put("riskLevel", descriptor == null || descriptor.getRiskLevel() == null ? "" : descriptor.getRiskLevel().name());
        payload.put("selectionReason", skillDecision == null ? "legacy_route:" + decision.getRouteType().getCode() : safe(skillDecision.getReason()));
        if (durationMs != null) {
            payload.put("durationMs", durationMs);
        }
        return payload;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String applyResponseGuardrails(AiRun run, AssistantSkillResult result, String content) {
        if (responseGuardrailService == null || content == null || content.isBlank()) {
            return content;
        }
        GuardrailResult guardrailResult = responseGuardrailService.check(content, result.getEvidenceChunks());
        if (guardrailResult.getAction() == GuardrailResult.Action.PASS) {
            return content;
        }
        if (guardrailAuditService != null) {
            guardrailAuditService.publish(run.getRunId(), "response_output", guardrailResult, content);
        }
        if (guardrailResult.getSanitizedContent() != null && !guardrailResult.getSanitizedContent().isBlank()) {
            return guardrailResult.getSanitizedContent();
        }
        if (guardrailResult.isBlocked()) {
            return "抱歉，本次回答触发了输出安全策略，已被拦截。";
        }
        return content;
    }

    private void emitReplacementIfNeeded(AiRun run, String originalContent, String guardedContent) {
        if (guardedContent == null || originalContent == null || guardedContent.equals(originalContent)) {
            return;
        }
        runService.appendEvent(run.getRunId(), AssistantEventTypes.MESSAGE_REPLACED, Map.of(
                "runId", run.getRunId(),
                "chatId", run.getConversationId(),
                "content", guardedContent
        ));
    }

    private Map<String, Object> actionPayload(String runId, AiAction action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("actionId", action.getActionId());
        payload.put("actionType", action.getActionType());
        payload.put("status", action.getActionStatus());
        payload.put("previewSummary", action.getPreviewSummary());
        payload.put("expiresAt", action.getExpiresAt());
        payload.put("preview", action.getPreviewJson() == null ? Map.of() : JSON.parseObject(action.getPreviewJson()));
        return payload;
    }
}
