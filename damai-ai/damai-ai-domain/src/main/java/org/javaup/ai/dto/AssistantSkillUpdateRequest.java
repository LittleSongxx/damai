package org.javaup.ai.dto;

import lombok.Data;

import java.util.List;

@Data
public class AssistantSkillUpdateRequest {

    private String name;

    private String description;

    private String version;

    private String goal;

    private String instructions;

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

    private Boolean frontendSelectable;

    private Boolean modelSelectable;
}
