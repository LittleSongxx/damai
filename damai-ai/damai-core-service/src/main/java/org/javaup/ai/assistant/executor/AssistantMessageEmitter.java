package org.javaup.ai.assistant.executor;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantRunService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AssistantMessageEmitter {

    private final AssistantRunService runService;

    public void emitMessage(String runId, String chatId, String content) {
        if (content == null) {
            return;
        }
        for (String chunk : chunk(content, 180)) {
            runService.appendEvent(runId, AssistantEventTypes.MESSAGE_DELTA, Map.of(
                    "runId", runId,
                    "chatId", chatId,
                    "delta", chunk
            ));
        }
    }

    private List<String> chunk(String content, int chunkSize) {
        ArrayList<String> chunks = new ArrayList<>();
        for (int index = 0; index < content.length(); index += chunkSize) {
            chunks.add(content.substring(index, Math.min(content.length(), index + chunkSize)));
        }
        return chunks.isEmpty() ? List.of(content) : chunks;
    }
}
