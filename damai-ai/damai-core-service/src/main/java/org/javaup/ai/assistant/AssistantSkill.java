package org.javaup.ai.assistant;

public interface AssistantSkill {

    AssistantRouteType routeType();

    default AssistantSkillDescriptor descriptor() {
        return AssistantSkillDescriptor.legacy(routeType(), getClass().getSimpleName());
    }

    AssistantSkillResult execute(AssistantSkillContext context);
}
