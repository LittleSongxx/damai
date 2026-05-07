package org.javaup.ai.assistant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class AssistantSkillRegistry {

    private final Map<String, AssistantSkill> skillById;
    private final Map<String, AssistantSkillDescriptor> descriptorById;
    private final Map<AssistantRouteType, List<AssistantSkill>> skillsByRoute;
    private final Map<AssistantRouteType, AssistantSkill> primarySkillByRoute;

    public AssistantSkillRegistry(List<AssistantSkill> skills) {
        this(skills, null);
    }

    @Autowired
    public AssistantSkillRegistry(List<AssistantSkill> skills, ObjectProvider<AssistantSkillDefinitionService> definitionServiceProvider) {
        AssistantSkillDefinitionService definitionService = definitionServiceProvider == null ? null : definitionServiceProvider.getIfAvailable();
        Map<String, AssistantSkill> skillMap = new HashMap<>();
        Map<String, AssistantSkillDescriptor> descriptorMap = new HashMap<>();
        EnumMap<AssistantRouteType, List<AssistantSkill>> routeMap = new EnumMap<>(AssistantRouteType.class);
        EnumMap<AssistantRouteType, AssistantSkill> primaryMap = new EnumMap<>(AssistantRouteType.class);
        for (AssistantSkill skill : skills) {
            AssistantSkillDescriptor descriptor = mergeDescriptor(definitionService, skill.descriptor());
            AssistantSkill previous = skillMap.putIfAbsent(descriptor.getSkillId(), skill);
            if (previous != null) {
                throw new IllegalStateException("duplicate assistant skill id: " + descriptor.getSkillId());
            }
            descriptorMap.put(descriptor.getSkillId(), descriptor);
            routeMap.computeIfAbsent(descriptor.getRouteType(), ignored -> new ArrayList<>()).add(skill);
            if (descriptor.primarySkill()) {
                primaryMap.putIfAbsent(descriptor.getRouteType(), skill);
            }
        }
        for (Map.Entry<AssistantRouteType, List<AssistantSkill>> entry : routeMap.entrySet()) {
            primaryMap.putIfAbsent(entry.getKey(), entry.getValue().get(0));
        }
        this.skillById = Map.copyOf(skillMap);
        this.descriptorById = Map.copyOf(descriptorMap);
        this.skillsByRoute = routeMap.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        this.primarySkillByRoute = Map.copyOf(primaryMap);
    }

    public AssistantSkill getRequired(AssistantRouteType routeType) {
        AssistantSkill skill = primarySkillByRoute.get(routeType);
        if (skill == null) {
            throw new IllegalStateException("skill not found: " + routeType);
        }
        return skill;
    }

    public AssistantSkill getRequired(String skillId) {
        AssistantSkill skill = skillById.get(skillId);
        if (skill == null) {
            throw new IllegalStateException("skill not found: " + skillId);
        }
        return skill;
    }

    public AssistantSkillDescriptor getDescriptor(String skillId) {
        return descriptorById.get(skillId);
    }

    public AssistantSkillDescriptor getDescriptor(AssistantSkill skill) {
        if (skill == null) {
            return null;
        }
        return descriptorById.get(skill.descriptor().getSkillId());
    }

    public List<AssistantSkillDescriptor> listDescriptors() {
        return descriptorById.values().stream()
                .sorted((left, right) -> left.getSkillId().compareTo(right.getSkillId()))
                .toList();
    }

    public List<AssistantSkillDescriptor> listDescriptors(AssistantRouteType routeType) {
        return skillsByRoute.getOrDefault(routeType, List.of()).stream()
                .map(this::getDescriptor)
                .filter(descriptor -> descriptor != null)
                .toList();
    }

    private AssistantSkillDescriptor mergeDescriptor(AssistantSkillDefinitionService definitionService, AssistantSkillDescriptor descriptor) {
        if (definitionService == null) {
            return descriptor;
        }
        return definitionService.mergeDescriptor(descriptor);
    }
}
