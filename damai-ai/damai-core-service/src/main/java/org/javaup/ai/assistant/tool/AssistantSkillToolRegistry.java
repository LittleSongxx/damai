package org.javaup.ai.assistant.tool;

import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.springframework.stereotype.Component;

@Component
public class AssistantSkillToolRegistry {

    public AssistantSkillToolScope.ScopeHandle openScope(AssistantSkillDescriptor descriptor) {
        if (descriptor == null) {
            return AssistantSkillToolScope.open("", java.util.List.of("*"));
        }
        return AssistantSkillToolScope.open(descriptor.getSkillId(), descriptor.getToolAllowlist());
    }
}
