package org.javaup.ai.assistant;

import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.security.AiAuthorizationException;
import org.javaup.ai.security.AiPermissionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantSkillPolicyGuardTest {

    @Test
    void shouldRejectDisabledSkillBeforeExecution() {
        AssistantSkillPolicyGuard guard = new AssistantSkillPolicyGuard(mock(AiPermissionService.class));
        AssistantSkillDescriptor descriptor = baseDescriptor().toBuilder()
                .enabled(false)
                .build();

        AiAuthorizationException exception = assertThrows(AiAuthorizationException.class, () ->
                guard.verifyBeforeExecution(AiUserContext.builder().userId(1L).build(), descriptor));

        assertEquals("该 Skill 当前已停用", exception.getMessage());
    }

    @Test
    void shouldRejectHighRiskAdminSkillForNonAdminUser() {
        AiPermissionService permissionService = mock(AiPermissionService.class);
        AssistantSkillPolicyGuard guard = new AssistantSkillPolicyGuard(permissionService);
        AiUserContext user = AiUserContext.builder().userId(1L).admin(false).build();
        AssistantSkillDescriptor descriptor = baseDescriptor().toBuilder()
                .riskLevel(AssistantSkillRiskLevel.HIGH)
                .requiresAdmin(true)
                .build();
        when(permissionService.canAccessSkill(user, descriptor)).thenReturn(true);
        when(permissionService.isAdmin(user)).thenReturn(false);

        AiAuthorizationException exception = assertThrows(AiAuthorizationException.class, () ->
                guard.verifyBeforeExecution(user, descriptor));

        assertEquals("高风险 Skill 仅允许管理员执行", exception.getMessage());
    }

    @Test
    void shouldRequirePendingActionWhenSkillRequiresApproval() {
        AssistantSkillPolicyGuard guard = new AssistantSkillPolicyGuard(mock(AiPermissionService.class));
        AssistantSkillDescriptor descriptor = baseDescriptor().toBuilder()
                .requiresApproval(true)
                .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                guard.verifyAfterExecution(descriptor, AssistantSkillResult.builder()
                        .message("预览")
                        .responseSummary("预览")
                        .build()));

        assertEquals("Skill business.purchase.prepare 声明需要审批，但未生成待审批动作", exception.getMessage());
    }

    @Test
    void shouldAllowRequiredApprovalWhenPendingActionExists() {
        AssistantSkillPolicyGuard guard = new AssistantSkillPolicyGuard(mock(AiPermissionService.class));
        AssistantSkillDescriptor descriptor = baseDescriptor().toBuilder()
                .requiresApproval(true)
                .build();

        assertDoesNotThrow(() -> guard.verifyAfterExecution(descriptor, AssistantSkillResult.builder()
                .message("预览")
                .responseSummary("预览")
                .pendingAction(new AiAction())
                .build()));
    }

    private AssistantSkillDescriptor baseDescriptor() {
        return AssistantSkillDescriptor.builder()
                .skillId("business.purchase.prepare")
                .name("购票预览")
                .description("生成购票预览")
                .version("1.0.0")
                .routeType(AssistantRouteType.BUSINESS)
                .category("business")
                .riskLevel(AssistantSkillRiskLevel.LOW)
                .requiresAdmin(false)
                .requiresApproval(false)
                .enabled(true)
                .executorType("java")
                .frontendSelectable(true)
                .modelSelectable(true)
                .primarySkill(false)
                .build();
    }
}
