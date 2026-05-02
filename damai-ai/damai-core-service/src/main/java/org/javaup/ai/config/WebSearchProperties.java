package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.web-search")
public class WebSearchProperties {

    private boolean enabled = true;

    private int timeoutMs = 5000;

    private int maxResults = 5;

    private Provider tavily = Provider.of("https://api.tavily.com/search");

    private Provider bocha = Provider.of("https://api.bochaai.com/v1/web-search");

    @Data
    public static class Provider {

        private boolean enabled = true;

        private String apiKey = "";

        private List<String> apiKeys = new ArrayList<>();

        private String url = "";

        public static Provider of(String url) {
            Provider provider = new Provider();
            provider.setUrl(url);
            return provider;
        }
    }
}
