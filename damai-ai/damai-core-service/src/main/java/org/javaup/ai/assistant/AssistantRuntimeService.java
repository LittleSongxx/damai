package org.javaup.ai.assistant;

import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.assistant.executor.AssistantExecutionContext;
import org.javaup.ai.assistant.executor.AssistantExecutor;
import org.javaup.ai.assistant.executor.AssistantExecutorRegistry;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.AiRunEvent;
import org.javaup.ai.security.AiPermissionService;
import org.javaup.ai.vo.AiUserCapabilitiesVo;
import org.javaup.ai.vo.AssistantActionResultVo;
import org.javaup.ai.vo.AssistantRunCreatedVo;
import org.javaup.ai.vo.AssistantRunDetailVo;
import org.javaup.ai.vo.ChatHistoryMessageVO;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AssistantRuntimeService {

    private final AssistantConversationService conversationService;
    private final AssistantRunService runService;
    private final AssistantExecutionPlanner executionPlanner;
    private final AssistantExecutorRegistry executorRegistry;
    private final org.javaup.ai.assistant.skill.business.PurchaseActionService purchaseActionService;
    private final AiPermissionService aiPermissionService;
    private final AssistantSkillManagementService skillManagementService;

    public AssistantRunCreatedVo createRun(AssistantRunCreateRequest request) {
        return runService.createRun(request);
    }

    public AiUserCapabilitiesVo getCurrentUserCapabilities(AiUserContext user) {
        boolean admin = aiPermissionService.isAdmin(user);
        return AiUserCapabilitiesVo.builder()
                .userId(user.getUserId())
                .admin(admin)
                .allowedRoutes(admin ? List.of("business", "knowledge", "general", "ops") : List.of("business", "knowledge", "general"))
                .skills(skillManagementService.listCapabilities(user))
                .build();
    }

    public Flux<ServerSentEvent<String>> streamRun(String runId) {
        AiRun run = runService.getRun(runId);
        if (run == null) {
            return Flux.just(toEvent(AssistantEventTypes.RUN_FAILED, Map.of("runId", runId, "message", "run not found")));
        }
        if (AssistantRunStatus.CREATED.name().equals(run.getRunStatus())) {
            return Flux.create(sink -> {
                try {
                    processRun(run);
                    List<AiRunEvent> events = runService.listEvents(runId);
                    for (AiRunEvent event : events) {
                        sink.next(toEvent(event));
                    }
                    sink.complete();
                } catch (Exception ex) {
                    sink.next(toEvent(AssistantEventTypes.RUN_FAILED, Map.of("runId", runId, "message", ex.getMessage())));
                    sink.complete();
                }
            });
        }
        List<AiRunEvent> events = runService.listEvents(runId);
        return Flux.fromIterable(events).map(this::toEvent);
    }

    public List<org.javaup.ai.vo.AssistantConversationVo> listConversations() {
        return conversationService.listConversations();
    }

    public List<ChatHistoryMessageVO> listMessages(String chatId) {
        return conversationService.listMessages(chatId);
    }

    public AssistantRunDetailVo getRunDetail(String runId) {
        AiRun run = runService.getRun(runId);
        return AssistantRunDetailVo.builder()
                .run(run)
                .events(run == null ? List.of() : runService.listEvents(runId))
                .pendingAction(run == null ? null : runService.getPendingAction(runId))
                .latestAction(run == null ? null : runService.getLatestAction(runId))
                .retrieval(run == null ? null : runService.getLatestRetrieval(runId))
                .build();
    }

    public AssistantActionResultVo approveAction(String runId, String actionId) {
        return purchaseActionService.approve(runId, actionId);
    }

    public AssistantActionResultVo rejectAction(String runId, String actionId) {
        return purchaseActionService.reject(runId, actionId);
    }

    private void processRun(AiRun run) {
        AssistantRunCreateRequest request = new AssistantRunCreateRequest();
        request.setChatId(run.getConversationId());
        request.setMessage(run.getUserMessage());
        if (run.getClientContextJson() != null && !run.getClientContextJson().isBlank()) {
            request.setClientContext(JSON.parseObject(run.getClientContextJson()));
        }
        AiUserContext user = AiRequestContextHolder.getRequiredUser();
        AssistantExecutionPlan plan = executionPlanner.plan(run, user, request);
        AssistantRouteDecision decision = plan.getRouteDecision();
        AiRequestContextHolder.enrich(run.getConversationId(), run.getRunId(), decision.getRouteType().getLegacyChatType().getCode(), decision.getRouteType().getCode());
        runService.startRun(run, decision.getRouteType(), plan.getExecutionMode() == AssistantExecutionMode.CLARIFICATION ? "CLARIFYING" : "ROUTED");
        runService.appendEvent(run.getRunId(), AssistantEventTypes.RUN_STARTED, Map.of(
                "runId", run.getRunId(),
                "chatId", run.getConversationId()
        ));
        runService.appendEvent(run.getRunId(), AssistantEventTypes.ROUTE_SELECTED, routeSelectedPayload(run, plan));

        try {
            AssistantExecutor executor = executorRegistry.getRequired(plan.getExecutionMode());
            executor.execute(AssistantExecutionContext.builder()
                    .run(run)
                    .request(request)
                    .plan(plan)
                    .user(user)
                    .build());
        } catch (Exception ex) {
            runService.markFailed(run, "FAILED", ex.getMessage());
            runService.appendEvent(run.getRunId(), AssistantEventTypes.RUN_FAILED, Map.of(
                    "runId", run.getRunId(),
                    "message", ex.getMessage()
            ));
        }
    }

    private ServerSentEvent<String> toEvent(AiRunEvent event) {
        return ServerSentEvent.<String>builder()
                .event(event.getEventType())
                .data(event.getPayloadJson())
                .build();
    }

    private ServerSentEvent<String> toEvent(String eventType, Object payload) {
        return ServerSentEvent.<String>builder()
                .event(eventType)
                .data(JSON.toJSONString(payload))
                .build();
    }

    private Map<String, Object> routeSelectedPayload(AiRun run, AssistantExecutionPlan plan) {
        AssistantRouteDecision decision = plan.getRouteDecision();
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("runId", run.getRunId());
        payload.put("routeType", decision.getRouteType().getCode());
        payload.put("reason", decision.getReason());
        payload.put("fallback", decision.getFromFallback());
        payload.put("executionMode", plan.getExecutionMode().name());
        AssistantSkillDecision skillDecision = plan.getSkillDecision();
        if (skillDecision != null) {
            payload.put("skillId", skillDecision.getSkillId());
            payload.put("skillReason", skillDecision.getReason());
            payload.put("skillConfidence", skillDecision.getConfidence());
            if (skillDecision.getDescriptor() != null) {
                payload.put("skillName", skillDecision.getDescriptor().getName());
            }
        }
        return payload;
    }

}
