package org.javaup.ai.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AssistantRunGraphVo {

    private String runId;

    private String chatId;

    private String status;

    private String currentStage;

    private String checkpointId;

    private String checkpointStage;

    private Map<String, Object> checkpointPayload;

    private Map<String, Object> summary;

    private List<Node> nodes;

    private List<Edge> edges;

    @Data
    @Builder
    public static class Node {

        private String id;

        private String type;

        private String label;

        private String status;

        private Integer eventOrder;

        private String eventType;

        private String eventCategory;

        private String checkpointId;

        private String checkpointStage;

        private String inputSummary;

        private String outputSummary;

        private String riskLevel;

        private String traceRef;

        private Integer totalTokens;

        private String estimatedCost;

        private Date startedAt;

        private Date completedAt;

        private Map<String, Object> payload;
    }

    @Data
    @Builder
    public static class Edge {

        private String source;

        private String target;

        private String label;
    }
}
