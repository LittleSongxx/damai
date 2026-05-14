package org.javaup.ai.assistant;

import lombok.Builder;
import lombok.Data;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.entity.AiRetrieval;
import reactor.core.publisher.Flux;

import java.util.List;

@Data
@Builder
public class AssistantSkillResult {

    private String message;

    private String responseSummary;

    private AiAction pendingAction;

    private AiRetrieval retrieval;

    /**
     * 真流式 LLM 输出。如果非 null，则 SkillExecutor 会逐 token 发送 SSE delta 事件，
     * 而不是等完整 message 后再一次性切片发送。
     */
    private Flux<String> messageStream;

    private List<String> evidenceChunks;
}
