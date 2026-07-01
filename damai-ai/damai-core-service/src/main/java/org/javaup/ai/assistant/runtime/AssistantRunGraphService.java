package org.javaup.ai.assistant.runtime;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.entity.AiRunGraphNode;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.AiRunEvent;
import org.javaup.ai.vo.AssistantRunGraphVo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AssistantRunGraphService {

    private final AssistantRunService runService;
    private final CheckpointManager checkpointManager;
    private final RunGraphStateService runGraphStateService;

    public AssistantRunGraphVo buildGraph(String runId) {
        AiRun run = runService.getRun(runId);
        if (run == null) {
            return null;
        }
        List<AiRunEvent> events = runService.listEvents(runId);
        List<AssistantRunGraphVo.Node> nodes = new ArrayList<>();
        List<AiRunGraphNode> persistedNodes = runGraphStateService.listNodes(runId);
        if (persistedNodes.isEmpty()) {
            for (RunGraphDefinition.GraphNode baseNode : RunGraphDefinition.baseNodes()) {
                nodes.add(node(baseNode.id(), baseNode.type(), baseNode.label(), baseNodeStatus(baseNode.id(), events), null, null, null, null,
                        null, null, null, null, null, null, null, null, null, Map.of()));
            }
        } else {
            for (AiRunGraphNode persistedNode : persistedNodes) {
                Map<String, Object> payload = parsePayload(persistedNode.getStateJson());
                nodes.add(node(persistedNode.getNodeId(),
                        persistedNode.getNodeType(),
                        persistedNode.getNodeLabel(),
                        persistedNode.getNodeStatus(),
                        persistedNode.getLatestEventOrder(),
                        persistedNode.getLatestEventType(),
                        RunGraphDefinition.eventCategory(persistedNode.getLatestEventType()),
                        persistedNode.getCheckpointId(),
                        persistedNode.getCheckpointStage(),
                        persistedNode.getInputSummary(),
                        persistedNode.getOutputSummary(),
                        persistedNode.getRiskLevel(),
                        persistedNode.getTraceRef(),
                        persistedNode.getStartedAt(),
                        persistedNode.getCompletedAt(),
                        metricValue(payload, "totalTokens"),
                        costValue(payload),
                        payload));
            }
        }
        for (AiRunEvent event : events) {
            String graphType = RunGraphDefinition.nodeTypeForEvent(event.getEventType());
            Map<String, Object> payload = parsePayload(event.getPayloadJson());
            nodes.add(node(event.getEventId(), graphType, event.getEventType(), eventNodeStatus(event.getEventType()),
                    event.getEventOrder(), event.getEventType(), RunGraphDefinition.eventCategory(event.getEventType()),
                    null, null, null, null, null, null, null, null, metricValue(payload, "totalTokens"), costValue(payload), payload));
        }

        CheckpointManager.ResumeContext checkpoint = checkpointManager.tryResume(run);
        return AssistantRunGraphVo.builder()
                .runId(run.getRunId())
                .chatId(run.getConversationId())
                .status(run.getRunStatus())
                .currentStage(run.getCurrentStage())
                .checkpointId(checkpoint == null ? null : run.getRunId() + ":" + checkpoint.stage())
                .checkpointStage(checkpoint == null ? null : checkpoint.stage())
                .checkpointPayload(checkpoint == null ? Map.of() : checkpoint.payload())
                .summary(buildSummary(run, nodes, events, checkpoint))
                .nodes(nodes)
                .edges(RunGraphDefinition.baseEdges())
                .build();
    }

    private AssistantRunGraphVo.Node node(String id,
                                          String type,
                                          String label,
                                          String status,
                                          Integer eventOrder,
                                          String eventType,
                                          String eventCategory,
                                          String checkpointId,
                                          String checkpointStage,
                                          String inputSummary,
                                          String outputSummary,
                                          String riskLevel,
                                          String traceRef,
                                          java.util.Date startedAt,
                                          java.util.Date completedAt,
                                          Integer totalTokens,
                                          String estimatedCost,
                                          Map<String, Object> payload) {
        return AssistantRunGraphVo.Node.builder()
                .id(id)
                .type(type)
                .label(label)
                .status(status)
                .eventOrder(eventOrder)
                .eventType(eventType)
                .eventCategory(eventCategory)
                .checkpointId(checkpointId)
                .checkpointStage(checkpointStage)
                .inputSummary(inputSummary)
                .outputSummary(outputSummary)
                .riskLevel(riskLevel)
                .traceRef(traceRef)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .totalTokens(totalTokens)
                .estimatedCost(estimatedCost)
                .payload(payload == null ? Map.of() : payload)
                .build();
    }

    private String baseNodeStatus(String nodeId, List<AiRunEvent> events) {
        return switch (nodeId) {
            case RunGraphDefinition.ROUTE -> nodeStatus(events, AssistantEventTypes.ROUTE_SELECTED);
            case RunGraphDefinition.PLAN -> nodeStatus(events, AssistantEventTypes.RUN_STARTED);
            case RunGraphDefinition.SKILL -> nodeStatus(events, AssistantEventTypes.SKILL_COMPLETED);
            case RunGraphDefinition.TOOL -> nodeStatus(events, AssistantEventTypes.TOOL_COMPLETED, AssistantEventTypes.RETRIEVAL_COMPLETED);
            case RunGraphDefinition.APPROVAL -> nodeStatus(events, AssistantEventTypes.ACTION_REQUIRED);
            case RunGraphDefinition.FINAL -> terminalStatus(events);
            default -> events.isEmpty() ? "PENDING" : "RUNNING";
        };
    }

    private String nodeStatus(List<AiRunEvent> events, String... successEvents) {
        for (String eventType : successEvents) {
            if (events.stream().anyMatch(event -> eventType.equals(event.getEventType()))) {
                return "COMPLETED";
            }
        }
        if (events.stream().anyMatch(event -> AssistantEventTypes.RUN_FAILED.equals(event.getEventType()))) {
            return "FAILED";
        }
        return events.isEmpty() ? "PENDING" : "RUNNING";
    }

    private String terminalStatus(List<AiRunEvent> events) {
        if (events.stream().anyMatch(event -> AssistantEventTypes.RUN_COMPLETED.equals(event.getEventType()))) {
            return "COMPLETED";
        }
        if (events.stream().anyMatch(event -> AssistantEventTypes.RUN_FAILED.equals(event.getEventType()))) {
            return "FAILED";
        }
        return "PENDING";
    }

    private String eventNodeStatus(String eventType) {
        if (AssistantEventTypes.RUN_FAILED.equals(eventType) || AssistantEventTypes.STAGE_FAILED.equals(eventType)) {
            return "FAILED";
        }
        if (AssistantEventTypes.ACTION_REQUIRED.equals(eventType)) {
            return "INTERRUPTED";
        }
        return "COMPLETED";
    }

    private Map<String, Object> parsePayload(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            JSONObject object = JSON.parseObject(json);
            return new LinkedHashMap<>(object);
        } catch (Exception ex) {
            return Map.of("raw", json);
        }
    }

    private Integer metricValue(Map<String, Object> payload, String key) {
        Object direct = payload == null ? null : payload.get(key);
        if (direct == null && payload != null && payload.get("payload") instanceof Map<?, ?> nested) {
            direct = nested.get(key);
        }
        if (direct instanceof Number number) {
            return number.intValue();
        }
        if (direct == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(direct));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String costValue(Map<String, Object> payload) {
        Object direct = payload == null ? null : payload.get("estimatedCost");
        if (direct == null && payload != null && payload.get("payload") instanceof Map<?, ?> nested) {
            direct = nested.get("estimatedCost");
        }
        return direct == null ? null : String.valueOf(direct);
    }

    private Map<String, Object> buildSummary(AiRun run,
                                             List<AssistantRunGraphVo.Node> nodes,
                                             List<AiRunEvent> events,
                                             CheckpointManager.ResumeContext checkpoint) {
        Map<String, Integer> nodeStatusCounts = new LinkedHashMap<>();
        Map<String, Integer> eventCategoryCounts = new LinkedHashMap<>();
        List<Map<String, Object>> highRiskNodes = new ArrayList<>();
        List<Map<String, Object>> failedNodes = new ArrayList<>();
        int totalTokens = 0;
        double estimatedCost = 0D;

        for (AssistantRunGraphVo.Node node : nodes) {
            increment(nodeStatusCounts, node.getStatus() == null ? "UNKNOWN" : node.getStatus());
            if (node.getTotalTokens() != null) {
                totalTokens += node.getTotalTokens();
            }
            if (StringUtils.hasText(node.getEstimatedCost())) {
                try {
                    estimatedCost += Double.parseDouble(node.getEstimatedCost());
                } catch (NumberFormatException ignored) {
                }
            }
            if ("HIGH".equalsIgnoreCase(node.getRiskLevel()) || "MEDIUM".equalsIgnoreCase(node.getRiskLevel())) {
                highRiskNodes.add(nodeSummary(node));
            }
            if ("FAILED".equalsIgnoreCase(node.getStatus())) {
                failedNodes.add(nodeSummary(node));
            }
        }

        int resumeRequested = 0;
        int checkpointReplayed = 0;
        String latestReplayAttemptId = "";
        String latestCheckpointFingerprint = "";
        String idempotencyPolicy = "";
        List<Map<String, Object>> auditTrail = new ArrayList<>();
        for (AiRunEvent event : events) {
            String category = RunGraphDefinition.eventCategory(event.getEventType());
            increment(eventCategoryCounts, category);
            Map<String, Object> eventPayload = parsePayload(event.getPayloadJson());
            if (AssistantEventTypes.RUN_RESUMED.equals(event.getEventType())) {
                resumeRequested++;
            }
            if (AssistantEventTypes.RUN_REPLAY_REQUESTED.equals(event.getEventType())) {
                resumeRequested++;
            }
            if (AssistantEventTypes.CHECKPOINT_REPLAYED.equals(event.getEventType())) {
                checkpointReplayed++;
            }
            latestReplayAttemptId = latestText(latestReplayAttemptId, eventPayload.get("replayAttemptId"));
            latestCheckpointFingerprint = latestText(latestCheckpointFingerprint, eventPayload.get("checkpointFingerprint"));
            idempotencyPolicy = latestText(idempotencyPolicy, eventPayload.get("idempotencyPolicy"));
            if (isAuditEvent(event.getEventType())) {
                auditTrail.add(eventAudit(event, eventPayload));
            }
        }

        Map<String, Object> checkpointSummary = new LinkedHashMap<>();
        checkpointSummary.put("resumable", checkpoint != null);
        checkpointSummary.put("checkpointId", checkpoint == null ? "" : run.getRunId() + ":" + checkpoint.stage());
        checkpointSummary.put("checkpointStage", checkpoint == null ? "" : checkpoint.stage());
        checkpointSummary.put("payloadKeys", checkpoint == null ? List.of() : new ArrayList<>(checkpoint.payload().keySet()));
        checkpointSummary.put("checkpointFingerprint", latestCheckpointFingerprint);
        checkpointSummary.put("latestReplayAttemptId", latestReplayAttemptId);
        checkpointSummary.put("idempotencyPolicy", idempotencyPolicy);
        checkpointSummary.put("resumeCount", run.getResumed() == null ? 0 : run.getResumed());
        checkpointSummary.put("resumeRequestedEvents", resumeRequested);
        checkpointSummary.put("checkpointReplayEvents", checkpointReplayed);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("nodeStatusCounts", nodeStatusCounts);
        summary.put("eventCategoryCounts", eventCategoryCounts);
        summary.put("checkpoint", checkpointSummary);
        summary.put("recoveryPlan", recoveryPlan(run, checkpoint, highRiskNodes, failedNodes, resumeRequested, checkpointReplayed,
                latestReplayAttemptId, latestCheckpointFingerprint, idempotencyPolicy));
        summary.put("highRiskNodes", highRiskNodes.stream().limit(5).toList());
        summary.put("failedNodes", failedNodes.stream().limit(5).toList());
        summary.put("auditTrail", auditTrail.stream().limit(10).toList());
        summary.put("totalTokens", totalTokens == 0 ? null : totalTokens);
        summary.put("estimatedCost", estimatedCost == 0D ? "" : String.format("%.6f", estimatedCost));
        summary.put("explainability", Map.of(
                "replayable", checkpoint != null,
                "audited", !auditTrail.isEmpty(),
                "hasGuardrail", eventCategoryCounts.containsKey(RunGraphDefinition.CATEGORY_GUARDRAIL),
                "hasApprovalInterrupt", eventCategoryCounts.containsKey(RunGraphDefinition.CATEGORY_APPROVAL)));
        return summary;
    }

    private Map<String, Object> recoveryPlan(AiRun run,
                                             CheckpointManager.ResumeContext checkpoint,
                                             List<Map<String, Object>> highRiskNodes,
                                             List<Map<String, Object>> failedNodes,
                                             int resumeRequested,
                                             int checkpointReplayed,
                                             String latestReplayAttemptId,
                                             String latestCheckpointFingerprint,
                                             String idempotencyPolicy) {
        Map<String, Object> plan = new LinkedHashMap<>();
        boolean resumable = checkpoint != null;
        plan.put("resumable", resumable);
        plan.put("resumeFromStage", resumable ? checkpoint.stage() : "");
        plan.put("resumeCheckpointId", resumable ? run.getRunId() + ":" + checkpoint.stage() : "");
        plan.put("checkpointFingerprint", latestCheckpointFingerprint);
        plan.put("latestReplayAttemptId", latestReplayAttemptId);
        plan.put("idempotencyPolicy", idempotencyPolicy);
        plan.put("skipRouting", resumable && checkpoint.canSkipRouting());
        plan.put("skipRetrieval", resumable && checkpoint.canSkipRetrieval());
        plan.put("payloadKeys", resumable ? new ArrayList<>(checkpoint.payload().keySet()) : List.of());
        plan.put("requiresHumanReview", !highRiskNodes.isEmpty());
        plan.put("hasFailedNodes", !failedNodes.isEmpty());
        plan.put("auditComplete", resumeRequested == checkpointReplayed);
        plan.put("resumeRequestedEvents", resumeRequested);
        plan.put("checkpointReplayEvents", checkpointReplayed);
        plan.put("riskHint", recoveryRiskHint(resumable, highRiskNodes, failedNodes, resumeRequested, checkpointReplayed));
        plan.put("nextActions", recoveryNextActions(resumable, highRiskNodes, failedNodes, resumeRequested, checkpointReplayed));
        return plan;
    }

    private String recoveryRiskHint(boolean resumable,
                                    List<Map<String, Object>> highRiskNodes,
                                    List<Map<String, Object>> failedNodes,
                                    int resumeRequested,
                                    int checkpointReplayed) {
        if (!resumable) {
            return "NO_CHECKPOINT";
        }
        if (!failedNodes.isEmpty()) {
            return "FAILED_NODE_REVIEW_REQUIRED";
        }
        if (!highRiskNodes.isEmpty()) {
            return "HUMAN_REVIEW_REQUIRED";
        }
        if (resumeRequested != checkpointReplayed) {
            return "AUDIT_MISMATCH";
        }
        return "READY_TO_REPLAY";
    }

    private List<String> recoveryNextActions(boolean resumable,
                                             List<Map<String, Object>> highRiskNodes,
                                             List<Map<String, Object>> failedNodes,
                                             int resumeRequested,
                                             int checkpointReplayed) {
        List<String> actions = new ArrayList<>();
        if (!resumable) {
            actions.add("Start a new run; no checkpoint is available for replay");
            return actions;
        }
        if (!failedNodes.isEmpty()) {
            actions.add("Inspect failed graph nodes before replay");
        }
        if (!highRiskNodes.isEmpty()) {
            actions.add("Require human review for high-risk approval/tool nodes");
        }
        if (resumeRequested != checkpointReplayed) {
            actions.add("Verify checkpoint replay audit events before release");
        }
        actions.add("Resume from checkpoint and skip already completed safe stages");
        return actions;
    }

    private boolean isAuditEvent(String eventType) {
        return AssistantEventTypes.RUN_RESUMED.equals(eventType)
                || AssistantEventTypes.RUN_REPLAY_REQUESTED.equals(eventType)
                || AssistantEventTypes.RUN_RECOVERY_PLANNED.equals(eventType)
                || AssistantEventTypes.CHECKPOINT_REPLAYED.equals(eventType)
                || AssistantEventTypes.ACTION_REQUIRED.equals(eventType)
                || AssistantEventTypes.ACTION_PROCESSING.equals(eventType)
                || AssistantEventTypes.RUN_FAILED.equals(eventType)
                || (eventType != null && eventType.startsWith("guardrail."));
    }

    private Map<String, Object> nodeSummary(AssistantRunGraphVo.Node node) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", node.getId());
        item.put("label", node.getLabel());
        item.put("type", node.getType());
        item.put("status", node.getStatus());
        item.put("riskLevel", node.getRiskLevel());
        item.put("eventType", node.getEventType());
        item.put("checkpointId", node.getCheckpointId());
        item.put("summary", node.getOutputSummary());
        return item;
    }

    private Map<String, Object> eventAudit(AiRunEvent event, Map<String, Object> payload) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventOrder", event.getEventOrder());
        item.put("eventType", event.getEventType());
        item.put("eventCategory", RunGraphDefinition.eventCategory(event.getEventType()));
        item.put("checkpointId", payload.getOrDefault("checkpointId", ""));
        item.put("checkpointStage", payload.getOrDefault("checkpointStage", ""));
        item.put("checkpointFingerprint", payload.getOrDefault("checkpointFingerprint", ""));
        item.put("replayAttemptId", payload.getOrDefault("replayAttemptId", ""));
        item.put("idempotencyPolicy", payload.getOrDefault("idempotencyPolicy", ""));
        item.put("resumeCount", payload.getOrDefault("resumeCount", ""));
        item.put("riskHint", payload.getOrDefault("riskHint", ""));
        item.put("nextActions", payload.getOrDefault("nextActions", List.of()));
        item.put("summary", firstText(payload, "message", "reason", "summary", "status", "actionId"));
        return item;
    }

    private String latestText(String currentValue, Object candidate) {
        return candidate != null && StringUtils.hasText(String.valueOf(candidate))
                ? String.valueOf(candidate)
                : currentValue;
    }

    private String firstText(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private void increment(Map<String, Integer> counts, String key) {
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }
}
