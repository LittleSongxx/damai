package org.javaup.ai.vo;

import lombok.Builder;
import lombok.Data;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.entity.AiConversationMemorySummary;
import org.javaup.ai.entity.AiRetrieval;
import org.javaup.ai.entity.AiRetrievalTrace;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.AiRunEvent;
import org.javaup.ai.entity.AiTrace;

import java.util.List;

@Data
@Builder
public class AssistantRunDetailVo {

    private AiRun run;

    private List<AiRunEvent> events;

    private AiAction pendingAction;

    private AiAction latestAction;

    private AiRetrieval retrieval;

    private List<AiTrace> stageTraces;

    private List<AiRetrievalTrace> retrievalTraces;

    private AiConversationMemorySummary memorySummary;
}
