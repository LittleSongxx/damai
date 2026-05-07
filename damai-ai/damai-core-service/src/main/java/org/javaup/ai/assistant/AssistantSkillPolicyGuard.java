package org.javaup.ai.assistant;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.security.AiAuthorizationException;
import org.javaup.ai.security.AiPermissionService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AssistantSkillPolicyGuard {

    private final AiPermissionService permissionService;

    public void verifyBeforeExecution(AiUserContext user, AssistantSkillDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        if (!descriptor.enabled()) {
            throw new AiAuthorizationException("该 Skill 当前已停用");
        }
        if (!permissionService.canAccessSkill(user, descriptor)) {
            throw new AiAuthorizationException("当前账号无权访问该 Skill");
        }
        AssistantSkillRiskLevel riskLevel = descriptor.getRiskLevel();
        if ((riskLevel == AssistantSkillRiskLevel.HIGH || riskLevel == AssistantSkillRiskLevel.CRITICAL)
                && Boolean.TRUE.equals(descriptor.getRequiresAdmin())
                && !permissionService.isAdmin(user)) {
            throw new AiAuthorizationException("高风险 Skill 仅允许管理员执行");
        }
    }

    public void verifyAfterExecution(AssistantSkillDescriptor descriptor, AssistantSkillResult result) {
        if (descriptor == null || !Boolean.TRUE.equals(descriptor.getRequiresApproval())) {
            return;
        }
        if (result == null || result.getPendingAction() == null) {
            throw new IllegalStateException("Skill " + descriptor.getSkillId() + " 声明需要审批，但未生成待审批动作");
        }
    }
}
