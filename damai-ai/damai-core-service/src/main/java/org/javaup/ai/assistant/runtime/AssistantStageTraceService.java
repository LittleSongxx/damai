package org.javaup.ai.assistant.runtime;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.context.AiRequestContext;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.entity.AiTrace;
import org.javaup.ai.mapper.AiTraceMapper;
import org.javaup.ai.service.AiObservabilityService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantStageTraceService {

    private final AiTraceMapper aiTraceMapper;
    private final AssistantRunService runService;
    private final AiObservabilityService observabilityService;

    public StageSpan startStage(String stageKey,
                                String requestType,
                                String userInput,
                                String modelName,
                                Map<String, Object> metadata) {
        AiRequestContext requestContext = AiRequestContextHolder.getOptional().orElse(null);
        String traceId = observabilityService.generateTraceId();
        StageSpan span = StageSpan.builder()
                .traceId(traceId)
                .runId(requestContext == null ? null : requestContext.getRunId())
                .conversationId(requestContext == null ? null : requestContext.getConversationId())
                .userId(requestContext == null || requestContext.getUser() == null ? null : requestContext.getUser().getUserId())
                .stageKey(stageKey)
                .requestType(requestType)
                .modelName(modelName)
                .userInput(truncate(userInput, 1000))
                .metadata(metadata == null ? Map.of() : new LinkedHashMap<>(metadata))
                .startedAt(System.currentTimeMillis())
                .build();
        if (StringUtils.hasText(span.runId())) {
            runService.updateStage(span.runId(), stageKey);
            runService.appendEvent(span.runId(), AssistantEventTypes.STAGE_STARTED, Map.of(
                    "traceId", traceId,
                    "stageKey", stageKey,
                    "requestType", requestType,
                    "metadata", span.metadata()
            ));
        }
        return span;
    }

    public void complete(StageSpan span,
                         String aiOutput,
                         Integer promptTokens,
                         Integer completionTokens,
                         Integer totalTokens,
                         BigDecimal estimatedCost,
                         Map<String, Object> extraMetadata) {
        persist(span, true, aiOutput, null, promptTokens, completionTokens, totalTokens, estimatedCost, extraMetadata);
    }

    public void fail(StageSpan span, Throwable throwable, Map<String, Object> extraMetadata) {
        persist(span, false, null, throwable == null ? null : throwable.getMessage(), null, null, null, null, extraMetadata);
    }

    public List<AiTrace> listStageTraces(String runId) {
        return aiTraceMapper.selectList(Wrappers.lambdaQuery(AiTrace.class)
                .eq(AiTrace::getRunId, runId)
                .eq(AiTrace::getStatus, 1)
                .isNotNull(AiTrace::getStepKey)
                .orderByAsc(AiTrace::getCreateTime));
    }

    private void persist(StageSpan span,
                         boolean success,
                         String aiOutput,
                         String errorMessage,
                         Integer promptTokens,
                         Integer completionTokens,
                         Integer totalTokens,
                         BigDecimal estimatedCost,
                         Map<String, Object> extraMetadata) {
        if (span == null) {
            return;
        }
        long latencyMs = Math.max(0L, System.currentTimeMillis() - span.startedAt());
        Map<String, Object> mergedMetadata = new LinkedHashMap<>(span.metadata());
        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            mergedMetadata.putAll(extraMetadata);
        }
        AiTrace trace = new AiTrace();
        trace.setTraceId(span.traceId());
        trace.setConversationId(span.conversationId());
        trace.setUserId(span.userId());
        trace.setRunId(span.runId());
        trace.setStepKey(span.stageKey());
        trace.setModelName(span.modelName());
        trace.setRequestType(span.requestType());
        trace.setPromptTokens(promptTokens);
        trace.setCompletionTokens(completionTokens);
        trace.setTotalTokens(totalTokens);
        trace.setLatencyMs(latencyMs);
        trace.setEstimatedCost(estimatedCost);
        trace.setUserInput(span.userInput());
        trace.setAiOutput(truncate(aiOutput, 1000));
        trace.setSuccess(success);
        trace.setErrorMessage(errorMessage);
        trace.setMetadata(mergedMetadata.isEmpty() ? null : JSON.toJSONString(mergedMetadata));
        trace.setCreateTime(new Date());
        trace.setEditTime(new Date());
        trace.setStatus(1);
        aiTraceMapper.insert(trace);
        if (StringUtils.hasText(span.runId())) {
            if (success) {
                runService.appendEvent(span.runId(), AssistantEventTypes.STAGE_COMPLETED, Map.of(
                        "traceId", span.traceId(),
                        "stageKey", span.stageKey(),
                        "latencyMs", latencyMs,
                        "metadata", mergedMetadata
                ));
            } else {
                runService.appendEvent(span.runId(), AssistantEventTypes.STAGE_FAILED, Map.of(
                        "traceId", span.traceId(),
                        "stageKey", span.stageKey(),
                        "latencyMs", latencyMs,
                        "message", errorMessage,
                        "metadata", mergedMetadata
                ));
            }
        }
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) + "..." : value;
    }

    @Builder
    public record StageSpan(
            String traceId,
            String runId,
            String conversationId,
            Long userId,
            String stageKey,
            String requestType,
            String modelName,
            String userInput,
            Map<String, Object> metadata,
            long startedAt
    ) {
    }
}
