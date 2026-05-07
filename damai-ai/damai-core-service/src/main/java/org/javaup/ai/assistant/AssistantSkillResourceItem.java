package org.javaup.ai.assistant;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AssistantSkillResourceItem {

    private String resourceId;

    private String resourceType;

    private String title;

    private String content;

    private String metadataJson;
}
