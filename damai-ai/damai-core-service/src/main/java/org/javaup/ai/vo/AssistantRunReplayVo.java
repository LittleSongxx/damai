package org.javaup.ai.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class AssistantRunReplayVo {

    private String runId;

    private String chatId;

    private String status;

    private String checkpointId;

    private String checkpointStage;

    private String checkpointFingerprint;

    private String replayAttemptId;

    private String idempotencyPolicy;

    private boolean replayable;

    private boolean replayScheduled;

    private boolean skipRouting;

    private boolean skipRetrieval;

    private String riskHint;

    private String eventStreamPath;

    private List<String> nextActions;

    private Map<String, Object> checkpointPayload;
}
