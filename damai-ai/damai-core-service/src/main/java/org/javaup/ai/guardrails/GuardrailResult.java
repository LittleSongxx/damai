package org.javaup.ai.guardrails;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardrailResult {

    public enum Action { PASS, WARN, BLOCK }

    @Builder.Default
    private Action action = Action.PASS;
    @Builder.Default
    private List<String> reasons = new ArrayList<>();
    private String sanitizedContent;

    public static GuardrailResult pass() {
        return GuardrailResult.builder().action(Action.PASS).build();
    }

    public static GuardrailResult warn(String reason) {
        return GuardrailResult.builder().action(Action.WARN).reasons(List.of(reason)).build();
    }

    public static GuardrailResult block(String reason, String sanitized) {
        return GuardrailResult.builder().action(Action.BLOCK).reasons(List.of(reason)).sanitizedContent(sanitized).build();
    }

    public boolean isBlocked() {
        return action == Action.BLOCK;
    }
}
