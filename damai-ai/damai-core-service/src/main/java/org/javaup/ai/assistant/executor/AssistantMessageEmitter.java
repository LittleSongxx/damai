package org.javaup.ai.assistant.executor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantRunService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
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

    /**
     * 真流式发射：逐 token 订阅 Flux，每个 token 作为 MESSAGE_DELTA 事件持久化。
     * 返回完整拼接后的回答文本（用于 responseSummary / ChatMemory）。
     */
    public String emitStream(String runId, String chatId, Flux<String> tokenStream) {
        return consumeStream(tokenStream, token -> runService.appendEvent(runId, AssistantEventTypes.MESSAGE_DELTA, Map.of(
                "runId", runId,
                "chatId", chatId,
                "delta", token
        )));
    }

    public String collectStream(Flux<String> tokenStream) {
        return consumeStream(tokenStream, token -> {
        });
    }

    private String consumeStream(Flux<String> tokenStream, java.util.function.Consumer<String> chunkConsumer) {
        StringBuilder fullAnswer = new StringBuilder();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        tokenStream
                .limitRate(100)
                .onBackpressureBuffer(200)
                .doOnNext(token -> {
            if (token != null && !token.isEmpty()) {
                fullAnswer.append(token);
                chunkConsumer.accept(token);
            }
        }).doOnError(errorRef::set).blockLast();

        if (errorRef.get() != null) {
            log.warn("流式 LLM 输出中断: {}", errorRef.get().getMessage());
        }

        return fullAnswer.toString();
    }

    private List<String> chunk(String content, int chunkSize) {
        ArrayList<String> chunks = new ArrayList<>();
        for (int index = 0; index < content.length(); index += chunkSize) {
            chunks.add(content.substring(index, Math.min(content.length(), index + chunkSize)));
        }
        return chunks.isEmpty() ? List.of(content) : chunks;
    }
}
