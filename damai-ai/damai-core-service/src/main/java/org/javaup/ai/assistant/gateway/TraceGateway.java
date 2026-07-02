package org.javaup.ai.assistant.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class TraceGateway {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${damai.ai.ops.skywalking.graphql-url:}")
    private String skywalkingGraphqlUrl;

    public boolean available() {
        return StringUtils.hasText(skywalkingGraphqlUrl);
    }

    public Map<String, Object> getTrace(String traceId, Instant start, Instant end) {
        if (!available()) {
            return Map.of(
                    "configured", false,
                    "traceId", traceId == null ? "" : traceId,
                    "count", 0,
                    "spans", List.of());
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "query", """
                        query queryTrace($traceId: ID!) {
                          queryTrace(traceId: $traceId) {
                            spans {
                              traceId
                              segmentId
                              spanId
                              parentSpanId
                              serviceCode
                              endpointName
                              startTime
                              endTime
                              isError
                              tags { key value }
                              logs { time data { key value } }
                            }
                          }
                        }
                        """,
                "variables", Map.of("traceId", traceId));
        Object response = restTemplate.postForObject(skywalkingGraphqlUrl, new HttpEntity<>(body, headers), Object.class);
        return Map.of(
                "configured", true,
                "source", "skywalking",
                "traceId", traceId,
                "windowStart", start == null ? "" : start.toString(),
                "windowEnd", end == null ? "" : end.toString(),
                "response", response == null ? Map.of() : response);
    }
}
