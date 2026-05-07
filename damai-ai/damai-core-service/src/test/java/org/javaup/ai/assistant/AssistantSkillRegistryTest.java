package org.javaup.ai.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssistantSkillRegistryTest {

    @Test
    void shouldAllowMultipleSkillsOnSameRouteAndKeepPrimaryRouteSkill() {
        AssistantSkill primary = skill("business.legacy", AssistantRouteType.BUSINESS, true);
        AssistantSkill search = skill("business.program.search", AssistantRouteType.BUSINESS, false);

        AssistantSkillRegistry registry = new AssistantSkillRegistry(List.of(primary, search));

        assertSame(primary, registry.getRequired(AssistantRouteType.BUSINESS));
        assertSame(search, registry.getRequired("business.program.search"));
        assertEquals(2, registry.listDescriptors(AssistantRouteType.BUSINESS).size());
    }

    @Test
    void shouldRejectDuplicateSkillId() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                new AssistantSkillRegistry(List.of(
                        skill("business.program.search", AssistantRouteType.BUSINESS, true),
                        skill("business.program.search", AssistantRouteType.BUSINESS, false)
                ))
        );

        assertEquals("duplicate assistant skill id: business.program.search", exception.getMessage());
    }

    private AssistantSkill skill(String skillId, AssistantRouteType routeType, boolean primary) {
        return new AssistantSkill() {
            @Override
            public AssistantRouteType routeType() {
                return routeType;
            }

            @Override
            public AssistantSkillDescriptor descriptor() {
                return AssistantSkillDescriptor.builder()
                        .skillId(skillId)
                        .name(skillId)
                        .version("1.0.0")
                        .routeType(routeType)
                        .category(routeType.getCode())
                        .riskLevel(AssistantSkillRiskLevel.LOW)
                        .requiresAdmin(false)
                        .requiresApproval(false)
                        .enabled(true)
                        .executorType("java")
                        .frontendSelectable(true)
                        .modelSelectable(true)
                        .primarySkill(primary)
                        .build();
            }

            @Override
            public AssistantSkillResult execute(AssistantSkillContext context) {
                return AssistantSkillResult.builder().message("ok").responseSummary("ok").build();
            }
        };
    }
}
