package org.javaup.ai.assistant.runtime;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.AiRunEvent;
import org.javaup.ai.entity.AiRunGraphNode;
import org.javaup.ai.mapper.AiRunGraphNodeMapper;
import org.javaup.ai.mapper.AiRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RunGraphStateService {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String INTERRUPTED = "INTERRUPTED";
    public static final String CHECKPOINTED = "CHECKPOINTED";

    private final AiRunGraphNodeMapper graphNodeMapper;
    private final AiRunMapper runMapper;

    @Transactional(rollbackFor = Exception.class)
    public void initializeRun(AiRun run) {
        if (run == null || !StringUtils.hasText(run.getRunId())) {
            return;
        }
        for (RunGraphDefinition.GraphNode baseNode : RunGraphDefinition.baseNodes()) {
            AiRunGraphNode existing = findNode(run.getRunId(), baseNode.id());
            if (existing == null) {
                AiRunGraphNode node = newBaseNode(run, baseNode);
                graphNodeMapper.insert(node);
            }
        }
    }

    public List<AiRunGraphNode> listNodes(String runId) {
        return graphNodeMapper.selectList(Wrappers.lambdaQuery(AiRunGraphNode.class)
                .eq(AiRunGraphNode::getRunId, runId)
                .eq(AiRunGraphNode::getStatus, 1)
                .orderByAsc(AiRunGraphNode::getGraphOrder)
                .orderByAsc(AiRunGraphNode::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordEvent(AiRun run, AiRunEvent event, Map<String, Object> payload) {
        if (run == null || event == null || !StringUtils.hasText(event.getEventType())) {
            return;
        }
        initializeRun(run);
        String nodeId = RunGraphDefinition.nodeIdForEvent(event.getEventType());
        AiRunGraphNode node = findNode(run.getRunId(), nodeId);
        if (node == null) {
            node = newDynamicNode(run, nodeId, RunGraphDefinition.nodeTypeForEvent(event.getEventType()), labelFor(nodeId));
        }
        node.setLatestEventId(event.getEventId());
        node.setLatestEventType(event.getEventType());
        node.setLatestEventOrder(event.getEventOrder());
        node.setNodeStatus(statusForEvent(event.getEventType()));
        node.setStateJson(JSON.toJSONString(eventState(event, payload)));
        node.setOutputSummary(summaryForEvent(event.getEventType(), payload));
        node.setRiskLevel(riskLevelFor(event.getEventType(), payload));
        node.setTraceRef(stringValue(payload, "traceRef", run.getRunId()));
        if (RUNNING.equals(node.getNodeStatus()) && node.getStartedAt() == null) {
            node.setStartedAt(new Date());
        }
        if (COMPLETED.equals(node.getNodeStatus()) || FAILED.equals(node.getNodeStatus()) || INTERRUPTED.equals(node.getNodeStatus())) {
            if (node.getStartedAt() == null) {
                node.setStartedAt(new Date());
            }
            node.setCompletedAt(new Date());
        }
        save(node);
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordCheckpoint(String runId, String stage, Map<String, Object> payload) {
        if (!StringUtils.hasText(runId) || !StringUtils.hasText(stage)) {
            return;
        }
        AiRun run = runMapper.selectOne(Wrappers.lambdaQuery(AiRun.class)
                .eq(AiRun::getRunId, runId)
                .eq(AiRun::getStatus, 1)
                .last("limit 1"));
        if (run == null) {
            return;
        }
        initializeRun(run);
        String nodeId = RunGraphDefinition.nodeIdForCheckpointStage(stage);
        AiRunGraphNode node = findNode(runId, nodeId);
        if (node == null) {
            node = newDynamicNode(run, nodeId, nodeId, labelFor(nodeId));
        }
        node.setCheckpointId(runId + ":" + stage);
        node.setCheckpointStage(stage);
        node.setStateJson(JSON.toJSONString(checkpointState(stage, payload)));
        node.setNodeStatus(statusForCheckpoint(stage));
        node.setTraceRef(runId);
        if (node.getStartedAt() == null) {
            node.setStartedAt(new Date());
        }
        if (COMPLETED.equals(node.getNodeStatus()) || FAILED.equals(node.getNodeStatus()) || CHECKPOINTED.equals(node.getNodeStatus())) {
            node.setCompletedAt(new Date());
        }
        save(node);
    }

    @Transactional(rollbackFor = Exception.class)
    public void clearCheckpoints(String runId) {
        if (!StringUtils.hasText(runId)) {
            return;
        }
        graphNodeMapper.update(null, Wrappers.lambdaUpdate(AiRunGraphNode.class)
                .eq(AiRunGraphNode::getRunId, runId)
                .eq(AiRunGraphNode::getStatus, 1)
                .set(AiRunGraphNode::getCheckpointId, null)
                .set(AiRunGraphNode::getCheckpointStage, null));
    }

    private AiRunGraphNode findNode(String runId, String nodeId) {
        return graphNodeMapper.selectOne(Wrappers.lambdaQuery(AiRunGraphNode.class)
                .eq(AiRunGraphNode::getRunId, runId)
                .eq(AiRunGraphNode::getNodeId, nodeId)
                .eq(AiRunGraphNode::getStatus, 1)
                .last("limit 1"));
    }

    private AiRunGraphNode newBaseNode(AiRun run, RunGraphDefinition.GraphNode baseNode) {
        AiRunGraphNode node = new AiRunGraphNode();
        node.setNodeId(baseNode.id());
        node.setRunId(run.getRunId());
        node.setConversationId(run.getConversationId());
        node.setUserId(run.getUserId());
        node.setNodeType(baseNode.type());
        node.setNodeLabel(baseNode.label());
        node.setGraphOrder(RunGraphDefinition.graphOrder(baseNode.id()));
        node.setNodeStatus(PENDING);
        node.setTraceRef(run.getRunId());
        node.setStatus(1);
        return node;
    }

    private AiRunGraphNode newDynamicNode(AiRun run, String nodeId, String nodeType, String label) {
        AiRunGraphNode node = new AiRunGraphNode();
        node.setNodeId(nodeId);
        node.setRunId(run.getRunId());
        node.setConversationId(run.getConversationId());
        node.setUserId(run.getUserId());
        node.setNodeType(nodeType);
        node.setNodeLabel(label);
        node.setGraphOrder(RunGraphDefinition.graphOrder(nodeId));
        node.setNodeStatus(PENDING);
        node.setTraceRef(run.getRunId());
        node.setStatus(1);
        return node;
    }

    private void save(AiRunGraphNode node) {
        if (node.getId() == null) {
            graphNodeMapper.insert(node);
        } else {
            graphNodeMapper.updateById(node);
        }
    }

    private Map<String, Object> eventState(AiRunEvent event, Map<String, Object> payload) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("eventId", event.getEventId());
        state.put("eventType", event.getEventType());
        state.put("eventOrder", event.getEventOrder());
        state.put("payload", payload == null ? Map.of() : payload);
        return state;
    }

    private Map<String, Object> checkpointState(String stage, Map<String, Object> payload) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("checkpointStage", stage);
        state.put("payload", payload == null ? Map.of() : payload);
        return state;
    }

    private String statusForEvent(String eventType) {
        if (AssistantEventTypes.RUN_FAILED.equals(eventType) || AssistantEventTypes.STAGE_FAILED.equals(eventType)) {
            return FAILED;
        }
        if (AssistantEventTypes.ACTION_REQUIRED.equals(eventType)
                || AssistantEventTypes.CLARIFICATION_REQUIRED.equals(eventType)) {
            return INTERRUPTED;
        }
        if (eventType.endsWith(".started")
                || AssistantEventTypes.ACTION_PROCESSING.equals(eventType)
                || AssistantEventTypes.MESSAGE_DELTA.equals(eventType)
                || AssistantEventTypes.AGENT_STEP.equals(eventType)) {
            return RUNNING;
        }
        if (eventType.endsWith(".completed")
                || AssistantEventTypes.RUN_RECOVERY_PLANNED.equals(eventType)
                || AssistantEventTypes.RUN_REPLAY_REQUESTED.equals(eventType)
                || AssistantEventTypes.MESSAGE_REPLACED.equals(eventType)
                || AssistantEventTypes.ROUTE_SELECTED.equals(eventType)
                || AssistantEventTypes.KNOWLEDGE_ROUTE_SHADOWED.equals(eventType)
                || AssistantEventTypes.FEEDBACK_RECEIVED.equals(eventType)) {
            return COMPLETED;
        }
        if (eventType.startsWith("guardrail.")) {
            return CHECKPOINTED;
        }
        return RUNNING;
    }

    private String statusForCheckpoint(String stage) {
        if ("EXECUTION_FAILED".equals(stage)) {
            return FAILED;
        }
        if ("ACTION_PREVIEWED".equals(stage)) {
            return INTERRUPTED;
        }
        if ("ROUTED".equals(stage)
                || "RETRIEVAL_COMPLETED".equals(stage)
                || "SQL_GENERATED".equals(stage)) {
            return COMPLETED;
        }
        return CHECKPOINTED;
    }

    private String summaryForEvent(String eventType, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return eventType;
        }
        for (String key : List.of("message", "reason", "summary", "status", "checkpointStage", "toolName", "skillId", "actionId")) {
            Object value = payload.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return trim(String.valueOf(value), 240);
            }
        }
        if (payload.containsKey("chunkCount")) {
            return "retrieved chunks=" + payload.get("chunkCount");
        }
        return eventType;
    }

    private String riskLevelFor(String eventType, Map<String, Object> payload) {
        String explicit = stringValue(payload, "riskLevel", null);
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        if (AssistantEventTypes.ACTION_REQUIRED.equals(eventType)) {
            return "HIGH";
        }
        if (eventType != null && eventType.startsWith("guardrail.")) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String stringValue(Map<String, Object> payload, String key, String defaultValue) {
        if (payload == null) {
            return defaultValue;
        }
        Object value = payload.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private String labelFor(String nodeId) {
        return switch (nodeId) {
            case RunGraphDefinition.ROUTE -> "Route";
            case RunGraphDefinition.PLAN -> "Plan";
            case RunGraphDefinition.SKILL -> "Skill/Agent";
            case RunGraphDefinition.TOOL -> "Tools/Retrieval";
            case RunGraphDefinition.APPROVAL -> "Approval";
            case RunGraphDefinition.FINAL -> "Finalize";
            case RunGraphDefinition.GUARDRAIL -> "Guardrail";
            case RunGraphDefinition.STAGE -> "Stage";
            default -> nodeId;
        };
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
