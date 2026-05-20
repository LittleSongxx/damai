package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.AiPromptVersion;
import org.javaup.ai.mapper.AiPromptVersionMapper;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptVersionService {

    private final AiPromptVersionMapper promptVersionMapper;
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
            cache.put(promptKey, selected.getTemplate());
            return selected.getTemplate();
        }
        return defaultTemplate;
    }

    private AiPromptVersion selectVersion(List<AiPromptVersion> versions) {
        if (versions.size() == 1) return versions.get(0);

        // Check for gradual rollout: if the latest version is in GRADUAL status,
        // split traffic based on trafficPercent
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

    public void invalidateCache(String promptKey) {
        cache.invalidate(promptKey);
        broadcastInvalidation(promptKey);
    }

    public void invalidateAll() {
        cache.invalidateAll();
        broadcastInvalidation("ALL");
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

    public AiPromptVersion create(String promptKey, String template, String description, Long userId) {
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
        version.setActive(true);
        version.setCreatedBy(userId);
        version.setCreateTime(new Date());
        version.setEditTime(new Date());
        version.setStatus(1);
        promptVersionMapper.insert(version);
        invalidateCache(promptKey);
        return version;
    }

    public void activate(String promptKey, Integer version) {
        activate(promptKey, version, null, null);
    }

    public void activate(String promptKey, Integer version, String rolloutStatus, Integer trafficPercent) {
        List<AiPromptVersion> all = promptVersionMapper.selectList(
                new LambdaQueryWrapper<AiPromptVersion>()
                        .eq(AiPromptVersion::getPromptKey, promptKey)
                        .eq(AiPromptVersion::getStatus, 1));
        String effectiveRollout = rolloutStatus != null ? rolloutStatus : "STABLE";
        Integer effectiveTraffic = "GRADUAL".equals(effectiveRollout) && trafficPercent != null
                ? Math.max(1, Math.min(99, trafficPercent)) : 100;

        for (AiPromptVersion pv : all) {
            boolean isTarget = pv.getVersion().equals(version);
            pv.setActive(isTarget);
            pv.setRolloutStatus(isTarget ? effectiveRollout : null);
            pv.setTrafficPercent(isTarget ? effectiveTraffic : null);
            pv.setEditTime(new Date());
            promptVersionMapper.updateById(pv);
        }
        invalidateCache(promptKey);
        log.info("Prompt activated: key={}, version={}, rollout={}, traffic={}%",
                promptKey, version, effectiveRollout, effectiveTraffic);
    }

    public void promoteToStable(String promptKey, Integer version) {
        AiPromptVersion pv = promptVersionMapper.selectOne(
                new LambdaQueryWrapper<AiPromptVersion>()
                        .eq(AiPromptVersion::getPromptKey, promptKey)
                        .eq(AiPromptVersion::getVersion, version));
        if (pv != null) {
            pv.setRolloutStatus("STABLE");
            pv.setTrafficPercent(100);
            pv.setEditTime(new Date());
            promptVersionMapper.updateById(pv);
            invalidateCache(promptKey);
        }
    }

    public void rollback(String promptKey, Integer version) {
        List<AiPromptVersion> all = promptVersionMapper.selectList(
                new LambdaQueryWrapper<AiPromptVersion>()
                        .eq(AiPromptVersion::getPromptKey, promptKey)
                        .eq(AiPromptVersion::getStatus, 1));
        AiPromptVersion target = all.stream()
                .filter(pv -> pv.getVersion().equals(version))
                .findFirst().orElse(null);
        if (target != null) {
            for (AiPromptVersion pv : all) {
                pv.setActive(pv.getVersion().equals(version));
                pv.setRolloutStatus(pv.getVersion().equals(version) ? "STABLE" : null);
                pv.setTrafficPercent(pv.getVersion().equals(version) ? 100 : null);
                pv.setEditTime(new Date());
                promptVersionMapper.updateById(pv);
            }
            invalidateCache(promptKey);
        }
    }
}
