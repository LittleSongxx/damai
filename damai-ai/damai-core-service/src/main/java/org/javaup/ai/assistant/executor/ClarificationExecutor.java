package org.javaup.ai.assistant.executor;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantExecutionMode;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.entity.AiRun;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ClarificationExecutor implements AssistantExecutor {

    private final AssistantRunService runService;
    private final AssistantMessageEmitter messageEmitter;

    @Override
    public AssistantExecutionMode mode() {
        return AssistantExecutionMode.CLARIFICATION;
    }

    @Override
    public void execute(AssistantExecutionContext context) {
        AiRun run = context.getRun();
        runService.appendEvent(run.getRunId(), AssistantEventTypes.CLARIFICATION_REQUIRED, Map.of(
                "runId", run.getRunId(),
                "chatId", run.getConversationId(),
                "reason", context.getPlan().getReason(),
                "options", context.getPlan().getOptions() == null ? List.of() : context.getPlan().getOptions()
        ));
        messageEmitter.emitMessage(run.getRunId(), run.getConversationId(), context.getPlan().getResponseMessage());
        runService.appendEvent(run.getRunId(), AssistantEventTypes.MESSAGE_COMPLETED, Map.of(
                "runId", run.getRunId(),
                "chatId", run.getConversationId()
        ));
        runService.markCompleted(run, "CLARIFICATION", context.getPlan().getResponseMessage());
        runService.appendEvent(run.getRunId(), AssistantEventTypes.RUN_COMPLETED, Map.of(
                "runId", run.getRunId(),
                "status", runService.getRun(run.getRunId()).getRunStatus()
        ));
    }
}
