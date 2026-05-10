package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.guardrails")
public class AiGuardrailsProperties {

    private boolean enabled = true;
    private boolean piiEnabled = true;
    private boolean hallucinationEnabled = true;
    private boolean toxicityEnabled = true;
    private boolean blockOnPii = true;
}
