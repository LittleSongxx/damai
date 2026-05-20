package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.security")
public class AiSecurityProperties {

    private String adminUserIds = "";
    private long dailyTokenBudget = 0;
}
