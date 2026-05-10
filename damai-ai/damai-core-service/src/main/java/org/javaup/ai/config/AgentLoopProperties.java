package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.agent-loop")
public class AgentLoopProperties {

    private boolean enabled = true;
    private int maxSteps = 5;
    private long stepTimeoutMs = 30000;
}
