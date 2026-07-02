package org.javaup.ai.assistant.skill.ops;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiOpsEventRaw;
import org.javaup.ai.mapper.AiOpsEventRawMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class TopologyOpsEvidenceProvider implements OpsEvidenceProvider {

    private final AiOpsEventRawMapper eventRawMapper;

    @Override
    public String name() {
        return "ops-event-topology-provider";
    }

    @Override
    public String signalType() {
        return "topology";
    }

    @Override
    public Map<String, Object> collect(OpsRcaRequest request, Instant start, Instant end) {
        List<AiOpsEventRaw> items = eventRawMapper.selectList(Wrappers.lambdaQuery(AiOpsEventRaw.class)
                .eq(AiOpsEventRaw::getStatus, 1)
                .ge(AiOpsEventRaw::getOccurredAt, Date.from(start))
                .le(AiOpsEventRaw::getOccurredAt, Date.from(end))
                .orderByDesc(AiOpsEventRaw::getOccurredAt)
                .last("limit 100"));
        Set<String> services = new LinkedHashSet<>();
        Set<Map<String, Object>> edges = new LinkedHashSet<>();
        for (AiOpsEventRaw item : items) {
            services.add(item.getSourceService());
            JSONObject payload = parse(item.getPayloadJson());
            String target = payload.getString("targetService");
            if (target != null && !target.isBlank()) {
                services.add(target);
                edges.add(Map.of("from", item.getSourceService(), "to", target, "relation", item.getEventType()));
            }
        }
        services.remove(null);
        return Map.of("services", services, "edges", edges, "source", "ops-event-raw");
    }

    private JSONObject parse(String json) {
        try {
            return JSON.parseObject(json == null ? "{}" : json);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }
}
