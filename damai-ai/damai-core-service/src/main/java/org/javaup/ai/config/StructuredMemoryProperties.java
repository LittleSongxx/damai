package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.memory.structured")
public class StructuredMemoryProperties {

    private boolean enabled = true;

    private int stableFactLimit = 6;

    private int pendingQuestionLimit = 4;

    private int retrievalHintLimit = 6;
}
