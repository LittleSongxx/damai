package org.javaup.ai.assistant;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class AssistantSkillRegistry {

    private final Map<AssistantRouteType, AssistantSkill> skillMap;

    public AssistantSkillRegistry(List<AssistantSkill> skills) {
        EnumMap<AssistantRouteType, AssistantSkill> map = new EnumMap<>(AssistantRouteType.class);
        for (AssistantSkill skill : skills) {
            AssistantSkill previous = map.putIfAbsent(skill.routeType(), skill);
            if (previous != null) {
                throw new IllegalStateException("duplicate assistant skill route: " + skill.routeType());
            }
        }
        this.skillMap = Map.copyOf(map);
    }

    public AssistantSkill getRequired(AssistantRouteType routeType) {
        AssistantSkill skill = skillMap.get(routeType);
        if (skill == null) {
            throw new IllegalStateException("skill not found: " + routeType);
        }
        return skill;
    }
}
