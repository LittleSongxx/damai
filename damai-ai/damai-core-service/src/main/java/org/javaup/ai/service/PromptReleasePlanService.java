package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiPromptVersion;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PromptReleasePlanService {

    private final PromptVersionService promptVersionService;
    private final AiQualityGateService qualityGateService;

    public Map<String, Object> buildPlan(String promptKey,
                                         Integer version,
                                         String rolloutStatus,
                                         Integer trafficPercent,
                                         String baselineEvalRunId) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String effectiveRollout = normalizeRolloutStatus(rolloutStatus);
        Integer effectiveTraffic = normalizeTraffic(effectiveRollout, trafficPercent);

        if (!StringUtils.hasText(promptKey)) {
            blockers.add("promptKey is required");
        }
        if (version == null) {
            blockers.add("target version is required");
        }

        List<AiPromptVersion> versions = StringUtils.hasText(promptKey)
                ? promptVersionService.list(promptKey)
                : List.of();
        AiPromptVersion target = versions.stream()
                .filter(item -> version != null && version.equals(item.getVersion()))
                .findFirst()
                .orElse(null);
        if (version != null && target == null) {
            blockers.add("target prompt version does not exist");
        }

        Map<String, Object> qualityGate = qualityGateService.latestGate();
        Map<String, Object> readiness = mapValue(qualityGate.get("releaseReadiness"));
        Map<String, Object> ragClosure = mapValue(qualityGate.get("ragClosure"));
        Map<String, Object> nl2SqlContract = mapValue(qualityGate.get("nl2SqlContract"));
        String qualityStatus = text(qualityGate.get("status"));
        if ("FAIL".equals(qualityStatus) || "BLOCKED".equals(text(readiness.get("status")))) {
            blockers.add("quality gate is blocking release");
        } else if ("WARN".equals(qualityStatus) || "READY_WITH_WARNINGS".equals(text(readiness.get("status")))) {
            warnings.add("quality gate has warnings; keep rollout conservative");
        }

        boolean hasBaseline = StringUtils.hasText(baselineEvalRunId)
                || Boolean.TRUE.equals(ragClosure.get("baselineReady"));
        if (!hasBaseline) {
            blockers.add("baseline eval comparison is required before prompt/config release");
        }
        if (Boolean.TRUE.equals(ragClosure.get("releaseBlocked"))) {
            blockers.add("RAG bad-case closure blocks release");
        }
        if (!Boolean.TRUE.equals(nl2SqlContract.get("contractReady"))) {
            warnings.add("NL2SQL response/safety contract is not fully verified by the latest eval run");
        }
        if ("GRADUAL".equals(effectiveRollout) && stableActive(versions) == null) {
            blockers.add("gradual rollout requires an existing stable version");
        }

        Integer rollbackTargetVersion = rollbackTargetVersion(versions, version);
        if (rollbackTargetVersion == null) {
            warnings.add("no rollback target version was found");
        }
        int recommendedTraffic = recommendedTraffic(blockers, warnings, effectiveRollout, effectiveTraffic);
        String status = blockers.isEmpty()
                ? (warnings.isEmpty() ? "READY" : "READY_WITH_WARNINGS")
                : "BLOCKED";

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("qualityGateStatus", qualityStatus);
        evidence.put("latestRagRunId", text(qualityGate.get("latestRagRunId")));
        evidence.put("latestNl2SqlRunId", text(qualityGate.get("latestNl2SqlRunId")));
        evidence.put("baselineEvalRunId", StringUtils.hasText(baselineEvalRunId)
                ? baselineEvalRunId
                : text(ragClosure.get("baselineRunId")));
        evidence.put("ragClosureStatus", text(ragClosure.get("status")));
        evidence.put("nl2SqlContractStatus", text(nl2SqlContract.get("status")));
        evidence.put("releaseReadinessStatus", text(readiness.get("status")));
        evidence.put("rollbackTargetVersion", rollbackTargetVersion);
        evidence.put("recommendedTrafficPercent", recommendedTraffic);
        evidence.put("generatedAt", Instant.now().toString());

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("status", status);
        plan.put("publishAllowed", blockers.isEmpty());
        plan.put("promptKey", promptKey == null ? "" : promptKey);
        plan.put("targetVersion", version);
        plan.put("targetStatus", target == null ? "MISSING" : text(target.getRolloutStatus()));
        plan.put("requestedRolloutStatus", effectiveRollout);
        plan.put("requestedTrafficPercent", effectiveTraffic);
        plan.put("recommendedTrafficPercent", recommendedTraffic);
        plan.put("baselineEvalRunId", evidence.get("baselineEvalRunId"));
        plan.put("rollbackTargetVersion", rollbackTargetVersion);
        plan.put("blockers", blockers);
        plan.put("warnings", warnings);
        plan.put("evidence", evidence);
        plan.put("nextActions", nextActions(blockers, warnings));
        return plan;
    }

    public Map<String, Object> requirePublishablePlan(String promptKey,
                                                      Integer version,
                                                      String rolloutStatus,
                                                      Integer trafficPercent,
                                                      String baselineEvalRunId) {
        Map<String, Object> plan = buildPlan(promptKey, version, rolloutStatus, trafficPercent, baselineEvalRunId);
        if (!Boolean.TRUE.equals(plan.get("publishAllowed"))) {
            throw new IllegalStateException("Prompt release blocked by quality gate: " + plan.get("blockers"));
        }
        return plan;
    }

    public String releaseEvidenceJson(Map<String, Object> plan) {
        return JSON.toJSONString(plan == null ? Map.of() : plan);
    }

    private String normalizeRolloutStatus(String rolloutStatus) {
        if (!StringUtils.hasText(rolloutStatus)) {
            return "STABLE";
        }
        String normalized = rolloutStatus.trim().toUpperCase();
        if (!"STABLE".equals(normalized) && !"GRADUAL".equals(normalized)) {
            return "STABLE";
        }
        return normalized;
    }

    private Integer normalizeTraffic(String rolloutStatus, Integer trafficPercent) {
        if ("GRADUAL".equals(rolloutStatus)) {
            return Math.max(1, Math.min(99, trafficPercent == null ? 10 : trafficPercent));
        }
        return 100;
    }

    private AiPromptVersion stableActive(List<AiPromptVersion> versions) {
        return versions.stream()
                .filter(item -> Boolean.TRUE.equals(item.getActive()))
                .filter(item -> "STABLE".equals(item.getRolloutStatus()))
                .findFirst()
                .orElse(null);
    }

    private Integer rollbackTargetVersion(List<AiPromptVersion> versions, Integer targetVersion) {
        return versions.stream()
                .filter(item -> !item.getVersion().equals(targetVersion))
                .filter(item -> Boolean.TRUE.equals(item.getActive()) || "STABLE".equals(item.getRolloutStatus()))
                .map(AiPromptVersion::getVersion)
                .findFirst()
                .orElse(null);
    }

    private int recommendedTraffic(List<String> blockers,
                                   List<String> warnings,
                                   String rolloutStatus,
                                   Integer requestedTraffic) {
        if (!blockers.isEmpty()) {
            return 0;
        }
        if (!"GRADUAL".equals(rolloutStatus)) {
            return warnings.isEmpty() ? 100 : 10;
        }
        return warnings.isEmpty() ? requestedTraffic : Math.min(requestedTraffic, 10);
    }

    private List<String> nextActions(List<String> blockers, List<String> warnings) {
        if (!blockers.isEmpty()) {
            return List.of(
                    "fix blocking quality gates or baseline gaps",
                    "rerun eval suite and refresh release plan",
                    "keep rollback target available before publish");
        }
        if (!warnings.isEmpty()) {
            return List.of(
                    "publish with conservative rollout traffic",
                    "monitor bad cases and quality-gate trend after release",
                    "keep rollback record ready");
        }
        return List.of(
                "publish with recorded eval evidence",
                "monitor online bad cases",
                "rollback if quality gate regresses");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
