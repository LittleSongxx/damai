package org.javaup.ai.assistant;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.entity.AiConversationMemorySummary;
import org.javaup.ai.entity.AiRetrieval;
import org.javaup.ai.entity.AiRetrievalTrace;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.AiRunEvent;
import org.javaup.ai.entity.AiTrace;
import org.javaup.ai.entity.AiToolCall;
import org.javaup.ai.mapper.AiActionMapper;
import org.javaup.ai.mapper.AiConversationMemorySummaryMapper;
import org.javaup.ai.mapper.AiRetrievalMapper;
import org.javaup.ai.mapper.AiRetrievalTraceMapper;
import org.javaup.ai.mapper.AiRunEventMapper;
import org.javaup.ai.mapper.AiRunMapper;
import org.javaup.ai.mapper.AiTraceMapper;
import org.javaup.ai.mapper.AiToolCallMapper;
import org.javaup.ai.assistant.runtime.RunGraphStateService;
import org.javaup.ai.vo.AssistantRunCreatedVo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AssistantRunService {

    private final AiRunMapper runMapper;
    private final AiRunEventMapper runEventMapper;
    private final AiActionMapper actionMapper;
    private final AiToolCallMapper toolCallMapper;
    private final AiRetrievalMapper retrievalMapper;
    private final AiTraceMapper traceMapper;
    private final AiRetrievalTraceMapper retrievalTraceMapper;
    private final AiConversationMemorySummaryMapper memorySummaryMapper;
    private final AssistantConversationService conversationService;
    private final AssistantRunEventStreamService eventStreamService;
    private final RunGraphStateService runGraphStateService;

    @Transactional(rollbackFor = Exception.class)
    public AssistantRunCreatedVo createRun(AssistantRunCreateRequest request) {
        String chatId = request.getChatId();
        if (chatId == null || chatId.isBlank()) {
            chatId = nextId("chat");
            request.setChatId(chatId);
        }
        conversationService.ensureConversation(chatId, request.getMessage());
        AiRun run = new AiRun();
        run.setRunId(nextId("run"));
        run.setConversationId(chatId);
        run.setUserId(AiRequestContextHolder.getRequiredUser().getUserId());
        run.setRunStatus(AssistantRunStatus.CREATED.name());
        run.setCurrentStage("CREATED");
        run.setUserMessage(request.getMessage());
        run.setClientContextJson(request.getClientContext() == null ? null : JSON.toJSONString(request.getClientContext()));
        run.setStatus(1);
        runMapper.insert(run);
        runGraphStateService.initializeRun(run);
        conversationService.bindLatestRun(chatId, null, run.getRunId(), run.getRunStatus(), request.getMessage());
        return AssistantRunCreatedVo.builder()
                .runId(run.getRunId())
                .chatId(chatId)
                .status(run.getRunStatus())
                .eventStreamPath("/assistant/runs/" + run.getRunId() + "/events")
                .build();
    }

    public AiRun getRun(String runId) {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        return getRun(runId, userId);
    }

    public AiRun getRun(String runId, Long userId) {
        return runMapper.selectOne(Wrappers.lambdaQuery(AiRun.class)
                .eq(AiRun::getRunId, runId)
                .eq(AiRun::getUserId, userId)
                .eq(AiRun::getStatus, 1)
                .last("limit 1"));
    }

    public AiRun getRunInternal(String runId) {
        return runMapper.selectOne(Wrappers.lambdaQuery(AiRun.class)
                .eq(AiRun::getRunId, runId)
                .eq(AiRun::getStatus, 1)
                .last("limit 1"));
    }

    public List<AiRunEvent> listEvents(String runId) {
        return runEventMapper.selectList(Wrappers.lambdaQuery(AiRunEvent.class)
                .eq(AiRunEvent::getRunId, runId)
                .eq(AiRunEvent::getStatus, 1)
                .orderByAsc(AiRunEvent::getEventOrder));
    }

    @Transactional(rollbackFor = Exception.class)
    public void startRun(AiRun run, AssistantRouteType routeType, String stage) {
        run.setRouteType(routeType.getCode());
        run.setRunStatus(AssistantRunStatus.RUNNING.name());
        run.setCurrentStage(stage);
        runMapper.updateById(run);
        conversationService.bindLatestRun(run.getConversationId(), routeType, run.getRunId(), run.getRunStatus(), run.getUserMessage());
    }

    @Transactional(rollbackFor = Exception.class)
    public void bindSkill(AiRun run, AssistantSkillDescriptor descriptor) {
        if (run == null || descriptor == null) {
            return;
        }
        run.setSkillId(descriptor.getSkillId());
        run.setSkillVersion(descriptor.getVersion());
        run.setSkillSnapshotJson(JSON.toJSONString(descriptor));
        runMapper.updateById(run);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiRunEvent appendEvent(String runId, String eventType, Object payload) {
        AiRun run = runMapper.selectByRunIdForUpdate(runId);
        if (run == null) {
            throw new IllegalStateException("Run does not exist: " + runId);
        }
        int nextOrder = (run.getEventSeq() == null ? 0 : run.getEventSeq()) + 1;
        run.setEventSeq(nextOrder);
        runMapper.updateById(run);
        AiRunEvent event = new AiRunEvent();
        event.setEventId(nextId("evt"));
        event.setRunId(runId);
        event.setConversationId(run.getConversationId());
        event.setUserId(run.getUserId());
        event.setEventOrder(nextOrder);
        event.setEventType(eventType);
        Map<String, Object> normalizedPayload = normalizeEventPayload(run, eventType, payload);
        event.setPayloadJson(JSON.toJSONString(normalizedPayload));
        event.setStatus(1);
        runEventMapper.insert(event);
        runGraphStateService.recordEvent(run, event, normalizedPayload);
        publishAfterCommit(event);
        return event;
    }

    @Transactional(rollbackFor = Exception.class)
    public void markWaitingAction(AiRun run, String stage, String summary) {
        run.setRunStatus(AssistantRunStatus.WAITING_ACTION.name());
        run.setCurrentStage(stage);
        run.setResponseSummary(summary);
        runMapper.updateById(run);
        conversationService.bindLatestRun(run.getConversationId(), routeOf(run), run.getRunId(), run.getRunStatus(), run.getUserMessage());
    }

    @Transactional(rollbackFor = Exception.class)
    public void markCompleted(AiRun run, String stage, String summary) {
        run.setRunStatus(AssistantRunStatus.COMPLETED.name());
        run.setCurrentStage(stage);
        run.setResponseSummary(summary);
        run.setCompletedAt(new Date());
        runMapper.updateById(run);
        conversationService.bindLatestRun(run.getConversationId(), routeOf(run), run.getRunId(), run.getRunStatus(), run.getUserMessage());
    }

    @Transactional(rollbackFor = Exception.class)
    public void markFailed(AiRun run, String stage, String errorMessage) {
        run.setRunStatus(AssistantRunStatus.FAILED.name());
        run.setCurrentStage(stage);
        run.setErrorMessage(errorMessage);
        run.setCompletedAt(new Date());
        runMapper.updateById(run);
        conversationService.bindLatestRun(run.getConversationId(), routeOf(run), run.getRunId(), run.getRunStatus(), run.getUserMessage());
    }

    @Transactional(rollbackFor = Exception.class)
    public int resetForResume(String runId) {
        AiRun run = getRunInternal(runId);
        if (run == null) {
            throw new IllegalArgumentException("Run does not exist: " + runId);
        }
        run.setRunStatus(AssistantRunStatus.CREATED.name());
        run.setCurrentStage("RESUME_REQUESTED");
        run.setErrorMessage(null);
        run.setCompletedAt(null);
        run.setResumed(run.getResumed() == null ? 1 : run.getResumed() + 1);
        runMapper.updateById(run);
        conversationService.bindLatestRun(run.getConversationId(), routeOf(run), run.getRunId(), run.getRunStatus(), run.getUserMessage());
        return run.getResumed();
    }

    @Transactional(rollbackFor = Exception.class)
    public AiAction createAction(String runId, AssistantActionType actionType, Object preview) {
        return createAction(runId, actionType, preview, null, null, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiAction createAction(String runId,
                                 AssistantActionType actionType,
                                 Object preview,
                                 String previewSummary,
                                 String snapshotHash,
                                 String idempotencyKey,
                                 Date expiresAt) {
        AiRun run = getRunInternal(runId);
        AiAction action = new AiAction();
        action.setActionId(nextId("action"));
        action.setRunId(runId);
        action.setConversationId(run.getConversationId());
        action.setUserId(run.getUserId());
        action.setActionType(actionType.name());
        action.setActionStatus(AssistantActionStatus.WAITING.name());
        action.setPreviewJson(JSON.toJSONString(preview));
        action.setPreviewSummary(previewSummary);
        action.setSnapshotHash(snapshotHash);
        action.setIdempotencyKey(idempotencyKey);
        action.setVersion(0);
        action.setExpiresAt(expiresAt == null ? new Date(System.currentTimeMillis() + 15 * 60 * 1000L) : expiresAt);
        action.setStatus(1);
        actionMapper.insert(action);
        return action;
    }

    public AiAction getPendingAction(String runId) {
        AiAction action = actionMapper.selectOne(actionQuery(runId, AssistantActionStatus.WAITING.name()));
        if (isActionExpired(action)) {
            markActionExpired(action, null);
            return null;
        }
        return action;
    }

    public AiAction getLatestAction(String runId) {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        return actionMapper.selectOne(Wrappers.lambdaQuery(AiAction.class)
                .eq(AiAction::getRunId, runId)
                .eq(AiAction::getUserId, userId)
                .eq(AiAction::getStatus, 1)
                .orderByDesc(AiAction::getId)
                .last("limit 1"));
    }

    public AiAction getAction(String runId, String actionId) {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        return actionMapper.selectOne(Wrappers.lambdaQuery(AiAction.class)
                .eq(AiAction::getRunId, runId)
                .eq(AiAction::getActionId, actionId)
                .eq(AiAction::getUserId, userId)
                .eq(AiAction::getStatus, 1)
                .last("limit 1"));
    }

    @Transactional(rollbackFor = Exception.class)
    public void markActionApproved(AiAction action) {
        action.setActionStatus(AssistantActionStatus.APPROVING.name());
        action.setApprovedAt(new Date());
        action.setProcessingStartedAt(new Date());
        action.setVersion(action.getVersion() == null ? 1 : action.getVersion() + 1);
        actionMapper.updateById(action);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markActionCompleted(AiAction action, Object result, String orderNumber) {
        action.setActionStatus(AssistantActionStatus.COMPLETED.name());
        action.setResultJson(result == null ? null : JSON.toJSONString(result));
        action.setOrderNumber(orderNumber);
        action.setFailureCode(null);
        action.setFailureMessage(null);
        action.setCompletedAt(new Date());
        action.setVersion(action.getVersion() == null ? 1 : action.getVersion() + 1);
        actionMapper.updateById(action);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markActionRejected(AiAction action, Object result) {
        action.setActionStatus(AssistantActionStatus.REJECTED.name());
        action.setRejectedAt(new Date());
        action.setResultJson(result == null ? null : JSON.toJSONString(result));
        action.setCompletedAt(new Date());
        action.setVersion(action.getVersion() == null ? 1 : action.getVersion() + 1);
        actionMapper.updateById(action);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markActionOrdering(AiAction action) {
        action.setActionStatus(AssistantActionStatus.ORDERING.name());
        action.setVersion(action.getVersion() == null ? 1 : action.getVersion() + 1);
        actionMapper.updateById(action);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markActionFailed(AiAction action, String failureCode, String failureMessage, Object result) {
        action.setActionStatus(AssistantActionStatus.FAILED.name());
        action.setFailureCode(failureCode);
        action.setFailureMessage(failureMessage);
        action.setResultJson(result == null ? null : JSON.toJSONString(result));
        action.setCompletedAt(new Date());
        action.setVersion(action.getVersion() == null ? 1 : action.getVersion() + 1);
        actionMapper.updateById(action);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markActionExpired(AiAction action, Object result) {
        action.setActionStatus(AssistantActionStatus.EXPIRED.name());
        action.setFailureCode("ACTION_EXPIRED");
        action.setFailureMessage("待审批动作已过期");
        action.setResultJson(result == null ? null : JSON.toJSONString(result));
        action.setCompletedAt(new Date());
        action.setVersion(action.getVersion() == null ? 1 : action.getVersion() + 1);
        actionMapper.updateById(action);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean claimRunForProcessing(String runId) {
        return runMapper.update(null, Wrappers.lambdaUpdate(AiRun.class)
                .eq(AiRun::getRunId, runId)
                .eq(AiRun::getStatus, 1)
                .eq(AiRun::getRunStatus, AssistantRunStatus.CREATED.name())
                .set(AiRun::getRunStatus, AssistantRunStatus.RUNNING.name())
                .set(AiRun::getCurrentStage, "PLANNING")) > 0;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateStage(String runId, String stage) {
        if (runId == null || stage == null || stage.isBlank()) {
            return;
        }
        runMapper.update(null, Wrappers.lambdaUpdate(AiRun.class)
                .eq(AiRun::getRunId, runId)
                .eq(AiRun::getStatus, 1)
                .set(AiRun::getCurrentStage, stage));
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean claimActionForApproval(String runId, String actionId, Date now) {
        return actionMapper.update(null, Wrappers.lambdaUpdate(AiAction.class)
                .eq(AiAction::getRunId, runId)
                .eq(AiAction::getActionId, actionId)
                .eq(AiAction::getUserId, AiRequestContextHolder.getRequiredUser().getUserId())
                .eq(AiAction::getStatus, 1)
                .eq(AiAction::getActionStatus, AssistantActionStatus.WAITING.name())
                .and(wrapper -> wrapper.isNull(AiAction::getExpiresAt).or().gt(AiAction::getExpiresAt, now))
                .set(AiAction::getActionStatus, AssistantActionStatus.APPROVING.name())
                .set(AiAction::getApprovedAt, now)
                .set(AiAction::getProcessingStartedAt, now)
                .set(AiAction::getFailureCode, null)
                .set(AiAction::getFailureMessage, null)
                .setSql("version = ifnull(version, 0) + 1")) > 0;
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordToolCall(String runId, String toolName, String toolType, Object input, Object output, long durationMs, boolean success, String errorMessage) {
        AiRun run = getRunInternal(runId);
        AiToolCall toolCall = new AiToolCall();
        toolCall.setCallId(nextId("tool"));
        toolCall.setRunId(runId);
        toolCall.setConversationId(run == null ? null : run.getConversationId());
        toolCall.setUserId(run == null ? null : run.getUserId());
        toolCall.setSkillId(run == null ? null : run.getSkillId());
        toolCall.setToolName(toolName);
        toolCall.setToolType(toolType);
        toolCall.setInputJson(input == null ? null : JSON.toJSONString(input));
        toolCall.setOutputJson(output == null ? null : JSON.toJSONString(output));
        toolCall.setDurationMs(durationMs);
        toolCall.setSuccess(success ? 1 : 0);
        toolCall.setErrorMessage(errorMessage);
        toolCall.setStatus(1);
        toolCallMapper.insert(toolCall);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiRetrieval saveRetrieval(AiRetrieval retrieval) {
        retrieval.setStatus(1);
        if (retrieval.getRetrievalId() == null) {
            retrieval.setRetrievalId(nextId("retrieval"));
        }
        retrievalMapper.insert(retrieval);
        return retrieval;
    }

    public AiRetrieval getLatestRetrieval(String runId) {
        return retrievalMapper.selectOne(Wrappers.lambdaQuery(AiRetrieval.class)
                .eq(AiRetrieval::getRunId, runId)
                .eq(AiRetrieval::getStatus, 1)
                .orderByDesc(AiRetrieval::getId)
                .last("limit 1"));
    }

    public List<AiTrace> listStageTraces(String runId) {
        return traceMapper.selectList(Wrappers.lambdaQuery(AiTrace.class)
                .eq(AiTrace::getRunId, runId)
                .eq(AiTrace::getStatus, 1)
                .isNotNull(AiTrace::getStepKey)
                .orderByAsc(AiTrace::getCreateTime));
    }

    public List<AiRetrievalTrace> listRetrievalTraces(String runId) {
        return retrievalTraceMapper.selectList(Wrappers.lambdaQuery(AiRetrievalTrace.class)
                .eq(AiRetrievalTrace::getRunId, runId)
                .eq(AiRetrievalTrace::getStatus, 1)
                .orderByAsc(AiRetrievalTrace::getCreateTime));
    }

    public AiConversationMemorySummary getLatestMemorySummary(String conversationId, Long userId) {
        if (conversationId == null || userId == null) {
            return null;
        }
        return memorySummaryMapper.selectOne(Wrappers.lambdaQuery(AiConversationMemorySummary.class)
                .eq(AiConversationMemorySummary::getConversationId, conversationId)
                .eq(AiConversationMemorySummary::getUserId, userId)
                .eq(AiConversationMemorySummary::getStatus, 1)
                .orderByDesc(AiConversationMemorySummary::getCreateTime)
                .last("limit 1"));
    }

    private void publishAfterCommit(AiRunEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            eventStreamService.publish(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventStreamService.publish(event);
            }
        });
    }

    private Map<String, Object> normalizeEventPayload(AiRun run, String eventType, Object payload) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (payload instanceof Map<?, ?> map) {
            map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        } else if (payload != null) {
            normalized.put("data", payload);
        }
        normalized.putIfAbsent("runId", run.getRunId());
        normalized.put("eventType", eventType);
        normalized.putIfAbsent("graphNodeId", graphNodeId(eventType));
        normalized.putIfAbsent("eventCategory", org.javaup.ai.assistant.runtime.RunGraphDefinition.eventCategory(eventType));
        normalized.putIfAbsent("traceRef", run.getRunId());
        if (run.getResumableStateJson() != null && !run.getResumableStateJson().isBlank()) {
            normalized.putIfAbsent("checkpointId", run.getRunId() + ":" + run.getCurrentStage());
        }
        return normalized;
    }

    private String graphNodeId(String eventType) {
        return org.javaup.ai.assistant.runtime.RunGraphDefinition.nodeTypeForEvent(eventType);
    }

    private AssistantRouteType routeOf(AiRun run) {
        if (run == null || run.getRouteType() == null) {
            return null;
        }
        for (AssistantRouteType value : AssistantRouteType.values()) {
            if (value.getCode().equals(run.getRouteType())) {
                return value;
            }
        }
        return null;
    }

    private LambdaQueryWrapper<AiAction> actionQuery(String runId, String actionStatus) {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        return Wrappers.lambdaQuery(AiAction.class)
                .eq(AiAction::getRunId, runId)
                .eq(AiAction::getActionStatus, actionStatus)
                .eq(AiAction::getUserId, userId)
                .eq(AiAction::getStatus, 1)
                .last("limit 1");
    }

    private boolean isActionExpired(AiAction action) {
        return action != null && action.getExpiresAt() != null && action.getExpiresAt().before(new Date());
    }

    private String nextId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
