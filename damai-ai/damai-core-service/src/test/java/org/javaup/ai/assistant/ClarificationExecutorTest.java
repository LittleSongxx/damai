package org.javaup.ai.assistant;

import org.javaup.ai.assistant.executor.AssistantExecutionContext;
import org.javaup.ai.assistant.executor.AssistantMessageEmitter;
import org.javaup.ai.assistant.executor.ClarificationExecutor;
import org.javaup.ai.entity.AiRun;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClarificationExecutorTest {

    @Test
    void shouldEmitClarificationMessageAndCompleteRun() {
        AssistantRunService runService = mock(AssistantRunService.class);
        AssistantMessageEmitter messageEmitter = mock(AssistantMessageEmitter.class);
        AiRun latestRun = run();
        latestRun.setRunStatus(AssistantRunStatus.COMPLETED.name());
        when(runService.getRun("run_1")).thenReturn(latestRun);
        ClarificationExecutor executor = new ClarificationExecutor(runService, messageEmitter);

        executor.execute(AssistantExecutionContext.builder()
                .run(run())
                .plan(AssistantExecutionPlan.builder()
                        .executionMode(AssistantExecutionMode.CLARIFICATION)
                        .responseMessage("请补充你的目标")
                        .reason("clarification:OTHER")
                        .options(List.of("查票", "查规则"))
                        .build())
                .build());

        verify(runService).appendEvent(eq("run_1"), eq(AssistantEventTypes.CLARIFICATION_REQUIRED), any());
        verify(messageEmitter).emitMessage("run_1", "chat_1", "请补充你的目标");
        verify(runService).markCompleted(any(AiRun.class), eq("CLARIFICATION"), eq("请补充你的目标"));
        verify(runService).appendEvent(eq("run_1"), eq(AssistantEventTypes.RUN_COMPLETED), any());
    }

    private AiRun run() {
        AiRun run = new AiRun();
        run.setRunId("run_1");
        run.setConversationId("chat_1");
        run.setUserId(1L);
        return run;
    }
}
