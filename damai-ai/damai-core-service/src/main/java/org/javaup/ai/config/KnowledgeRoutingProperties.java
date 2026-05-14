package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.knowledge-routing")
public class KnowledgeRoutingProperties {

    private boolean shadowEnabled = true;

    private int maxScopes = 3;

    private int maxTopics = 5;

    private int maxDocuments = 5;

    private double minScore = 1.0D;
}
