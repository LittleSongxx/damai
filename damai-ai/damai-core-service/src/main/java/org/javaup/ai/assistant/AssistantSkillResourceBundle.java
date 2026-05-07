package org.javaup.ai.assistant;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class AssistantSkillResourceBundle {

    @Builder.Default
    private List<AssistantSkillResourceItem> resources = new ArrayList<>();

    public static AssistantSkillResourceBundle empty() {
        return AssistantSkillResourceBundle.builder().resources(List.of()).build();
    }
}
