package org.javaup.ai.assistant.skill.ops;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiOpsEventRaw;
import org.javaup.ai.mapper.AiOpsEventRawMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Date;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class BusinessEventOpsEvidenceProvider implements OpsEvidenceProvider {

    private final AiOpsEventRawMapper eventRawMapper;

    @Override
    public String name() {
        return "ops-event-business-provider";
    }

    @Override
    public String signalType() {
        return "businessEvents";
    }

    @Override
    public Map<String, Object> collect(OpsRcaRequest request, Instant start, Instant end) {
        var query = Wrappers.lambdaQuery(AiOpsEventRaw.class)
                .eq(AiOpsEventRaw::getStatus, 1)
                .ge(AiOpsEventRaw::getOccurredAt, Date.from(start))
                .le(AiOpsEventRaw::getOccurredAt, Date.from(end))
                .orderByDesc(AiOpsEventRaw::getOccurredAt)
                .last("limit 50");
        if (StringUtils.hasText(request.getServiceName())) {
            query.eq(AiOpsEventRaw::getSourceService, request.getServiceName());
        }
        return Map.of("items", eventRawMapper.selectList(query));
    }
}
