package org.javaup.ai.assistant;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.entity.AiRetrieval;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.AiRunEvent;
import org.javaup.ai.entity.AiToolCall;
import org.javaup.ai.mapper.AiActionMapper;
import org.javaup.ai.mapper.AiRetrievalMapper;
import org.javaup.ai.mapper.AiRunEventMapper;
import org.javaup.ai.mapper.AiRunMapper;
import org.javaup.ai.mapper.AiToolCallMapper;
import org.javaup.ai.vo.AssistantRunCreatedVo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AssistantRunService {

    private final AiRunMapper runMapper;
    private final AiRunEventMapper runEventMapper;
    private final AiActionMapper actionMapper;
    private final AiToolCallMapper toolCallMapper;
    private final AiRetrievalMapper retrievalMapper;
    private final AssistantConversationService conversationService;

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
    public AiRunEvent appendEvent(String runId, String eventType, Object payload) {
        AiRun run = getRun(runId);
        if (run == null) {
            throw new IllegalStateException("Run does not exist: " + runId);
        }
        AiRunEvent event = new AiRunEvent();
        event.setEventId(nextId("evt"));
        event.setRunId(runId);
        event.setConversationId(run.getConversationId());
        event.setUserId(run.getUserId());
        event.setEventOrder(nextEventOrder(runId));
        event.setEventType(eventType);
        event.setPayloadJson(payload == null ? "{}" : JSON.toJSONString(payload));
        event.setStatus(1);
        runEventMapper.insert(event);
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
    public AiAction createAction(String runId, AssistantActionType actionType, Object preview) {
        AiRun run = getRun(runId);
        AiAction action = new AiAction();
        action.setActionId(nextId("action"));
        action.setRunId(runId);
        action.setConversationId(run.getConversationId());
        action.setUserId(run.getUserId());
        action.setActionType(actionType.name());
        action.setActionStatus(AssistantActionStatus.WAITING.name());
        action.setPreviewJson(JSON.toJSONString(preview));
        action.setExpiresAt(new Date(System.currentTimeMillis() + 15 * 60 * 1000L));
        action.setStatus(1);
        actionMapper.insert(action);
        return action;
    }

    public AiAction getPendingAction(String runId) {
        return actionMapper.selectOne(actionQuery(runId, AssistantActionStatus.WAITING.name()));
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
        action.setActionStatus(AssistantActionStatus.APPROVED.name());
        action.setApprovedAt(new Date());
        actionMapper.updateById(action);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markActionCompleted(AiAction action, Object result) {
        action.setActionStatus(AssistantActionStatus.COMPLETED.name());
        action.setResultJson(result == null ? null : JSON.toJSONString(result));
        actionMapper.updateById(action);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markActionRejected(AiAction action, Object result) {
        action.setActionStatus(AssistantActionStatus.REJECTED.name());
        action.setRejectedAt(new Date());
        action.setResultJson(result == null ? null : JSON.toJSONString(result));
        actionMapper.updateById(action);
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordToolCall(String runId, String toolName, String toolType, Object input, Object output, long durationMs, boolean success, String errorMessage) {
        AiRun run = getRun(runId);
        AiToolCall toolCall = new AiToolCall();
        toolCall.setCallId(nextId("tool"));
        toolCall.setRunId(runId);
        toolCall.setConversationId(run == null ? null : run.getConversationId());
        toolCall.setUserId(run == null ? null : run.getUserId());
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

    private int nextEventOrder(String runId) {
        Long count = runEventMapper.selectCount(Wrappers.lambdaQuery(AiRunEvent.class)
                .eq(AiRunEvent::getRunId, runId)
                .eq(AiRunEvent::getStatus, 1));
        return count == null ? 1 : count.intValue() + 1;
    }

    private String nextId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
