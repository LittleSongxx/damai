package org.javaup.ai.assistant.skill.ops;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiOpsRunbook;
import org.javaup.ai.mapper.AiOpsRunbookMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RunbookOpsEvidenceProvider implements OpsEvidenceProvider {

    private final AiOpsRunbookMapper runbookMapper;

    @Override
    public String name() {
        return "db-runbook-provider";
    }

    @Override
    public String signalType() {
        return "runbooks";
    }

    @Override
    public Map<String, Object> collect(OpsRcaRequest request, Instant start, Instant end) {
        var query = Wrappers.lambdaQuery(AiOpsRunbook.class)
                .eq(AiOpsRunbook::getStatus, 1)
                .eq(AiOpsRunbook::getRunbookStatus, "ACTIVE")
                .orderByAsc(AiOpsRunbook::getRiskLevel)
                .last("limit 10");
        if (StringUtils.hasText(request.getServiceName())) {
            query.and(wrapper -> wrapper
                    .eq(AiOpsRunbook::getServiceName, request.getServiceName())
                    .or()
                    .isNull(AiOpsRunbook::getServiceName)
                    .or()
                    .eq(AiOpsRunbook::getServiceName, ""));
        }
        List<Map<String, Object>> items = runbookMapper.selectList(query).stream()
                .map(item -> Map.<String, Object>of(
                        "runbookId", item.getRunbookId(),
                        "title", item.getTitle(),
                        "serviceName", item.getServiceName() == null ? "" : item.getServiceName(),
                        "scenarioKey", item.getScenarioKey(),
                        "recommendation", item.getRecommendation() == null ? "" : item.getRecommendation(),
                        "riskLevel", item.getRiskLevel(),
                        "executable", item.getExecutable() != null && item.getExecutable() == 1,
                        "reviewRequired", item.getReviewRequired() == null || item.getReviewRequired() == 1))
                .toList();
        return Map.of("items", items, "executionPolicy", "SUGGEST_ONLY");
    }
}
