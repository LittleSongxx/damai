package org.javaup.ai.assistant;

import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.security.AiPermissionService;
import org.javaup.ai.service.LlmSkillSelectorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantSkillSelectorTest {

    @Test
    void shouldRespectSkillHintWhenEnabledAndAllowed() {
        AssistantSkillDescriptor descriptor = descriptor("business.program.search", AssistantRouteType.BUSINESS, false, true, false, "演出");
        AssistantSkillSelector selector = selector(List.of(skill(descriptor)), descriptor, true);

        AssistantSkillDecision decision = selector.select(AssistantRouteType.BUSINESS, user(false), request("随便聊聊", Map.of("skillHint", "business.program.search")));

        assertEquals("business.program.search", decision.getSkillId());
        assertTrue(decision.getFromHint());
        assertEquals("skill_hint:business.program.search", decision.getReason());
    }

    @Test
    void shouldRejectDisabledSkillHint() {
        AssistantSkillDescriptor descriptor = descriptor("business.program.search", AssistantRouteType.BUSINESS, false, false, false, "演出");
        AssistantSkillSelector selector = selector(List.of(skill(descriptor)), descriptor, true);

        AssistantSkillDecision decision = selector.select(AssistantRouteType.BUSINESS, user(false), request("找演出", Map.of("skillHint", "business.program.search")));

        assertEquals("skill:disabled", decision.getReason());
        assertTrue(decision.getFromHint());
    }

    @Test
    void shouldSelectKeywordMatchedSkillBeforePrimaryFallback() {
        AssistantSkillDescriptor primary = descriptor("business.default", AssistantRouteType.BUSINESS, true, true, false, "演出");
        AssistantSkillDescriptor purchase = descriptor("business.purchase.prepare", AssistantRouteType.BUSINESS, false, true, false, "买", "下单");
        AssistantSkillSelector selector = selector(List.of(skill(primary), skill(purchase)), primary, true);

        AssistantSkillDecision decision = selector.select(AssistantRouteType.BUSINESS, user(false), request("帮我买两张票", null));

        assertEquals("business.purchase.prepare", decision.getSkillId());
        assertFalse(decision.getFromHint());
    }

    @Test
    void shouldRejectForbiddenOpsSkillHintForNonAdmin() {
        AssistantSkillDescriptor descriptor = descriptor("ops.nl2sql.query", AssistantRouteType.OPS, false, true, true, "问数");
        AssistantSkillSelector selector = selector(List.of(skill(descriptor)), descriptor, false);

        AssistantSkillDecision decision = selector.select(AssistantRouteType.OPS, user(false), request("统计订单量", Map.of("skillHint", "ops.nl2sql.query")));

        assertEquals("skill:forbidden", decision.getReason());
    }

    private AssistantSkillSelector selector(List<AssistantSkill> skills, AssistantSkillDescriptor descriptor, boolean allowed) {
        AssistantSkillRegistry registry = new AssistantSkillRegistry(skills);
        AiPermissionService permissionService = mock(AiPermissionService.class);
        when(permissionService.canAccessSkill(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(allowed);
        when(permissionService.canAccessSkill(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(descriptor))).thenReturn(allowed);
        ObjectProvider<AssistantSkillDefinitionService> stubDefProvider = stubProvider();
        ObjectProvider<LlmSkillSelectorService> stubLlmProvider = stubProvider();
        return new AssistantSkillSelector(registry, permissionService, stubDefProvider, stubLlmProvider);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> stubProvider() {
        return mock(ObjectProvider.class);
    }

    private AssistantRunCreateRequest request(String message, Map<String, Object> clientContext) {
        AssistantRunCreateRequest request = new AssistantRunCreateRequest();
        request.setMessage(message);
        request.setClientContext(clientContext);
        return request;
    }

    private AiUserContext user(boolean admin) {
        return AiUserContext.builder().userId(admin ? 1L : 2L).admin(admin).build();
    }

    private AssistantSkillDescriptor descriptor(String skillId, AssistantRouteType routeType, boolean primary, boolean enabled, boolean admin, String... keywords) {
        return AssistantSkillDescriptor.builder()
                .skillId(skillId)
                .name(skillId)
                .version("1.0.0")
                .routeType(routeType)
                .category(routeType.getCode())
                .triggerKeywords(List.of(keywords))
                .riskLevel(admin ? AssistantSkillRiskLevel.HIGH : AssistantSkillRiskLevel.LOW)
                .requiresAdmin(admin)
                .requiresApproval(false)
                .enabled(enabled)
                .executorType("java")
                .frontendSelectable(true)
                .modelSelectable(true)
                .primarySkill(primary)
                .build();
    }

    private AssistantSkill skill(AssistantSkillDescriptor descriptor) {
        return new AssistantSkill() {
            @Override
            public AssistantRouteType routeType() {
                return descriptor.getRouteType();
            }

            @Override
            public AssistantSkillDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public AssistantSkillResult execute(AssistantSkillContext context) {
                return AssistantSkillResult.builder().message("ok").responseSummary("ok").build();
            }
        };
    }
}
