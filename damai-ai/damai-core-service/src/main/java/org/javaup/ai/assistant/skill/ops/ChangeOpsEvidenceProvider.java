package org.javaup.ai.assistant.skill.ops;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiOpsEventRaw;
import org.javaup.ai.mapper.AiOpsEventRawMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ChangeOpsEvidenceProvider implements OpsEvidenceProvider {

    private final AiOpsEventRawMapper eventRawMapper;

    @Override
    public String name() {
        return "ops-event-change-provider";
    }

    @Override
    public String signalType() {
        return "changes";
    }

    @Override
    public Map<String, Object> collect(OpsRcaRequest request, Instant start, Instant end) {
        Instant changeStart = end.minus(java.time.Duration.ofMinutes(request.getChangeWindowMinutes()));
        var query = Wrappers.lambdaQuery(AiOpsEventRaw.class)
                .eq(AiOpsEventRaw::getStatus, 1)
                .likeRight(AiOpsEventRaw::getEventType, "CHANGE_")
                .ge(AiOpsEventRaw::getOccurredAt, Date.from(changeStart))
                .le(AiOpsEventRaw::getOccurredAt, Date.from(end))
                .orderByDesc(AiOpsEventRaw::getOccurredAt)
                .last("limit 30");
        if (StringUtils.hasText(request.getServiceName())) {
            query.eq(AiOpsEventRaw::getSourceService, request.getServiceName());
        }
        List<AiOpsEventRaw> items = eventRawMapper.selectList(query);
        return Map.of("items", items, "windowStart", changeStart.toString(), "windowEnd", end.toString());
    }
}
