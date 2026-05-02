package org.javaup.ai.assistant;

public interface AssistantSkill {

    AssistantRouteType routeType();

    AssistantSkillResult execute(AssistantSkillContext context);
}
