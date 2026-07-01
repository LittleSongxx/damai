package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.entity.AiPromptReleaseRecord;
import org.javaup.ai.entity.AiPromptVersion;
import org.javaup.ai.service.PromptReleasePlanService;
import org.javaup.ai.service.PromptVersionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/prompt-versions")
@RequiredArgsConstructor
public class PromptVersionController {

    private final PromptVersionService promptVersionService;
    private final PromptReleasePlanService promptReleasePlanService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String promptKey) {
        List<AiPromptVersion> versions = promptVersionService.list(promptKey);
        return ResponseEntity.ok(Map.of("code", 0, "data", versions));
    }

    @GetMapping("/release-records")
    public ResponseEntity<Map<String, Object>> listReleaseRecords(@RequestParam(required = false) String promptKey) {
        List<AiPromptReleaseRecord> records = promptVersionService.listReleaseRecords(promptKey);
        return ResponseEntity.ok(Map.of("code", 0, "data", records));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String promptKey = (String) body.get("promptKey");
        String template = (String) body.get("template");
        String description = (String) body.get("description");
        Long userId = currentUserId();
        AiPromptVersion version = promptVersionService.create(promptKey, template, description, userId);
        return ResponseEntity.ok(Map.of("code", 0, "data", version));
    }

    @PostMapping("/activate")
    public ResponseEntity<Map<String, Object>> activate(@RequestBody Map<String, Object> body) {
        String promptKey = (String) body.get("promptKey");
        Integer version = (Integer) body.get("version");
        promptVersionService.activate(promptKey, version);
        return ResponseEntity.ok(Map.of("code", 0, "message", "activated"));
    }

    @PostMapping("/publish")
    public ResponseEntity<Map<String, Object>> publish(@RequestBody Map<String, Object> body) {
        String promptKey = stringValue(body, "promptKey");
        Integer version = intValue(body, "version");
        String rolloutStatus = stringValue(body, "rolloutStatus");
        Integer trafficPercent = intValue(body, "trafficPercent");
        String baselineEvalRunId = stringValue(body, "baselineEvalRunId");
        Map<String, Object> releasePlan = promptReleasePlanService.buildPlan(
                promptKey, version, rolloutStatus, trafficPercent, baselineEvalRunId);
        ResponseEntity<Map<String, Object>> blocked = blockedReleaseResponse(releasePlan);
        if (blocked != null) {
            return blocked;
        }
        AiPromptVersion published = promptVersionService.publish(promptKey, version,
                rolloutStatus,
                trafficPercent,
                baselineEvalRunId,
                stringValue(body, "releaseNote"),
                promptReleasePlanService.releaseEvidenceJson(releasePlan),
                currentUserId());
        return ResponseEntity.ok(Map.of("code", 0, "data", published, "releasePlan", releasePlan, "message", "published"));
    }

    @PostMapping("/release-plan")
    public ResponseEntity<Map<String, Object>> releasePlan(@RequestBody Map<String, Object> body) {
        Map<String, Object> plan = promptReleasePlanService.buildPlan(
                stringValue(body, "promptKey"),
                intValue(body, "version"),
                stringValue(body, "rolloutStatus"),
                intValue(body, "trafficPercent"),
                stringValue(body, "baselineEvalRunId"));
        return ResponseEntity.ok(Map.of("code", 0, "data", plan));
    }

    @PostMapping("/promote")
    public ResponseEntity<Map<String, Object>> promote(@RequestBody Map<String, Object> body) {
        String promptKey = stringValue(body, "promptKey");
        Integer version = intValue(body, "version");
        String baselineEvalRunId = stringValue(body, "baselineEvalRunId");
        Map<String, Object> releasePlan = promptReleasePlanService.buildPlan(
                promptKey, version, "STABLE", 100, baselineEvalRunId);
        ResponseEntity<Map<String, Object>> blocked = blockedReleaseResponse(releasePlan);
        if (blocked != null) {
            return blocked;
        }
        AiPromptVersion promoted = promptVersionService.promoteToStable(promptKey, version,
                baselineEvalRunId,
                stringValue(body, "releaseNote"),
                promptReleasePlanService.releaseEvidenceJson(releasePlan),
                currentUserId());
        return ResponseEntity.ok(Map.of("code", 0, "data", promoted, "releasePlan", releasePlan, "message", "promoted"));
    }

    @PostMapping("/rollback")
    public ResponseEntity<Map<String, Object>> rollback(@RequestBody Map<String, Object> body) {
        String promptKey = stringValue(body, "promptKey");
        Integer version = intValue(body, "version");
        Map<String, Object> releasePlan = promptReleasePlanService.buildPlan(
                promptKey, version, "STABLE", 100, stringValue(body, "baselineEvalRunId"));
        AiPromptVersion rolledBack = promptVersionService.rollback(promptKey, version,
                stringValue(body, "reason"),
                promptReleasePlanService.releaseEvidenceJson(releasePlan),
                currentUserId());
        return ResponseEntity.ok(Map.of("code", 0, "data", rolledBack, "releasePlan", releasePlan, "message", "rolled back"));
    }

    @PostMapping("/invalidate-cache")
    public ResponseEntity<Map<String, Object>> invalidateCache() {
        promptVersionService.invalidateAll();
        return ResponseEntity.ok(Map.of("code", 0, "message", "cache invalidated"));
    }

    private Long currentUserId() {
        return AiRequestContextHolder.getOptional()
                .map(context -> context.getUser() == null ? null : context.getUser().getUserId())
                .orElse(null);
    }

    private String stringValue(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Integer intValue(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private ResponseEntity<Map<String, Object>> blockedReleaseResponse(Map<String, Object> releasePlan) {
        if (Boolean.TRUE.equals(releasePlan.get("publishAllowed"))) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", -100,
                "message", "Prompt release blocked by quality gate",
                "releasePlan", releasePlan));
    }
}
