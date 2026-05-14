package org.javaup.ai.assistant.runtime;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.service.AiObservabilityService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class AssistantObservedChatService {

    private final AssistantStageTraceService stageTraceService;
    private final AiObservabilityService observabilityService;

    public String call(ChatClient chatClient,
                       String stageKey,
                       String requestType,
                       String modelName,
                       String prompt) {
        return call(chatClient, stageKey, requestType, modelName, null, prompt);
    }

    public String call(ChatClient chatClient,
                       String stageKey,
                       String requestType,
                       String modelName,
                       String conversationMemoryKey,
                       String prompt) {
        AssistantStageTraceService.StageSpan span = stageTraceService.startStage(
                stageKey,
                requestType,
                prompt,
                modelName,
                Map.of("mode", "call"));
        try {
            ChatResponse response = preparePrompt(chatClient, conversationMemoryKey, prompt)
                    .call()
                    .chatResponse();
            String output = extractOutput(response);
            Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
            Integer promptTokens = usage == null ? null : usage.getPromptTokens();
            Integer completionTokens = usage == null ? null : usage.getCompletionTokens();
            Integer totalTokens = null;
            if (promptTokens != null || completionTokens != null) {
                totalTokens = (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
            }
            BigDecimal cost = null;
            if (promptTokens != null || completionTokens != null) {
                cost = observabilityService.calculateCost(modelName, promptTokens == null ? 0 : promptTokens, completionTokens == null ? 0 : completionTokens);
            }
            stageTraceService.complete(span, output, promptTokens, completionTokens, totalTokens, cost, Map.of());
            return output;
        } catch (Exception ex) {
            stageTraceService.fail(span, ex, Map.of());
            throw ex;
        }
    }

    public Flux<String> stream(ChatClient chatClient,
                               String stageKey,
                               String requestType,
                               String modelName,
                               String conversationMemoryKey,
                               String prompt) {
        AssistantStageTraceService.StageSpan span = stageTraceService.startStage(
                stageKey,
                requestType,
                prompt,
                modelName,
                Map.of("mode", "stream"));
        StringBuilder output = new StringBuilder();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        return preparePrompt(chatClient, conversationMemoryKey, prompt)
                .stream()
                .content()
                .doOnNext(token -> {
                    if (token != null) {
                        output.append(token);
                    }
                })
                .doOnError(errorRef::set)
                .doOnComplete(() -> stageTraceService.complete(span, output.toString(), null, null, null, null, Map.of("streamed", true)))
                .doFinally(signalType -> {
                    Throwable error = errorRef.get();
                    if (error != null) {
                        stageTraceService.fail(span, error, Map.of("streamed", true));
                    }
                });
    }

    private ChatClient.ChatClientRequestSpec preparePrompt(ChatClient chatClient, String conversationMemoryKey, String prompt) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt().user(prompt);
        if (StringUtils.hasText(conversationMemoryKey)) {
            spec = spec.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationMemoryKey));
        }
        return spec;
    }

    private String extractOutput(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }
}
