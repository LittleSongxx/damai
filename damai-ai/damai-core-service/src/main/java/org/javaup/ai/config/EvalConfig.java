package org.javaup.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "damai.ai.eval")
public class EvalConfig {

    private boolean degradedMode = false;

    public boolean isDegradedMode() {
        return degradedMode;
    }

    public void setDegradedMode(boolean degradedMode) {
        this.degradedMode = degradedMode;
    }
}
