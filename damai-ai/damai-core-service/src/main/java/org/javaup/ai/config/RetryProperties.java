package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.retry")
public class RetryProperties {

    private int maxRetries = 1;
    private boolean retryableOnTimeout = true;
}
