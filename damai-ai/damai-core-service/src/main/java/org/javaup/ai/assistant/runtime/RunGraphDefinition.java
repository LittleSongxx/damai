package org.javaup.ai.assistant.runtime;

import org.javaup.ai.vo.AssistantRunGraphVo;

import java.util.List;

/**
 * Typed run graph contract shared by runtime execution and graph visualization.
 */
public final class RunGraphDefinition {

    public static final String ROUTE = "route";
    public static final String PLAN = "plan";
    public static final String SKILL = "skill";
    public static final String TOOL = "tool";
    public static final String APPROVAL = "approval";
    public static final String FINAL = "final";
    public static final String GUARDRAIL = "guardrail";
    public static final String STAGE = "stage";
    public static final String RUN = "run";
    public static final String RETRIEVAL = "retrieval";

    public static final String CATEGORY_RUN = "run";
    public static final String CATEGORY_STAGE = "stage";
    public static final String CATEGORY_TOOL = "tool";
    public static final String CATEGORY_RETRIEVAL = "retrieval";
    public static final String CATEGORY_APPROVAL = "approval";
    public static final String CATEGORY_GUARDRAIL = "guardrail";
    public static final String CATEGORY_FINAL = "final";

    private static final List<GraphNode> BASE_NODES = List.of(
            new GraphNode(ROUTE, ROUTE, "Route"),
            new GraphNode(PLAN, PLAN, "Plan"),
            new GraphNode(SKILL, SKILL, "Skill/Agent"),
            new GraphNode(TOOL, TOOL, "Tools/Retrieval"),
            new GraphNode(APPROVAL, APPROVAL, "Approval"),
            new GraphNode(FINAL, FINAL, "Finalize")
    );

    private static final List<AssistantRunGraphVo.Edge> BASE_EDGES = List.of(
            edge(ROUTE, PLAN, "route selected"),
            edge(PLAN, SKILL, "execution plan"),
            edge(SKILL, TOOL, "tool/retrieval calls"),
            edge(TOOL, APPROVAL, "optional interrupt"),
            edge(APPROVAL, FINAL, "approve/reject/skip"),
            edge(SKILL, FINAL, "direct final")
    );

    private RunGraphDefinition() {
    }

    public static List<GraphNode> baseNodes() {
        return BASE_NODES;
    }

    public static List<AssistantRunGraphVo.Edge> baseEdges() {
        return BASE_EDGES;
    }

    public static String nodeTypeForEvent(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return RUN;
        }
        if (eventType.startsWith("tool.")) {
            return TOOL;
        }
        if (eventType.startsWith("retrieval.")) {
            return RETRIEVAL;
        }
        if (eventType.startsWith("guardrail.")) {
            return GUARDRAIL;
        }
        if (eventType.startsWith("action.")) {
            return APPROVAL;
        }
        if (eventType.startsWith("skill.")) {
            return SKILL;
        }
        if (eventType.startsWith("route.")) {
            return ROUTE;
        }
        if (eventType.startsWith("stage.")) {
            return STAGE;
        }
        return RUN;
    }

    public static String eventCategory(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return CATEGORY_RUN;
        }
        if (eventType.startsWith("stage.") || eventType.startsWith("agent.") || eventType.startsWith("skill.")
                || eventType.startsWith("checkpoint.")) {
            return CATEGORY_STAGE;
        }
        if (eventType.startsWith("tool.")) {
            return CATEGORY_TOOL;
        }
        if (eventType.startsWith("retrieval.") || eventType.startsWith("knowledge.route.")) {
            return CATEGORY_RETRIEVAL;
        }
        if (eventType.startsWith("action.") || "clarification.required".equals(eventType)) {
            return CATEGORY_APPROVAL;
        }
        if (eventType.startsWith("guardrail.")) {
            return CATEGORY_GUARDRAIL;
        }
        if (eventType.startsWith("message.") || "run.completed".equals(eventType) || "run.failed".equals(eventType)) {
            return CATEGORY_FINAL;
        }
        return CATEGORY_RUN;
    }

    public static String nodeIdForEvent(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return RUN;
        }
        if (eventType.startsWith("route.")) {
            return ROUTE;
        }
        if ("run.started".equals(eventType)) {
            return PLAN;
        }
        if (eventType.startsWith("skill.") || eventType.startsWith("agent.") || eventType.startsWith("checkpoint.")) {
            return SKILL;
        }
        if (eventType.startsWith("tool.") || eventType.startsWith("retrieval.")) {
            return TOOL;
        }
        if (eventType.startsWith("action.") || "clarification.required".equals(eventType)) {
            return APPROVAL;
        }
        if (eventType.startsWith("guardrail.")) {
            return GUARDRAIL;
        }
        if (eventType.startsWith("stage.")) {
            return STAGE;
        }
        if ("run.resumed".equals(eventType)
                || "run.recovery_planned".equals(eventType)
                || "run.replay_requested".equals(eventType)) {
            return RUN;
        }
        if (eventType.startsWith("message.") || eventType.startsWith("run.")) {
            return FINAL;
        }
        return RUN;
    }

    public static String nodeIdForCheckpointStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return RUN;
        }
        if (stage.contains("ROUTE") || "ROUTED".equals(stage)) {
            return ROUTE;
        }
        if (stage.contains("SKILL") || stage.contains("SQL")) {
            return SKILL;
        }
        if (stage.contains("RETRIEVAL")) {
            return TOOL;
        }
        if (stage.contains("ACTION") || stage.contains("APPROVAL")) {
            return APPROVAL;
        }
        if (stage.contains("FAILED") || stage.contains("FINAL")) {
            return FINAL;
        }
        return STAGE;
    }

    public static int graphOrder(String nodeId) {
        if (nodeId == null) {
            return 900;
        }
        return switch (nodeId) {
            case ROUTE -> 10;
            case PLAN -> 20;
            case SKILL -> 30;
            case TOOL -> 40;
            case APPROVAL -> 50;
            case FINAL -> 60;
            case GUARDRAIL -> 70;
            case STAGE -> 80;
            case RUN -> 90;
            case RETRIEVAL -> 40;
            default -> 900;
        };
    }

    private static AssistantRunGraphVo.Edge edge(String source, String target, String label) {
        return AssistantRunGraphVo.Edge.builder()
                .source(source)
                .target(target)
                .label(label)
                .build();
    }

    public record GraphNode(String id, String type, String label) {
    }
}
