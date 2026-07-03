package org.javaup.ai.runtime.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeBudget {

    private Integer maxModelCalls;

    private Integer maxToolCalls;

    private Integer maxTokens;

    private Long maxCostMicros;
}
