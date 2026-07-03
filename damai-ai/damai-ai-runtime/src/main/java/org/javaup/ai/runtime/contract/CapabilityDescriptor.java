package org.javaup.ai.runtime.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityDescriptor {

    private String capabilityId;

    private String displayName;

    private CapabilityType type;

    private Set<String> scopes;

    private CapabilityRiskLevel riskLevel;

    private boolean requiresApproval;

    private boolean requiresAdmin;

    private Duration timeout;

    private IdempotencyPolicy idempotencyPolicy;

    private String inputSchema;

    private String outputSchema;
}
