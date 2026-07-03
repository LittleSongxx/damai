package org.javaup.ai.runtime.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyDecision {

    private PolicyDecisionType type;

    private CapabilityRiskLevel riskLevel;

    private boolean approvalRequired;

    private List<String> reasons;
}
