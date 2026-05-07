package org.javaup.ai.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AssistantSkillVo {

    private String skillId;

    private String name;

    private String description;

    private String version;

    private String goal;

    private String instructions;

    private String routeType;

    private String category;

    private List<String> triggerKeywords;

    private List<String> toolAllowlist;

    private List<String> examples;

    private List<String> evalCases;

    private String inputSchemaJson;

    private String outputSchemaJson;

    private String riskLevel;

    private Boolean requiresAdmin;

    private Boolean requiresApproval;

    private Boolean enabled;

    private String executorType;

    private Boolean frontendSelectable;

    private Boolean modelSelectable;

    private Boolean primarySkill;
}
