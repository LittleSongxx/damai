package org.javaup.ai.assistant;

public interface AssistantSkill {

    AssistantRouteType routeType();

    default AssistantSkillDescriptor descriptor() {
        return AssistantSkillDescriptor.defaultDescriptor(routeType(), getClass().getSimpleName());
    }

    AssistantSkillResult execute(AssistantSkillContext context);
}
