package org.javaup.ai.assistant;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder(toBuilder = true)
public class AssistantSkillDescriptor {

    private String skillId;

    private String name;

    private String description;

    private String version;

    private String goal;

    private String instructions;

    private AssistantRouteType routeType;

    private String category;

    @Builder.Default
    private List<String> triggerKeywords = new ArrayList<>();

    @Builder.Default
    private List<String> toolAllowlist = new ArrayList<>();

    @Builder.Default
    private List<String> examples = new ArrayList<>();

    @Builder.Default
    private List<String> evalCases = new ArrayList<>();

    private String inputSchemaJson;

    private String outputSchemaJson;

    private AssistantSkillRiskLevel riskLevel;

    private Boolean requiresAdmin;

    private Boolean requiresApproval;

    private Boolean enabled;

    private String executorType;

    private Boolean frontendSelectable;

    private Boolean modelSelectable;

    private Boolean primarySkill;

    private Boolean requiresMemory;

    public static AssistantSkillDescriptor defaultDescriptor(AssistantRouteType routeType, String skillName) {
        boolean ops = routeType == AssistantRouteType.OPS;
        return AssistantSkillDescriptor.builder()
                .skillId(routeType.getCode() + ".default")
                .name(skillName)
                .description(routeType.getCode() + " 路由的默认 Skill")
                .version("1.0.0")
                .goal("处理 " + routeType.getCode() + " 路由下的默认请求")
                .instructions("执行前需遵守统一权限、工具审计和审批策略。")
                .routeType(routeType)
                .category(routeType.getCode())
                .triggerKeywords(List.of())
                .toolAllowlist(List.of("*"))
                .examples(List.of())
                .evalCases(List.of())
                .riskLevel(ops ? AssistantSkillRiskLevel.HIGH : AssistantSkillRiskLevel.LOW)
                .requiresAdmin(ops)
                .requiresApproval(false)
                .enabled(true)
                .executorType("java")
                .frontendSelectable(true)
                .modelSelectable(true)
                .primarySkill(true)
                .build();
    }

    public AssistantSkillPolicy policy() {
        return AssistantSkillPolicy.builder()
                .requiresAdmin(Boolean.TRUE.equals(requiresAdmin))
                .requiresApproval(Boolean.TRUE.equals(requiresApproval))
                .riskLevel(riskLevel == null ? AssistantSkillRiskLevel.LOW : riskLevel)
                .frontendSelectable(!Boolean.FALSE.equals(frontendSelectable))
                .modelSelectable(!Boolean.FALSE.equals(modelSelectable))
                .build();
    }

    public boolean enabled() {
        return !Boolean.FALSE.equals(enabled);
    }

    public boolean primarySkill() {
        return Boolean.TRUE.equals(primarySkill);
    }
}
