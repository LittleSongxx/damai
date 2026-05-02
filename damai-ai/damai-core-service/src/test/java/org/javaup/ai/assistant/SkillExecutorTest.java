package org.javaup.ai.assistant;

import org.javaup.ai.assistant.executor.AssistantExecutionContext;
import org.javaup.ai.assistant.executor.AssistantMessageEmitter;
import org.javaup.ai.assistant.executor.SkillExecutor;
import org.javaup.ai.assistant.memory.AssistantMemoryContext;
import org.javaup.ai.assistant.memory.AssistantMemoryService;
import org.javaup.ai.assistant.profile.AssistantUserProfileContext;
import org.javaup.ai.assistant.profile.AssistantUserProfileService;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.entity.AiRun;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillExecutorTest {

    @Test
    void shouldEmitSkillEventsAndCompleteRun() {
        AssistantRunService runService = mock(AssistantRunService.class);
        AssistantSkillRegistry skillRegistry = mock(AssistantSkillRegistry.class);
        AssistantMessageEmitter messageEmitter = mock(AssistantMessageEmitter.class);
        AssistantMemoryService memoryService = mock(AssistantMemoryService.class);
        AssistantUserProfileService userProfileService = mock(AssistantUserProfileService.class);
        AssistantSkill skill = mock(AssistantSkill.class);
        when(skillRegistry.getRequired(AssistantRouteType.BUSINESS)).thenReturn(skill);
        when(memoryService.load("chat_1", 1L)).thenReturn(AssistantMemoryContext.empty());
        when(userProfileService.load(1L)).thenReturn(AssistantUserProfileContext.empty());
        when(skill.execute(any())).thenReturn(AssistantSkillResult.builder()
                .message("回答内容")
                .responseSummary("回答内容")
                .build());
        AiRun latestRun = run();
        latestRun.setRunStatus(AssistantRunStatus.COMPLETED.name());
        when(runService.getRun("run_1")).thenReturn(latestRun);
        SkillExecutor executor = new SkillExecutor(runService, skillRegistry, messageEmitter, memoryService, userProfileService);

        executor.execute(context());

        verify(runService).appendEvent(eq("run_1"), eq(AssistantEventTypes.SKILL_STARTED), any());
        verify(runService).appendEvent(eq("run_1"), eq(AssistantEventTypes.SKILL_COMPLETED), any());
        verify(messageEmitter).emitMessage("run_1", "chat_1", "回答内容");
        verify(runService).markCompleted(any(AiRun.class), eq("RESPONDED"), eq("回答内容"));
        verify(memoryService).refreshAfterRun(latestRun);
        verify(userProfileService).refreshAfterRun(latestRun);
        verify(runService).appendEvent(eq("run_1"), eq(AssistantEventTypes.RUN_COMPLETED), any());
    }

    private AssistantExecutionContext context() {
        AssistantRunCreateRequest request = new AssistantRunCreateRequest();
        request.setMessage("买票");
        AiUserContext user = AiUserContext.builder().userId(1L).build();
        return AssistantExecutionContext.builder()
                .run(run())
                .request(request)
                .user(user)
                .plan(AssistantExecutionPlan.builder()
                        .executionMode(AssistantExecutionMode.SKILL)
                        .routeDecision(AssistantRouteDecision.builder()
                                .routeType(AssistantRouteType.BUSINESS)
                                .reason("keyword:business")
                                .fromFallback(false)
                                .clarificationRequired(false)
                                .build())
                        .options(List.of())
                        .build())
                .build();
    }

    private AiRun run() {
        AiRun run = new AiRun();
        run.setRunId("run_1");
        run.setConversationId("chat_1");
        run.setUserId(1L);
        return run;
    }
}
