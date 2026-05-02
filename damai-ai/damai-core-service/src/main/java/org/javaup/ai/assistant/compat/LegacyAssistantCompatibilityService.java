package org.javaup.ai.assistant.compat;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.AssistantActionStatus;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantRuntimeService;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.AiRunEvent;
import org.javaup.ai.entity.AiWorkflowRun;
import org.javaup.ai.entity.AiWorkflowStep;
import org.javaup.ai.enums.ChatType;
import org.javaup.ai.vo.AssistantActionResultVo;
import org.javaup.ai.vo.AssistantRunCreatedVo;
import org.javaup.ai.vo.AssistantRunDetailVo;
import org.javaup.ai.vo.CreateOrderVo;
import org.javaup.ai.vo.WorkflowDetailVo;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.javaup.ai.constants.DaMaiConstant.ORDER_LIST_ADDRESS;
import static org.javaup.ai.workflow.AiWorkflowStatus.COMPLETED;
import static org.javaup.ai.workflow.AiWorkflowStatus.FAILED;
import static org.javaup.ai.workflow.AiWorkflowStatus.REJECTED;
import static org.javaup.ai.workflow.AiWorkflowStatus.RUNNING;
import static org.javaup.ai.workflow.AiWorkflowStatus.WAITING_APPROVAL;

@Service
@RequiredArgsConstructor
public class LegacyAssistantCompatibilityService {

    private final AssistantRuntimeService assistantRuntimeService;

    public Flux<ServerSentEvent<String>> streamLegacyRun(String prompt, String chatId, AssistantRouteType routeType) {
        AssistantRunCreateRequest request = new AssistantRunCreateRequest();
        request.setChatId(chatId);
        request.setMessage(prompt);
        request.setClientContext(Map.of(
                "routeHint", routeType.getCode(),
                "legacyMode", true
        ));
        AssistantRunCreatedVo createdVo = assistantRuntimeService.createRun(request);
        return assistantRuntimeService.streamRun(createdVo.getRunId())
                .flatMap(this::translateEvent)
                .concatWith(Flux.defer(() -> Flux.fromIterable(buildLegacyTail(createdVo.getRunId(), chatId))))
                .onErrorResume(ex -> Flux.just(legacyEvent("error", Map.of(
                        "runId", createdVo.getRunId(),
                        "message", ex.getMessage()
                ))));
    }

    public WorkflowDetailVo getLegacyWorkflow(String runId) {
        AssistantRunDetailVo detail = assistantRuntimeService.getRunDetail(runId);
        if (detail == null || detail.getRun() == null) {
            return null;
        }
        return WorkflowDetailVo.builder()
                .run(toLegacyRun(detail.getRun(), detail.getLatestAction()))
                .steps(toLegacySteps(detail.getEvents()))
                .pendingApproval(toLegacyApproval(detail.getPendingAction()))
                .build();
    }

    public CreateOrderVo approveLegacy(String runId) {
        AssistantRunDetailVo detail = assistantRuntimeService.getRunDetail(runId);
        AiAction pendingAction = detail == null ? null : detail.getPendingAction();
        if (pendingAction == null) {
            throw new RuntimeException("没有待审批的订单");
        }
        AssistantActionResultVo resultVo = assistantRuntimeService.approveAction(runId, pendingAction.getActionId());
        CreateOrderVo result = new CreateOrderVo();
        result.setOrderNumber(resultVo.getOrderNumber());
        result.setOrderListAddress(resultVo.getOrderListAddress());
        return result;
    }

    public void rejectLegacy(String runId) {
        AssistantRunDetailVo detail = assistantRuntimeService.getRunDetail(runId);
        AiAction pendingAction = detail == null ? null : detail.getPendingAction();
        if (pendingAction == null) {
            throw new RuntimeException("没有待审批的订单");
        }
        assistantRuntimeService.rejectAction(runId, pendingAction.getActionId());
    }

    private Mono<ServerSentEvent<String>> translateEvent(ServerSentEvent<String> event) {
        String eventType = event.event();
        JSONObject payload = parsePayload(event.data());
        if (AssistantEventTypes.MESSAGE_DELTA.equals(eventType)) {
            return Mono.just(legacyEvent("message.delta", payload));
        }
        if (AssistantEventTypes.RETRIEVAL_COMPLETED.equals(eventType)) {
            Map<String, Object> legacyPayload = new LinkedHashMap<>();
            legacyPayload.put("runId", payload.getString("runId"));
            legacyPayload.put("traceId", payload.getString("retrievalId"));
            legacyPayload.put("rewrittenQuery", payload.getString("rewrittenQuery"));
            legacyPayload.put("sources", payload.get("sources"));
            return Mono.just(legacyEvent("retrieval.sources", legacyPayload));
        }
        if (AssistantEventTypes.ACTION_REQUIRED.equals(eventType)) {
            return Mono.just(legacyEvent("approval.required", payload));
        }
        if (AssistantEventTypes.RUN_FAILED.equals(eventType)) {
            return Mono.just(legacyEvent("error", Map.of(
                    "runId", payload.getString("runId"),
                    "message", payload.getString("message")
            )));
        }
        return Mono.empty();
    }

    private List<ServerSentEvent<String>> buildLegacyTail(String runId, String chatId) {
        WorkflowDetailVo detail = getLegacyWorkflow(runId);
        List<ServerSentEvent<String>> events = new ArrayList<>();
        if (detail != null) {
            events.add(legacyEvent("workflow.step", detail.getSteps()));
            if (detail.getPendingApproval() != null
                    && AssistantActionStatus.WAITING.name().equals(detail.getPendingApproval().getApprovalStatus())) {
                events.add(legacyEvent("approval.required", detail.getPendingApproval()));
            }
        }
        events.add(legacyEvent("message.done", Map.of(
                "runId", runId,
                "chatId", chatId
        )));
        return events;
    }

    private AiWorkflowRun toLegacyRun(AiRun run, AiAction latestAction) {
        AiWorkflowRun legacy = new AiWorkflowRun();
        legacy.setRunId(run.getRunId());
        legacy.setUserId(run.getUserId());
        legacy.setChatId(run.getConversationId());
        AssistantRouteType routeType = AssistantRouteType.fromCode(run.getRouteType());
        legacy.setType(routeType == null ? ChatType.CHAT.getCode() : routeType.getLegacyChatType().getCode());
        legacy.setRequestType(routeType == null ? "兼容助手" : routeType.getLegacyChatType().getMsg());
        legacy.setWorkflowStatus(legacyWorkflowStatus(run, latestAction));
        legacy.setCurrentStep(run.getCurrentStage());
        legacy.setLatestApprovalId(latestAction == null ? null : latestAction.getActionId());
        legacy.setRequestSummary(run.getUserMessage());
        legacy.setResponseSummary(run.getResponseSummary());
        legacy.setContextJson(run.getClientContextJson());
        legacy.setErrorMessage(run.getErrorMessage());
        legacy.setCompletedAt(run.getCompletedAt());
        return legacy;
    }

    private List<AiWorkflowStep> toLegacySteps(List<AiRunEvent> events) {
        List<AiWorkflowStep> steps = new ArrayList<>();
        int order = 1;
        for (AiRunEvent event : events) {
            LegacyStepSpec stepSpec = toLegacyStep(event);
            if (stepSpec == null) {
                continue;
            }
            AiWorkflowStep step = new AiWorkflowStep();
            step.setRunId(event.getRunId());
            step.setStepOrder(order++);
            step.setStepKey(stepSpec.stepKey());
            step.setStepStatus(stepSpec.stepStatus());
            step.setInputJson(event.getPayloadJson());
            step.setOutputJson(event.getPayloadJson());
            step.setStartedAt(event.getCreateTime());
            step.setFinishedAt(event.getCreateTime());
            steps.add(step);
        }
        return steps;
    }

    private LegacyStepSpec toLegacyStep(AiRunEvent event) {
        String type = event.getEventType();
        JSONObject payload = parsePayload(event.getPayloadJson());
        if (AssistantEventTypes.RUN_STARTED.equals(type)) {
            return new LegacyStepSpec("START", "COMPLETED");
        }
        if (AssistantEventTypes.ROUTE_SELECTED.equals(type)) {
            return new LegacyStepSpec("ROUTE", "COMPLETED");
        }
        if (AssistantEventTypes.CLARIFICATION_REQUIRED.equals(type)) {
            return new LegacyStepSpec("CLARIFY", "COMPLETED");
        }
        if (AssistantEventTypes.RETRIEVAL_STARTED.equals(type)) {
            return new LegacyStepSpec("RETRIEVE", "RUNNING");
        }
        if (AssistantEventTypes.RETRIEVAL_COMPLETED.equals(type)) {
            return new LegacyStepSpec("RERANK", "COMPLETED");
        }
        if (AssistantEventTypes.TOOL_STARTED.equals(type)) {
            return new LegacyStepSpec(payload.getString("toolName"), "RUNNING");
        }
        if (AssistantEventTypes.TOOL_COMPLETED.equals(type)) {
            return new LegacyStepSpec(payload.getString("toolName"), payload.getString("status"));
        }
        if (AssistantEventTypes.ACTION_REQUIRED.equals(type)) {
            return new LegacyStepSpec("WAIT_APPROVAL", "COMPLETED");
        }
        if (AssistantEventTypes.MESSAGE_COMPLETED.equals(type)) {
            return new LegacyStepSpec("ANSWER", "COMPLETED");
        }
        if (AssistantEventTypes.RUN_COMPLETED.equals(type)) {
            return new LegacyStepSpec("COMPLETE", "COMPLETED");
        }
        if (AssistantEventTypes.RUN_FAILED.equals(type)) {
            return new LegacyStepSpec("FAILED", "FAILED");
        }
        return null;
    }

    private org.javaup.ai.entity.AiApproval toLegacyApproval(AiAction action) {
        if (action == null) {
            return null;
        }
        org.javaup.ai.entity.AiApproval approval = new org.javaup.ai.entity.AiApproval();
        approval.setApprovalId(action.getActionId());
        approval.setRunId(action.getRunId());
        approval.setUserId(action.getUserId());
        approval.setChatId(action.getConversationId());
        approval.setApprovalType(action.getActionType());
        approval.setApprovalStatus(action.getActionStatus());
        approval.setPreviewJson(action.getPreviewJson());
        approval.setResultJson(action.getResultJson());
        approval.setExpiresAt(action.getExpiresAt());
        approval.setApprovedAt(action.getApprovedAt());
        approval.setRejectedAt(action.getRejectedAt());
        return approval;
    }

    public String toLegacyWorkflowStatus(String status) {
        if (status == null || status.isBlank()) {
            return RUNNING;
        }
        return switch (status) {
            case "CREATED", "RUNNING" -> RUNNING;
            case "WAITING_ACTION" -> WAITING_APPROVAL;
            case "FAILED" -> FAILED;
            case "REJECTED" -> REJECTED;
            case "COMPLETED" -> COMPLETED;
            default -> status;
        };
    }

    private String legacyWorkflowStatus(AiRun run, AiAction latestAction) {
        if (latestAction != null) {
            if (AssistantActionStatus.WAITING.name().equals(latestAction.getActionStatus())) {
                return WAITING_APPROVAL;
            }
            if (AssistantActionStatus.REJECTED.name().equals(latestAction.getActionStatus())) {
                return REJECTED;
            }
        }
        return toLegacyWorkflowStatus(run.getRunStatus());
    }

    private ServerSentEvent<String> legacyEvent(String eventType, Object payload) {
        return ServerSentEvent.<String>builder()
                .event(eventType)
                .data(JSON.toJSONString(payload))
                .build();
    }

    private JSONObject parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return new JSONObject();
        }
        return JSON.parseObject(payload);
    }

    private record LegacyStepSpec(String stepKey, String stepStatus) {
    }
}
