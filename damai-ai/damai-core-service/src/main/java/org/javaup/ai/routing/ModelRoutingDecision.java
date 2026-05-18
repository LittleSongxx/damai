package org.javaup.ai.routing;

import lombok.Builder;

@Builder
public record ModelRoutingDecision(
        String selectedModel,
        String routingReason,
        boolean usedCheapModel
) {

    public static ModelRoutingDecision primary(String reason) {
        return ModelRoutingDecision.builder()
                .selectedModel("primary")
                .routingReason(reason)
                .usedCheapModel(false)
                .build();
    }

    public static ModelRoutingDecision cheap(String cheapModel, String reason) {
        return ModelRoutingDecision.builder()
                .selectedModel(cheapModel)
                .routingReason(reason)
                .usedCheapModel(true)
                .build();
    }
}
