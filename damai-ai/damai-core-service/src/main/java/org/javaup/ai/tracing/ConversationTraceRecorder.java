package org.javaup.ai.tracing;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.runtime.AssistantStageTraceService;
import org.javaup.ai.entity.AiRetrievalTrace;
import org.javaup.ai.mapper.AiRetrievalTraceMapper;
import org.javaup.ai.tracing.model.ConversationLimitStats;
import org.javaup.ai.tracing.model.ConversationModelUsageTrace;
import org.javaup.ai.tracing.model.ConversationTraceStageCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class ConversationTraceRecorder {

    private final AssistantStageTraceService stageTraceService;
    private final AiRetrievalTraceMapper retrievalTraceMapper;
    private final String conversationId;
    private final String runId;
    private final String traceId;
    private final List<ConversationModelUsageTrace> modelUsageTraces = Collections.synchronizedList(new ArrayList<>());
    private final ConversationLimitStats limitStats = new ConversationLimitStats();

    public ConversationTraceRecorder(AssistantStageTraceService stageTraceService,
                                      AiRetrievalTraceMapper retrievalTraceMapper,
                                      String conversationId,
                                      String runId,
                                      String traceId) {
        this.stageTraceService = stageTraceService;
        this.retrievalTraceMapper = retrievalTraceMapper;
        this.conversationId = conversationId;
        this.runId = runId;
        this.traceId = traceId;
    }

    public String conversationId() {
        return conversationId;
    }

    public String runId() {
        return runId;
    }

    public String traceId() {
        return traceId;
    }

    public StageHandle startStage(ConversationTraceStageCode stageCode,
                                   String requestType,
                                   String summaryText,
                                   Object snapshot) {
        AssistantStageTraceService.StageSpan span = stageTraceService.startStage(
                stageCode.name(),
                requestType,
                summaryText,
                null,
                snapshot instanceof Map ? (Map<String, Object>) snapshot : Map.of("traceId", traceId)
        );
        return new StageHandle(span, System.currentTimeMillis(), stageCode);
    }

    public void completeStage(StageHandle stageHandle,
                               String summaryText,
                               Object snapshot) {
        if (stageHandle == null) {
            return;
        }
        Map<String, Object> extraMetadata = new LinkedHashMap<>();
        extraMetadata.put("traceId", traceId);
        extraMetadata.put("durationMs", System.currentTimeMillis() - stageHandle.startTimeMs());
        if (snapshot instanceof Map) {
            extraMetadata.putAll((Map<String, Object>) snapshot);
        }
        stageTraceService.complete(
                stageHandle.span(),
                summaryText,
                null, null, null, null,
                extraMetadata
        );
    }

    public void failStage(StageHandle stageHandle,
                           String summaryText,
                           String errorMessage,
                           Object snapshot) {
        if (stageHandle == null) {
            return;
        }
        Map<String, Object> extraMetadata = new LinkedHashMap<>();
        extraMetadata.put("traceId", traceId);
        extraMetadata.put("errorMessage", errorMessage);
        if (snapshot instanceof Map) {
            extraMetadata.putAll((Map<String, Object>) snapshot);
        }
        stageTraceService.fail(
                stageHandle.span(),
                new RuntimeException(errorMessage),
                extraMetadata
        );
    }

    public void addModelUsageTrace(ConversationModelUsageTrace trace) {
        if (trace != null) {
            modelUsageTraces.add(trace);
            limitStats.setModelCallsUsed(modelUsageTraces.size());
        }
    }

    public List<ConversationModelUsageTrace> snapshotModelUsageTraces() {
        return new ArrayList<>(modelUsageTraces);
    }

    public ConversationLimitStats limitStats() {
        return limitStats;
    }

    public void recordRetrievalResults(List<AiRetrievalTrace> results) {
        if (retrievalTraceMapper == null || results == null || results.isEmpty()) {
            return;
        }
        try {
            for (AiRetrievalTrace trace : results) {
                if (trace.getRunId() == null) {
                    trace.setRunId(runId);
                }
                if (trace.getChatId() == null) {
                    trace.setChatId(conversationId);
                }
                if (trace.getStatus() == null) {
                    trace.setStatus(1);
                }
                retrievalTraceMapper.insert(trace);
            }
        } catch (RuntimeException ex) {
            log.warn("failed to record retrieval results conversationId={} runId={}", conversationId, runId, ex);
        }
    }

    public record StageHandle(AssistantStageTraceService.StageSpan span, long startTimeMs, ConversationTraceStageCode stageCode) {

        public long durationMs() {
            return System.currentTimeMillis() - startTimeMs;
        }
    }
}
