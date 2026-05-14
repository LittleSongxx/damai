package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.runtime-lease")
public class AssistantRuntimeLeaseProperties {

    private boolean enabled = true;

    private boolean failOpen = true;

    private String keyPrefix = "damai:ai:runtime:lease:";

    private long ttlMs = 45000L;

    private long acquireTimeoutMs = 120000L;

    private long retryIntervalMs = 1000L;
}
