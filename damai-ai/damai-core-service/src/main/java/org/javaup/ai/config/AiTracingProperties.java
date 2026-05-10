package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.tracing")
public class AiTracingProperties {

    private boolean enabled = true;
    private String otlpEndpoint = "http://127.0.0.1:4317";
    private String serviceName = "damai-ai";
    private boolean logExporterEnabled = true;
}
