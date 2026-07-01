package org.javaup.ai.assistant;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.context.AiRequestContext;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.assistant.executor.AssistantExecutionContext;
import org.javaup.ai.assistant.executor.AssistantExecutor;
import org.javaup.ai.assistant.executor.AssistantExecutorRegistry;
import org.javaup.ai.assistant.executor.AssistantMessageEmitter;
import org.javaup.ai.assistant.runtime.AssistantRuntimeLeaseService;
import org.javaup.ai.assistant.runtime.AssistantStageTraceService;
import org.javaup.ai.assistant.runtime.CheckpointManager;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.AiRunEvent;
import org.javaup.ai.guardrails.GuardrailAuditService;
import org.javaup.ai.guardrails.GuardrailResult;
import org.javaup.ai.guardrails.InputGuardrailService;
import org.javaup.ai.mapper.AiRetrievalTraceMapper;
import org.javaup.ai.metrics.BusinessMetrics;
import org.javaup.ai.security.AiPermissionService;
import org.javaup.ai.tracing.ConversationTraceRecorder;
import org.javaup.ai.tracing.model.ConversationTraceStageCode;
import org.javaup.ai.vo.AiUserCapabilitiesVo;
import org.javaup.ai.vo.AssistantActionResultVo;
import org.javaup.ai.vo.AssistantRunCreatedVo;
import org.javaup.ai.vo.AssistantRunDetailVo;
import org.javaup.ai.vo.AssistantRunReplayVo;
import org.javaup.ai.vo.ChatHistoryMessageVO;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
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
    private final AssistantRunEventStreamService eventStreamService;
    private final AssistantMessageEmitter messageEmitter;
    private final InputGuardrailService inputGuardrailService;
    private final GuardrailAuditService guardrailAuditService;
    private final AssistantRuntimeLeaseService runtimeLeaseService;
    private final AssistantStageTraceService stageTraceService;
    private final AiRetrievalTraceMapper retrievalTraceMapper;
    private final ThreadPoolExecutor assistantRunExecutor;
    private final BusinessMetrics businessMetrics;
    private final org.javaup.ai.config.ThreadPoolProperties threadPoolProperties;
    private final org.javaup.ai.assistant.mq.AssistantRunRequestPublisher overflowPublisher;
    private final CheckpointManager checkpointManager;

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
        eventStreamService.ensureRunStream(runId);
        if (AssistantRunStatus.CREATED.name().equals(run.getRunStatus())) {
            if (runService.claimRunForProcessing(runId)) {
                launchProcess(runId, snapshotContext());
            }
        }
        List<AiRunEvent> events = runService.listEvents(runId);
        Flux<ServerSentEvent<String>> replay = Flux.fromIterable(events).map(this::toEvent);
        if (isTerminalRunStatus(run) && hasTerminalEvent(events)) {
            return replay;
        }
        int lastOrder = events.isEmpty() ? 0 : events.get(events.size() - 1).getEventOrder();
        Flux<ServerSentEvent<String>> live = eventStreamService.stream(runId)
                .filter(event -> event.getEventOrder() != null && event.getEventOrder() > lastOrder)
                .takeUntil(event -> isTerminalEvent(event.getEventType()))
                .map(this::toEvent);
        return Flux.concat(replay, live);
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
                .stageTraces(run == null ? List.of() : runService.listStageTraces(runId))
                .retrievalTraces(run == null ? List.of() : runService.listRetrievalTraces(runId))
                .memorySummary(run == null ? null : runService.getLatestMemorySummary(run.getConversationId(), run.getUserId()))
                .build();
    }

    public AssistantRunCreatedVo resumeRun(String runId) {
        AiRun run = runService.getRun(runId);
        if (run == null) {
            throw new IllegalArgumentException("Run does not exist: " + runId);
        }
        CheckpointManager.ResumeContext checkpoint = checkpointManager.tryResume(run);
        if (checkpoint == null) {
            throw new IllegalStateException("Run has no resumable checkpoint: " + runId);
        }
        int resumeCount = runService.resetForResume(runId);
        String checkpointFingerprint = checkpointFingerprint(checkpoint);
        String replayAttemptId = replayAttemptId(runId, "resume", resumeCount, checkpointFingerprint);
        runService.appendEvent(runId, AssistantEventTypes.RUN_RESUMED, Map.of(
                "runId", runId,
                "checkpointStage", checkpoint.stage(),
                "checkpointId", runId + ":" + checkpoint.stage(),
                "checkpointFingerprint", checkpointFingerprint,
                "replayAttemptId", replayAttemptId,
                "idempotencyPolicy", idempotencyPolicy(),
                "resumeCount", resumeCount,
                "resumeSource", "api"
        ));
        runService.appendEvent(runId, AssistantEventTypes.RUN_RECOVERY_PLANNED,
                recoveryPlanPayload(run, checkpoint, resumeCount, "resume"));
        eventStreamService.ensureRunStream(runId);
        launchProcess(runId, snapshotContext());
        return AssistantRunCreatedVo.builder()
                .runId(run.getRunId())
                .chatId(run.getConversationId())
                .status(AssistantRunStatus.CREATED.name())
                .eventStreamPath("/assistant/runs/" + run.getRunId() + "/events")
                .build();
    }

    public AssistantRunReplayVo replayRun(String runId) {
        AiRun run = runService.getRun(runId);
        if (run == null) {
            throw new IllegalArgumentException("Run does not exist: " + runId);
        }
        CheckpointManager.ResumeContext checkpoint = checkpointManager.tryResume(run);
        if (checkpoint == null) {
            throw new IllegalStateException("Run has no replayable checkpoint: " + runId);
        }
        int resumeCount = runService.resetForResume(runId);
        String checkpointFingerprint = checkpointFingerprint(checkpoint);
        String replayAttemptId = replayAttemptId(runId, "replay", resumeCount, checkpointFingerprint);
        Map<String, Object> plan = recoveryPlanPayload(run, checkpoint, resumeCount, "replay");
        Map<String, Object> replayPayload = new java.util.LinkedHashMap<>();
        replayPayload.put("runId", runId);
        replayPayload.put("checkpointStage", checkpoint.stage());
        replayPayload.put("checkpointId", runId + ":" + checkpoint.stage());
        replayPayload.put("checkpointFingerprint", checkpointFingerprint);
        replayPayload.put("replayAttemptId", replayAttemptId);
        replayPayload.put("idempotencyPolicy", idempotencyPolicy());
        replayPayload.put("resumeCount", resumeCount);
        replayPayload.put("replaySource", "api");
        replayPayload.put("skipRouting", checkpoint.canSkipRouting());
        replayPayload.put("skipRetrieval", checkpoint.canSkipRetrieval());
        replayPayload.put("riskHint", plan.get("riskHint"));
        runService.appendEvent(runId, AssistantEventTypes.RUN_REPLAY_REQUESTED, replayPayload);
        runService.appendEvent(runId, AssistantEventTypes.RUN_RECOVERY_PLANNED, plan);
        eventStreamService.ensureRunStream(runId);
        launchProcess(runId, snapshotContext());
        return AssistantRunReplayVo.builder()
                .runId(run.getRunId())
                .chatId(run.getConversationId())
                .status(AssistantRunStatus.CREATED.name())
                .checkpointId(run.getRunId() + ":" + checkpoint.stage())
                .checkpointStage(checkpoint.stage())
                .checkpointFingerprint(checkpointFingerprint)
                .replayAttemptId(replayAttemptId)
                .idempotencyPolicy(idempotencyPolicy())
                .replayable(true)
                .replayScheduled(true)
                .skipRouting(checkpoint.canSkipRouting())
                .skipRetrieval(checkpoint.canSkipRetrieval())
                .riskHint(String.valueOf(plan.getOrDefault("riskHint", "")))
                .nextActions(recoveryNextActions(checkpoint))
                .checkpointPayload(new java.util.LinkedHashMap<>(checkpoint.payload()))
                .eventStreamPath("/assistant/runs/" + run.getRunId() + "/events")
                .build();
    }

    public AssistantActionResultVo approveAction(String runId, String actionId) {
        return purchaseActionService.approve(runId, actionId);
    }

    public AssistantActionResultVo rejectAction(String runId, String actionId) {
        return purchaseActionService.reject(runId, actionId);
    }

    private void processRun(String runId) {
        AiRun run = runService.getRunInternal(runId);
        if (run == null) {
            return;
        }
        AssistantRunCreateRequest request = new AssistantRunCreateRequest();
        request.setChatId(run.getConversationId());
        request.setMessage(run.getUserMessage());
        if (run.getClientContextJson() != null && !run.getClientContextJson().isBlank()) {
            request.setClientContext(JSON.parseObject(run.getClientContextJson()));
        }
        AiUserContext user = AiRequestContextHolder.getRequiredUser();

        String traceId = UUID.randomUUID().toString().replace("-", "");
        ConversationTraceRecorder traceRecorder = new ConversationTraceRecorder(
                stageTraceService, retrievalTraceMapper,
                run.getConversationId(), run.getRunId(), traceId);

        GuardrailResult inputGuardrail = inputGuardrailService.check(request.getMessage());
        if (inputGuardrail.getAction() != GuardrailResult.Action.PASS) {
            guardrailAuditService.publish(run.getRunId(), "input", inputGuardrail, request.getMessage());
            if (inputGuardrail.isBlocked()) {
                String blockedMessage = inputGuardrail.getSanitizedContent() == null
                        ? "this request triggered input safety policy and cannot be processed."
                        : inputGuardrail.getSanitizedContent();
                messageEmitter.emitMessage(run.getRunId(), run.getConversationId(), blockedMessage);
                runService.appendEvent(run.getRunId(), AssistantEventTypes.MESSAGE_COMPLETED, Map.of(
                        "runId", run.getRunId(),
                        "chatId", run.getConversationId()
                ));
                runService.markCompleted(run, "GUARDRAIL_BLOCKED", blockedMessage);
                runService.appendEvent(run.getRunId(), AssistantEventTypes.RUN_COMPLETED, Map.of(
                        "runId", run.getRunId(),
                        "status", runService.getRunInternal(run.getRunId()).getRunStatus()
                ));
                return;
            }
        }

        ConversationTraceRecorder.StageHandle planningSpan = traceRecorder.startStage(
                ConversationTraceStageCode.PLANNING,
                "AssistantPlanning",
                request.getMessage(),
                Map.of("conversationId", run.getConversationId()));
        AssistantExecutionPlan plan;
        // LangGraph durable execution: 检测是否有可恢复的 checkpoint
        CheckpointManager.ResumeContext resumeCtx = checkpointManager.tryResume(run);
        boolean isResume = resumeCtx != null;

        try {
            if (isResume && resumeCtx.canSkipRouting()) {
                // 从 checkpoint 恢复：从快照重建 plan，跳过已完成的阶段
                log.info("Resuming runId={} from checkpoint stage={}", run.getRunId(), resumeCtx.stage());
                plan = rebuildPlanFromCheckpoint(run, resumeCtx);
                runService.appendEvent(run.getRunId(), AssistantEventTypes.CHECKPOINT_REPLAYED, Map.of(
                        "runId", run.getRunId(),
                        "checkpointStage", resumeCtx.stage(),
                        "checkpointId", run.getRunId() + ":" + resumeCtx.stage(),
                        "checkpointFingerprint", checkpointFingerprint(resumeCtx),
                        "replayAttemptId", replayAttemptId(run.getRunId(), "process", run.getResumed() == null ? 0 : run.getResumed(), checkpointFingerprint(resumeCtx)),
                        "idempotencyPolicy", idempotencyPolicy(),
                        "resumeCount", run.getResumed() == null ? 0 : run.getResumed(),
                        "replayed", true
                ));
                traceRecorder.completeStage(planningSpan, "resumed_from:" + resumeCtx.stage(), Map.of(
                        "resumed", true,
                        "checkpointStage", resumeCtx.stage()
                ));
            } else {
                plan = executionPlanner.plan(run, user, request);
                traceRecorder.completeStage(planningSpan, plan.getReason(), Map.of(
                    "routeType", plan.getRouteDecision() == null || plan.getRouteDecision().getRouteType() == null
                            ? "" : plan.getRouteDecision().getRouteType().getCode(),
                    "executionMode", plan.getExecutionMode() == null ? "" : plan.getExecutionMode().name()
            ));
            }
        } catch (Exception ex) {
            traceRecorder.failStage(planningSpan, "planning failed", ex.getMessage(), Map.of());
            throw ex;
        }

        AssistantRouteDecision decision = plan.getRouteDecision();
        AiRequestContextHolder.enrich(run.getConversationId(), run.getRunId(),
                decision.getRouteType().getLegacyChatType().getCode(), decision.getRouteType().getCode());
        runService.startRun(run, decision.getRouteType(),
                plan.getExecutionMode() == AssistantExecutionMode.CLARIFICATION ? "CLARIFYING" : "ROUTED");
        runService.appendEvent(run.getRunId(), AssistantEventTypes.RUN_STARTED, Map.of(
                "runId", run.getRunId(),
                "chatId", run.getConversationId()
        ));
        runService.appendEvent(run.getRunId(), AssistantEventTypes.ROUTE_SELECTED, routeSelectedPayload(run, plan));

        // Checkpoint: 路由决策已完成，保存可恢复状态（LangGraph durable execution）
        checkpointManager.saveCheckpoint(run.getRunId(), "ROUTED", Map.of(
                "routeType", decision.getRouteType().getCode(),
                "executionMode", plan.getExecutionMode().name(),
                "skillId", plan.getSkillDecision() != null ? plan.getSkillDecision().getSkillId() : "",
                "originalMessage", plan.getOriginalMessage()
        ));

        try {
            AssistantExecutor executor = executorRegistry.getRequired(plan.getExecutionMode());
            executor.execute(AssistantExecutionContext.builder()
                    .run(run)
                    .request(request)
                    .plan(plan)
                    .user(user)
                    .traceRecorder(traceRecorder)
                    .build());
            // LangGraph durable execution: 成功完成后清空 checkpoint
            checkpointManager.clearCheckpoint(run.getRunId());
        } catch (Exception ex) {
            // LangGraph durable execution: 失败时保存失败阶段的 checkpoint 以便恢复重试
            checkpointManager.saveCheckpoint(run.getRunId(), "EXECUTION_FAILED", Map.of(
                    "routeType", decision.getRouteType().getCode(),
                    "executionMode", plan.getExecutionMode().name(),
                    "skillId", plan.getSkillDecision() != null ? plan.getSkillDecision().getSkillId() : "",
                    "originalMessage", plan.getOriginalMessage(),
                    "error", ex.getMessage()
            ));
            ConversationTraceRecorder.StageHandle finalizeSpan = traceRecorder.startStage(
                    ConversationTraceStageCode.FINALIZE, "FAILED",
                    "finalizing failed run", Map.of());
            runService.markFailed(run, "FAILED", ex.getMessage());
            runService.appendEvent(run.getRunId(), AssistantEventTypes.RUN_FAILED, Map.of(
                    "runId", run.getRunId(),
                    "message", ex.getMessage()
            ));
            traceRecorder.completeStage(finalizeSpan, "run finalized with failure", Map.of(
                    "status", "FAILED",
                    "error", ex.getMessage()
            ));
        }
    }

    private void launchProcess(String runId, AiRequestContext context) {
        var sample = businessMetrics.startAssistantRun();
        double queueRatio = (double) assistantRunExecutor.getQueue().size()
                / Math.max(1, threadPoolProperties.getAssistant().getQueueCapacity());

        if (queueRatio > threadPoolProperties.getAssistant().getOverflowThreshold()) {
            log.warn("Thread pool queue at {}% capacity, overflowing runId={} to RabbitMQ",
                    (int)(queueRatio * 100), runId);
            AiRun run = runService.getRunInternal(runId);
            var message = org.javaup.ai.assistant.mq.AssistantRunRequestMessage.builder()
                    .runId(runId)
                    .conversationId(run != null ? run.getConversationId() : null)
                    .userId(run != null ? run.getUserId() : null)
                    .userMessage(run != null ? run.getUserMessage() : null)
                    .clientContextJson(run != null ? run.getClientContextJson() : null)
                    .build();
            overflowPublisher.publish(message);
            businessMetrics.stopAssistantRun(sample);
            return;
        }

        assistantRunExecutor.execute(() -> {
            try {
                if (context != null) {
                    AiRequestContextHolder.set(context);
                }
                AiRun run = runService.getRunInternal(runId);
                if (run != null) {
                    runService.updateStage(runId, "WAITING_LEASE");
                }
                try (AssistantRuntimeLeaseService.LeaseHandle ignored = run == null
                        ? AssistantRuntimeLeaseService.LeaseHandle.noop()
                        : runtimeLeaseService.acquireConversationLease(run.getConversationId(), runId)) {
                    processRun(runId);
                }
            } finally {
                businessMetrics.stopAssistantRun(sample);
                AiRequestContextHolder.clear();
            }
        });
    }

    public void processOverflowRun(org.javaup.ai.assistant.mq.AssistantRunRequestMessage message) {
        var sample = businessMetrics.startAssistantRun();
        try {
            AiRun run = runService.getRunInternal(message.getRunId());
            if (run == null) {
                log.warn("Overflow run not found: runId={}", message.getRunId());
                return;
            }
            AiRequestContext ctx = AiRequestContext.builder()
                    .conversationId(message.getConversationId())
                    .runId(message.getRunId())
                    .build();
            AiRequestContextHolder.set(ctx);
            runService.updateStage(message.getRunId(), "WAITING_LEASE");
            try (AssistantRuntimeLeaseService.LeaseHandle ignored =
                         runtimeLeaseService.acquireConversationLease(
                                 message.getConversationId(), message.getRunId())) {
                processRun(message.getRunId());
            }
        } finally {
            businessMetrics.stopAssistantRun(sample);
            AiRequestContextHolder.clear();
        }
    }

    private ServerSentEvent<String> toEvent(AiRunEvent event) {
        return ServerSentEvent.<String>builder()
                .id(event.getEventId())
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

    private Map<String, Object> recoveryPlanPayload(AiRun run,
                                                    CheckpointManager.ResumeContext checkpoint,
                                                    int resumeCount,
                                                    String replayMode) {
        String checkpointFingerprint = checkpointFingerprint(checkpoint);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("runId", run.getRunId());
        payload.put("checkpointStage", checkpoint.stage());
        payload.put("checkpointId", run.getRunId() + ":" + checkpoint.stage());
        payload.put("checkpointFingerprint", checkpointFingerprint);
        payload.put("replayAttemptId", replayAttemptId(run.getRunId(), replayMode, resumeCount, checkpointFingerprint));
        payload.put("replayMode", replayMode);
        payload.put("idempotencyPolicy", idempotencyPolicy());
        payload.put("resumeCount", resumeCount);
        payload.put("resumable", true);
        payload.put("skipRouting", checkpoint.canSkipRouting());
        payload.put("skipRetrieval", checkpoint.canSkipRetrieval());
        payload.put("payloadKeys", new java.util.ArrayList<>(checkpoint.payload().keySet()));
        payload.put("riskHint", checkpoint.canSkipRetrieval() ? "REPLAY_AFTER_RETRIEVAL" : "REPLAY_AFTER_ROUTING");
        payload.put("nextActions", recoveryNextActions(checkpoint));
        payload.put("summary", "resume from " + checkpoint.stage() + "; skipRouting="
                + checkpoint.canSkipRouting() + "; skipRetrieval=" + checkpoint.canSkipRetrieval());
        return payload;
    }

    private String checkpointFingerprint(CheckpointManager.ResumeContext checkpoint) {
        if (checkpoint == null) {
            return "";
        }
        String raw = checkpoint.stage() + ":" + JSON.toJSONString(checkpoint.payload());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < Math.min(8, hashed.length); index++) {
                builder.append(String.format("%02x", hashed[index]));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private String replayAttemptId(String runId, String mode, int resumeCount, String checkpointFingerprint) {
        return runId + ":" + mode + ":" + resumeCount + ":" + checkpointFingerprint;
    }

    private String idempotencyPolicy() {
        return "RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT";
    }

    private List<String> recoveryNextActions(CheckpointManager.ResumeContext checkpoint) {
        List<String> actions = new java.util.ArrayList<>();
        if (checkpoint.canSkipRouting()) {
            actions.add("Reuse checkpointed route and skill decision; do not call router again");
        }
        if (checkpoint.canSkipRetrieval()) {
            actions.add("Reuse checkpointed retrieval/tool evidence where executor supports it");
        } else {
            actions.add("Replay downstream tool or retrieval stage from checkpointed plan");
        }
        actions.add("Append checkpoint replay audit event before finalizing resumed run");
        return actions;
    }

    private boolean hasTerminalEvent(List<AiRunEvent> events) {
        return events.stream().anyMatch(event -> isTerminalEvent(event.getEventType()));
    }

    private boolean isTerminalEvent(String eventType) {
        return AssistantEventTypes.RUN_COMPLETED.equals(eventType) || AssistantEventTypes.RUN_FAILED.equals(eventType);
    }

    private boolean isTerminalRunStatus(AiRun run) {
        return run != null && (AssistantRunStatus.COMPLETED.name().equals(run.getRunStatus())
                || AssistantRunStatus.FAILED.name().equals(run.getRunStatus()));
    }

    private AiRequestContext snapshotContext() {
        AiRequestContext context = AiRequestContextHolder.get();
        if (context == null) {
            return null;
        }
        return AiRequestContext.builder()
                .user(context.getUser())
                .conversationId(context.getConversationId())
                .runId(context.getRunId())
                .chatType(context.getChatType())
                .requestType(context.getRequestType())
                .build();
    }

    /**
     * LangGraph durable execution: 从 checkpoint 快照重建 {@link AssistantExecutionPlan}。
     *
     * <p>当 Run 从断点恢复时（如 ROUTED、RETRIEVAL_COMPLETED 等阶段），
     * 不再重新调用 LLM 做路由规划，而是基于 checkpoint 中保存的决策信息直接重建 plan。
     */
    private AssistantExecutionPlan rebuildPlanFromCheckpoint(AiRun run, CheckpointManager.ResumeContext resumeCtx) {
        AssistantRouteType routeType = AssistantRouteType.fromCode(resumeCtx.getString("routeType"));
        if (routeType == null) {
            routeType = AssistantRouteType.GENERAL;
        }
        AssistantExecutionMode mode;
        try {
            mode = AssistantExecutionMode.valueOf(resumeCtx.getString("executionMode"));
        } catch (IllegalArgumentException e) {
            mode = AssistantExecutionMode.SKILL;
        }
        String skillId = resumeCtx.getString("skillId");
        String originalMessage = resumeCtx.getString("originalMessage");

        AssistantSkillDecision skillDecision = null;
        if (skillId != null && !skillId.isBlank()) {
            skillDecision = AssistantSkillDecision.builder()
                    .skillId(skillId)
                    .reason("resumed from checkpoint stage=" + resumeCtx.stage())
                    .build();
        }

        return AssistantExecutionPlan.builder()
                .runId(run.getRunId())
                .conversationId(run.getConversationId())
                .originalMessage(originalMessage != null ? originalMessage : run.getUserMessage())
                .executionMode(mode)
                .routeDecision(AssistantRouteDecision.builder()
                        .routeType(routeType)
                        .reason("resumed from checkpoint stage=" + resumeCtx.stage())
                        .fromFallback(false)
                        .build())
                .skillDecision(skillDecision)
                .reason("resumed from checkpoint")
                .build();
    }

}
