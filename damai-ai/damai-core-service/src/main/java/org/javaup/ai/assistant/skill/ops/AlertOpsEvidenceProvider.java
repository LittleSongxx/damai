package org.javaup.ai.assistant.skill.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class AlertOpsEvidenceProvider implements OpsEvidenceProvider {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${damai.ai.ops.alertmanager.url:}")
    private String alertmanagerUrl;

    @Override
    public String name() {
        return "alertmanager-provider";
    }

    @Override
    public String signalType() {
        return "alerts";
    }

    @Override
    public boolean available() {
        return StringUtils.hasText(alertmanagerUrl);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> collect(OpsRcaRequest request, Instant start, Instant end) {
        if (!available()) {
            return Map.of("configured", false, "items", List.of());
        }
        Object response = restTemplate.getForObject(alertmanagerUrl + "/api/v2/alerts", Object.class);
        return Map.of("configured", true, "items", response instanceof List<?> list ? list : List.of());
    }
}
