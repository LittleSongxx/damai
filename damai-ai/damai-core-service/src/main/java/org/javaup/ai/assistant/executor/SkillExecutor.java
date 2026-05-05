package org.javaup.ai.assistant.executor;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantExecutionMode;
import org.javaup.ai.assistant.AssistantRouteDecision;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillRegistry;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.memory.AssistantMemoryContext;
import org.javaup.ai.assistant.memory.AssistantMemoryService;
import org.javaup.ai.assistant.mq.AssistantRunCompletedPublisher;
import org.javaup.ai.assistant.profile.AssistantUserProfileContext;
import org.javaup.ai.assistant.profile.AssistantUserProfileService;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.entity.AiRun;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class SkillExecutor implements AssistantExecutor {

    private final AssistantRunService runService;
    private final AssistantSkillRegistry skillRegistry;
    private final AssistantMessageEmitter messageEmitter;
    private final AssistantMemoryService memoryService;
    private final AssistantUserProfileService userProfileService;
    private final AssistantRunCompletedPublisher runCompletedPublisher;

    @Override
    public AssistantExecutionMode mode() {
        return AssistantExecutionMode.SKILL;
    }

    @Override
    public void execute(AssistantExecutionContext context) {
        AiRun run = context.getRun();
        AssistantRouteDecision decision = context.getPlan().getRouteDecision();
        AssistantSkill skill = skillRegistry.getRequired(decision.getRouteType());
        long skillStartTime = System.currentTimeMillis();
        runService.appendEvent(run.getRunId(), AssistantEventTypes.SKILL_STARTED, Map.of(
                "runId", run.getRunId(),
                "chatId", run.getConversationId(),
                "routeType", decision.getRouteType().getCode(),
                "skill", skill.getClass().getSimpleName()
        ));
        AssistantMemoryContext memoryContext = memoryService.load(run.getConversationId(), run.getUserId());
        AssistantUserProfileContext userProfileContext = userProfileService.load(run.getUserId());
        AssistantSkillResult result = skill.execute(AssistantSkillContext.of(run, context.getUser(), context.getRequest(), memoryContext, userProfileContext));
        runService.appendEvent(run.getRunId(), AssistantEventTypes.SKILL_COMPLETED, Map.of(
                "runId", run.getRunId(),
                "chatId", run.getConversationId(),
                "routeType", decision.getRouteType().getCode(),
                "skill", skill.getClass().getSimpleName(),
                "durationMs", System.currentTimeMillis() - skillStartTime
        ));
        messageEmitter.emitMessage(run.getRunId(), run.getConversationId(), result.getMessage());
        runService.appendEvent(run.getRunId(), AssistantEventTypes.MESSAGE_COMPLETED, Map.of(
                "runId", run.getRunId(),
                "chatId", run.getConversationId()
        ));
        AiAction pendingAction = result.getPendingAction();
        if (pendingAction != null) {
            runService.appendEvent(run.getRunId(), AssistantEventTypes.ACTION_REQUIRED, Map.of(
                    "runId", run.getRunId(),
                    "actionId", pendingAction.getActionId(),
                    "actionType", pendingAction.getActionType(),
                    "previewJson", pendingAction.getPreviewJson(),
                    "status", pendingAction.getActionStatus()
            ));
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
}
