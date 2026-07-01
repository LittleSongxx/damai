package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.AiPromptReleaseRecord;
import org.javaup.ai.entity.AiPromptVersion;
import org.javaup.ai.mapper.AiPromptReleaseRecordMapper;
import org.javaup.ai.mapper.AiPromptVersionMapper;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptVersionService {

    private final AiPromptVersionMapper promptVersionMapper;
    private final AiPromptReleaseRecordMapper releaseRecordMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    private final Cache<String, String> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    public String resolve(String promptKey, String defaultTemplate) {
        String cached = cache.getIfPresent(promptKey);
        if (cached != null) {
            return cached;
        }
        List<AiPromptVersion> activeVersions = promptVersionMapper.selectList(
                new LambdaQueryWrapper<AiPromptVersion>()
                        .eq(AiPromptVersion::getPromptKey, promptKey)
                        .eq(AiPromptVersion::getActive, true)
                        .eq(AiPromptVersion::getStatus, 1)
                        .orderByDesc(AiPromptVersion::getVersion));
        if (activeVersions.isEmpty()) {
            return defaultTemplate;
        }
        AiPromptVersion selected = selectVersion(activeVersions);
        if (selected != null) {
            if (!hasGradualRollout(activeVersions)) {
                cache.put(promptKey, selected.getTemplate());
            }
            return selected.getTemplate();
        }
        return defaultTemplate;
    }

    private AiPromptVersion selectVersion(List<AiPromptVersion> versions) {
        if (versions.size() == 1) return versions.get(0);

        AiPromptVersion latest = versions.get(0);
        AiPromptVersion previousStable = null;
        for (int i = 1; i < versions.size(); i++) {
            if ("STABLE".equals(versions.get(i).getRolloutStatus())) {
                previousStable = versions.get(i);
                break;
            }
        }

        if ("GRADUAL".equals(latest.getRolloutStatus()) && previousStable != null
                && latest.getTrafficPercent() != null && latest.getTrafficPercent() > 0
                && latest.getTrafficPercent() < 100) {
            double roll = Math.random() * 100;
            if (roll < latest.getTrafficPercent()) {
                return latest;
            }
            return previousStable;
        }
        return latest;
    }

    private boolean hasGradualRollout(List<AiPromptVersion> versions) {
        return versions.stream().anyMatch(version -> "GRADUAL".equals(version.getRolloutStatus())
                && version.getTrafficPercent() != null
                && version.getTrafficPercent() > 0
                && version.getTrafficPercent() < 100);
    }

    public void invalidateCache(String promptKey) {
        invalidateCacheLocal(promptKey);
        broadcastInvalidation(promptKey);
    }

    public void invalidateAll() {
        invalidateAllLocal();
        broadcastInvalidation("ALL");
    }

    public void invalidateCacheLocal(String promptKey) {
        if ("ALL".equalsIgnoreCase(promptKey)) {
            invalidateAllLocal();
            return;
        }
        cache.invalidate(promptKey);
    }

    public void invalidateAllLocal() {
        cache.invalidateAll();
    }

    private void broadcastInvalidation(String promptKey) {
        try {
            redisTemplate.convertAndSend("damai:cache:invalidation", "prompt:" + promptKey);
        } catch (Exception e) {
            log.warn("Failed to broadcast prompt cache invalidation: {}", e.getMessage());
        }
    }

    public List<AiPromptVersion> list(String promptKey) {
        return promptVersionMapper.selectList(
                new LambdaQueryWrapper<AiPromptVersion>()
                        .eq(promptKey != null, AiPromptVersion::getPromptKey, promptKey)
                        .eq(AiPromptVersion::getStatus, 1)
                        .orderByDesc(AiPromptVersion::getPromptKey)
                        .orderByDesc(AiPromptVersion::getVersion));
    }

    public List<AiPromptReleaseRecord> listReleaseRecords(String promptKey) {
        return releaseRecordMapper.selectList(
                new LambdaQueryWrapper<AiPromptReleaseRecord>()
                        .eq(StringUtils.hasText(promptKey), AiPromptReleaseRecord::getPromptKey, promptKey)
                        .eq(AiPromptReleaseRecord::getStatus, 1)
                        .orderByDesc(AiPromptReleaseRecord::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public AiPromptVersion create(String promptKey, String template, String description, Long userId) {
        validateRequired(promptKey, template);
        Integer maxVersion = promptVersionMapper.selectList(
                new LambdaQueryWrapper<AiPromptVersion>()
                        .eq(AiPromptVersion::getPromptKey, promptKey)
                        .eq(AiPromptVersion::getStatus, 1)
                        .orderByDesc(AiPromptVersion::getVersion)
                        .last("LIMIT 1"))
                .stream().findFirst().map(AiPromptVersion::getVersion).orElse(0);

        AiPromptVersion version = new AiPromptVersion();
        version.setPromptKey(promptKey);
        version.setVersion(maxVersion + 1);
        version.setTemplate(template);
        version.setDescription(description);
        version.setActive(false);
        version.setRolloutStatus("DRAFT");
        version.setTrafficPercent(0);
        version.setCreatedBy(userId);
        version.setCreateTime(new Date());
        version.setEditTime(new Date());
        version.setStatus(1);
        promptVersionMapper.insert(version);
        invalidateCache(promptKey);
        return version;
    }

    public void activate(String promptKey, Integer version) {
        publish(promptKey, version, "STABLE", 100, null, null, null);
    }

    public void activate(String promptKey, Integer version, String rolloutStatus, Integer trafficPercent) {
        publish(promptKey, version, rolloutStatus, trafficPercent, null, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiPromptVersion publish(String promptKey,
                                   Integer version,
                                   String rolloutStatus,
                                   Integer trafficPercent,
                                   String baselineEvalRunId,
                                   String releaseNote,
                                   Long operatorId) {
        return publish(promptKey, version, rolloutStatus, trafficPercent, baselineEvalRunId,
                releaseNote, null, operatorId);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiPromptVersion publish(String promptKey,
                                   Integer version,
                                   String rolloutStatus,
                                   Integer trafficPercent,
                                   String baselineEvalRunId,
                                   String releaseNote,
                                   String releaseEvidenceJson,
                                   Long operatorId) {
        List<AiPromptVersion> all = versionsForUpdate(promptKey);
        AiPromptVersion target = findRequired(all, version);
        AiPromptVersion previousActive = currentActive(all);
        String effectiveRollout = normalizeRolloutStatus(rolloutStatus);
        Integer effectiveTraffic = normalizeTraffic(effectiveRollout, trafficPercent);
        if ("GRADUAL".equals(effectiveRollout) && all.stream().noneMatch(this::isStableActive)) {
            throw new IllegalStateException("Gradual rollout requires an existing stable prompt version");
        }

        for (AiPromptVersion pv : all) {
            boolean isTarget = pv.getVersion().equals(version);
            if ("GRADUAL".equals(effectiveRollout)) {
                pv.setActive(isTarget || isStableActive(pv));
                if (isTarget) {
                    pv.setRolloutStatus("GRADUAL");
                    pv.setTrafficPercent(effectiveTraffic);
                } else if (isStableActive(pv)) {
                    pv.setRolloutStatus("STABLE");
                    pv.setTrafficPercent(100 - effectiveTraffic);
                } else {
                    pv.setRolloutStatus("SUPERSEDED");
                    pv.setTrafficPercent(0);
                }
            } else {
                pv.setActive(isTarget);
                pv.setRolloutStatus(isTarget ? "STABLE" : "SUPERSEDED");
                pv.setTrafficPercent(isTarget ? 100 : 0);
            }
            if (isTarget) {
                pv.setBaselineEvalRunId(blankToNull(baselineEvalRunId));
                pv.setReleaseNote(blankToNull(releaseNote));
                pv.setReleasedBy(operatorId);
                pv.setReleasedAt(new Date());
                pv.setRollbackFromVersion(null);
                pv.setRollbackReason(null);
            }
            pv.setEditTime(new Date());
            promptVersionMapper.updateById(pv);
        }
        recordRelease(promptKey, previousActive == null ? null : previousActive.getVersion(), version,
                "PUBLISH", effectiveRollout, effectiveTraffic, baselineEvalRunId, releaseNote,
                releaseEvidenceJson, null, operatorId);
        invalidateCache(promptKey);
        log.info("Prompt published: key={}, version={}, rollout={}, traffic={}%",
                promptKey, version, effectiveRollout, effectiveTraffic);
        return promptVersionMapper.selectById(target.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void promoteToStable(String promptKey, Integer version) {
        publish(promptKey, version, "STABLE", 100, null, "promote gradual release to stable", null);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiPromptVersion promoteToStable(String promptKey, Integer version, String releaseNote, Long operatorId) {
        return publish(promptKey, version, "STABLE", 100, null, releaseNote, null, operatorId);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiPromptVersion promoteToStable(String promptKey,
                                           Integer version,
                                           String releaseNote,
                                           String releaseEvidenceJson,
                                           Long operatorId) {
        return publish(promptKey, version, "STABLE", 100, null, releaseNote, releaseEvidenceJson, operatorId);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiPromptVersion promoteToStable(String promptKey,
                                           Integer version,
                                           String baselineEvalRunId,
                                           String releaseNote,
                                           String releaseEvidenceJson,
                                           Long operatorId) {
        return publish(promptKey, version, "STABLE", 100, baselineEvalRunId, releaseNote, releaseEvidenceJson, operatorId);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiPromptVersion rollback(String promptKey, Integer version, String reason, Long operatorId) {
        return rollback(promptKey, version, reason, null, operatorId);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiPromptVersion rollback(String promptKey,
                                    Integer version,
                                    String reason,
                                    String releaseEvidenceJson,
                                    Long operatorId) {
        List<AiPromptVersion> all = versionsForUpdate(promptKey);
        AiPromptVersion target = findRequired(all, version);
        AiPromptVersion previousActive = currentActive(all);
        for (AiPromptVersion pv : all) {
            boolean isTarget = pv.getVersion().equals(version);
            pv.setActive(isTarget);
            pv.setRolloutStatus(isTarget ? "STABLE" : "ROLLED_BACK");
            pv.setTrafficPercent(isTarget ? 100 : 0);
            if (isTarget) {
                pv.setReleasedBy(operatorId);
                pv.setReleasedAt(new Date());
                pv.setRollbackFromVersion(previousActive == null ? null : String.valueOf(previousActive.getVersion()));
                pv.setRollbackReason(blankToNull(reason));
            }
            pv.setEditTime(new Date());
            promptVersionMapper.updateById(pv);
        }
        recordRelease(promptKey, previousActive == null ? null : previousActive.getVersion(), version,
                "ROLLBACK", "STABLE", 100, target.getBaselineEvalRunId(), target.getReleaseNote(),
                releaseEvidenceJson, reason, operatorId);
        invalidateCache(promptKey);
        return promptVersionMapper.selectById(target.getId());
    }

    public void rollback(String promptKey, Integer version) {
        rollback(promptKey, version, null, null);
    }

    private List<AiPromptVersion> versionsForUpdate(String promptKey) {
        if (!StringUtils.hasText(promptKey)) {
            throw new IllegalArgumentException("promptKey is required");
        }
        return promptVersionMapper.selectList(
                new LambdaQueryWrapper<AiPromptVersion>()
                        .eq(AiPromptVersion::getPromptKey, promptKey)
                        .eq(AiPromptVersion::getStatus, 1)
                        .orderByDesc(AiPromptVersion::getVersion));
    }

    private AiPromptVersion findRequired(List<AiPromptVersion> versions, Integer version) {
        if (version == null) {
            throw new IllegalArgumentException("version is required");
        }
        return versions.stream()
                .filter(pv -> version.equals(pv.getVersion()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Prompt version does not exist: " + version));
    }

    private AiPromptVersion currentActive(List<AiPromptVersion> versions) {
        return versions.stream()
                .filter(pv -> Boolean.TRUE.equals(pv.getActive()))
                .filter(pv -> "STABLE".equals(pv.getRolloutStatus()) || "GRADUAL".equals(pv.getRolloutStatus()))
                .findFirst()
                .orElse(null);
    }

    private boolean isStableActive(AiPromptVersion version) {
        return Boolean.TRUE.equals(version.getActive()) && "STABLE".equals(version.getRolloutStatus());
    }

    private String normalizeRolloutStatus(String rolloutStatus) {
        if (!StringUtils.hasText(rolloutStatus)) {
            return "STABLE";
        }
        String normalized = rolloutStatus.trim().toUpperCase();
        if (!"STABLE".equals(normalized) && !"GRADUAL".equals(normalized)) {
            throw new IllegalArgumentException("rolloutStatus only supports STABLE or GRADUAL");
        }
        return normalized;
    }

    private Integer normalizeTraffic(String rolloutStatus, Integer trafficPercent) {
        if ("GRADUAL".equals(rolloutStatus)) {
            return Math.max(1, Math.min(99, trafficPercent == null ? 10 : trafficPercent));
        }
        return 100;
    }

    private void validateRequired(String promptKey, String template) {
        if (!StringUtils.hasText(promptKey)) {
            throw new IllegalArgumentException("promptKey is required");
        }
        if (!StringUtils.hasText(template)) {
            throw new IllegalArgumentException("template is required");
        }
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void recordRelease(String promptKey,
                               Integer fromVersion,
                               Integer toVersion,
                               String actionType,
                               String rolloutStatus,
                               Integer trafficPercent,
                               String baselineEvalRunId,
                               String releaseNote,
                               String releaseEvidenceJson,
                               String rollbackReason,
                               Long operatorId) {
        AiPromptReleaseRecord record = new AiPromptReleaseRecord();
        record.setReleaseId("prompt_release_" + UUID.randomUUID().toString().replace("-", ""));
        record.setPromptKey(promptKey);
        record.setFromVersion(fromVersion);
        record.setToVersion(toVersion);
        record.setActionType(actionType);
        record.setRolloutStatus(rolloutStatus);
        record.setTrafficPercent(trafficPercent);
        record.setBaselineEvalRunId(blankToNull(baselineEvalRunId));
        record.setReleaseNote(blankToNull(releaseNote));
        record.setReleaseEvidenceJson(blankToNull(releaseEvidenceJson));
        record.setRollbackReason(blankToNull(rollbackReason));
        record.setOperatorId(operatorId);
        record.setCreateTime(new Date());
        record.setEditTime(new Date());
        record.setStatus(1);
        releaseRecordMapper.insert(record);
    }
}
