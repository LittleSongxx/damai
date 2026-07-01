package org.javaup.ai.assistant.runtime;

import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.context.AiRequestContext;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.AiRunEvent;
import org.javaup.ai.entity.AiRunGraphNode;
import org.javaup.ai.vo.AssistantRunGraphVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantRunGraphServiceTest {

    private final AssistantRunService runService = mock(AssistantRunService.class);
    private final CheckpointManager checkpointManager = mock(CheckpointManager.class);
    private final RunGraphStateService runGraphStateService = mock(RunGraphStateService.class);
    private final AssistantRunGraphService graphService = new AssistantRunGraphService(runService, checkpointManager, runGraphStateService);

    @AfterEach
    void tearDown() {
        AiRequestContextHolder.clear();
    }

    @Test
    void shouldBuildGraphFromRunEventsAndCheckpoint() {
        AiRequestContextHolder.set(AiRequestContext.builder()
                .user(AiUserContext.builder().userId(1L).build())
                .build());
        AiRun run = new AiRun();
        run.setRunId("run_1");
        run.setConversationId("chat_1");
        run.setRunStatus("FAILED");
        run.setCurrentStage("EXECUTION_FAILED");
        run.setResumed(2);
        run.setResumableStateJson("{\"stage\":\"ROUTED\",\"payload\":{\"routeType\":\"knowledge\"}}");
        when(runService.getRun("run_1")).thenReturn(run);
        when(runService.listEvents("run_1")).thenReturn(List.of(
                event(1, AssistantEventTypes.RUN_STARTED, "{\"runId\":\"run_1\"}"),
                event(2, AssistantEventTypes.ROUTE_SELECTED, "{\"routeType\":\"knowledge\"}"),
                event(3, AssistantEventTypes.RETRIEVAL_COMPLETED, "{\"chunkCount\":3}"),
                event(4, AssistantEventTypes.RUN_RESUMED, "{\"checkpointStage\":\"ROUTED\",\"checkpointId\":\"run_1:ROUTED\",\"checkpointFingerprint\":\"fp-run-1-routed\",\"replayAttemptId\":\"run_1:resume:2:fp-run-1-routed\",\"idempotencyPolicy\":\"RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT\",\"resumeCount\":2}"),
                event(5, AssistantEventTypes.RUN_REPLAY_REQUESTED, "{\"checkpointStage\":\"ROUTED\",\"checkpointId\":\"run_1:ROUTED\",\"checkpointFingerprint\":\"fp-run-1-routed\",\"replayAttemptId\":\"run_1:replay:3:fp-run-1-routed\",\"idempotencyPolicy\":\"RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT\",\"resumeCount\":3,\"skipRouting\":true,\"skipRetrieval\":false,\"riskHint\":\"REPLAY_AFTER_ROUTING\"}"),
                event(6, AssistantEventTypes.RUN_RECOVERY_PLANNED, "{\"checkpointStage\":\"ROUTED\",\"checkpointId\":\"run_1:ROUTED\",\"checkpointFingerprint\":\"fp-run-1-routed\",\"replayAttemptId\":\"run_1:recovery:2:fp-run-1-routed\",\"idempotencyPolicy\":\"RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT\",\"resumeCount\":2,\"skipRouting\":true,\"skipRetrieval\":false,\"riskHint\":\"REPLAY_AFTER_ROUTING\",\"nextActions\":[\"Reuse checkpointed route and skill decision; do not call router again\"]}"),
                event(7, AssistantEventTypes.CHECKPOINT_REPLAYED, "{\"checkpointStage\":\"ROUTED\",\"checkpointId\":\"run_1:ROUTED\",\"checkpointFingerprint\":\"fp-run-1-routed\",\"replayAttemptId\":\"run_1:process:2:fp-run-1-routed\",\"idempotencyPolicy\":\"RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT\",\"resumeCount\":2}"),
                event(8, AssistantEventTypes.ACTION_REQUIRED, "{\"actionId\":\"action_1\",\"riskLevel\":\"HIGH\"}"),
                event(9, AssistantEventTypes.RUN_FAILED, "{\"message\":\"boom\"}")
        ));
        when(runGraphStateService.listNodes("run_1")).thenReturn(List.of(
                graphNode("route", "route", "Route", "COMPLETED", 2, AssistantEventTypes.ROUTE_SELECTED),
                graphNode("tool", "tool", "Tools/Retrieval", "COMPLETED", 3, AssistantEventTypes.RETRIEVAL_COMPLETED),
                graphNode("run", "run", "Run", "COMPLETED", 6, AssistantEventTypes.RUN_RECOVERY_PLANNED, "LOW"),
                graphNode("approval", "approval", "Approval", "INTERRUPTED", 8, AssistantEventTypes.ACTION_REQUIRED, "HIGH"),
                graphNode("final", "final", "Finalize", "FAILED", 9, AssistantEventTypes.RUN_FAILED, "LOW")
        ));
        when(checkpointManager.tryResume(run)).thenReturn(new CheckpointManager.ResumeContext(
                "ROUTED", com.alibaba.fastjson2.JSON.parseObject("{\"routeType\":\"knowledge\"}")));

        AssistantRunGraphVo graph = graphService.buildGraph("run_1");

        assertNotNull(graph);
        assertEquals("run_1", graph.getRunId());
        assertEquals("ROUTED", graph.getCheckpointStage());
        assertTrue(graph.getNodes().stream().anyMatch(node -> "tool".equals(node.getType())));
        assertTrue(graph.getNodes().stream().anyMatch(node -> "retrieval".equals(node.getEventCategory())));
        assertTrue(graph.getEdges().stream().anyMatch(edge -> "approval".equals(edge.getTarget())));
        Map<String, Object> summary = graph.getSummary();
        Map<?, ?> checkpointSummary = (Map<?, ?>) summary.get("checkpoint");
        assertEquals(true, checkpointSummary.get("resumable"));
        assertEquals(2, checkpointSummary.get("resumeCount"));
        assertEquals(2, checkpointSummary.get("resumeRequestedEvents"));
        assertEquals(1, checkpointSummary.get("checkpointReplayEvents"));
        assertEquals("fp-run-1-routed", checkpointSummary.get("checkpointFingerprint"));
        assertEquals("run_1:process:2:fp-run-1-routed", checkpointSummary.get("latestReplayAttemptId"));
        assertEquals("RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT", checkpointSummary.get("idempotencyPolicy"));
        assertTrue(((Number) ((Map<?, ?>) summary.get("eventCategoryCounts")).get("run")).intValue() >= 2);
        Map<?, ?> recoveryPlan = (Map<?, ?>) summary.get("recoveryPlan");
        assertEquals(true, recoveryPlan.get("resumable"));
        assertEquals("ROUTED", recoveryPlan.get("resumeFromStage"));
        assertEquals("run_1:ROUTED", recoveryPlan.get("resumeCheckpointId"));
        assertEquals("fp-run-1-routed", recoveryPlan.get("checkpointFingerprint"));
        assertEquals("run_1:process:2:fp-run-1-routed", recoveryPlan.get("latestReplayAttemptId"));
        assertEquals("RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT", recoveryPlan.get("idempotencyPolicy"));
        assertEquals(true, recoveryPlan.get("skipRouting"));
        assertEquals(false, recoveryPlan.get("skipRetrieval"));
        assertEquals(true, recoveryPlan.get("requiresHumanReview"));
        assertEquals(true, recoveryPlan.get("hasFailedNodes"));
        assertEquals(false, recoveryPlan.get("auditComplete"));
        assertEquals("FAILED_NODE_REVIEW_REQUIRED", recoveryPlan.get("riskHint"));
        assertTrue(String.valueOf(recoveryPlan.get("nextActions")).contains("Inspect failed graph nodes"));
        assertTrue(String.valueOf(recoveryPlan.get("nextActions")).contains("Require human review"));
        assertTrue(String.valueOf(recoveryPlan.get("nextActions")).contains("Verify checkpoint replay audit events"));
        assertTrue(String.valueOf(summary.get("auditTrail")).contains(AssistantEventTypes.CHECKPOINT_REPLAYED));
        assertTrue(String.valueOf(summary.get("auditTrail")).contains(AssistantEventTypes.RUN_RECOVERY_PLANNED));
        assertTrue(String.valueOf(summary.get("auditTrail")).contains(AssistantEventTypes.RUN_REPLAY_REQUESTED));
        assertTrue(String.valueOf(summary.get("auditTrail")).contains("REPLAY_AFTER_ROUTING"));
        assertTrue(String.valueOf(summary.get("auditTrail")).contains("fp-run-1-routed"));
        assertTrue(String.valueOf(summary.get("auditTrail")).contains("run_1:process:2:fp-run-1-routed"));
        assertTrue(String.valueOf(summary.get("auditTrail")).contains("RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT"));
        assertTrue(String.valueOf(summary.get("highRiskNodes")).contains("Approval"));
        assertTrue(String.valueOf(summary.get("failedNodes")).contains("Finalize"));
    }

    private AiRunEvent event(int order, String type, String payload) {
        AiRunEvent event = new AiRunEvent();
        event.setEventId("evt_" + order);
        event.setRunId("run_1");
        event.setConversationId("chat_1");
        event.setUserId(1L);
        event.setEventOrder(order);
        event.setEventType(type);
        event.setPayloadJson(payload == null ? "{}" : payload);
        event.setStatus(1);
        return event;
    }

    private AiRunGraphNode graphNode(String id, String type, String label, String status, int eventOrder, String eventType) {
        return graphNode(id, type, label, status, eventOrder, eventType, "LOW");
    }

    private AiRunGraphNode graphNode(String id, String type, String label, String status, int eventOrder, String eventType, String riskLevel) {
        AiRunGraphNode node = new AiRunGraphNode();
        node.setNodeId(id);
        node.setRunId("run_1");
        node.setConversationId("chat_1");
        node.setUserId(1L);
        node.setNodeType(type);
        node.setNodeLabel(label);
        node.setNodeStatus(status);
        node.setGraphOrder(RunGraphDefinition.graphOrder(id));
        node.setLatestEventOrder(eventOrder);
        node.setLatestEventType(eventType);
        node.setRiskLevel(riskLevel);
        node.setTraceRef("run_1");
        node.setStateJson("{\"eventType\":\"" + eventType + "\"}");
        node.setStatus(1);
        return node;
    }
}
