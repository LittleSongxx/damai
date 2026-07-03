package org.javaup.ai.runtime.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionArtifact {

    private String artifactId;

    private String runId;

    private ArtifactType type;

    private CapabilityRiskLevel riskLevel;

    private boolean requiresApproval;

    private String idempotencyKey;

    private Map<String, Object> input;

    private Map<String, Object> output;

    private ArtifactStatus status;

    private LocalDateTime createdAt;
}
