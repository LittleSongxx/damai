package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.AiPromptVersion;
import org.javaup.ai.mapper.AiPromptVersionMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptVersionService {

    private final AiPromptVersionMapper promptVersionMapper;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String resolve(String promptKey, String defaultTemplate) {
        String cached = cache.get(promptKey);
        if (cached != null) {
            return cached;
        }
        AiPromptVersion active = promptVersionMapper.selectOne(
                new LambdaQueryWrapper<AiPromptVersion>()
                        .eq(AiPromptVersion::getPromptKey, promptKey)
                        .eq(AiPromptVersion::getActive, true)
                        .eq(AiPromptVersion::getStatus, 1)
                        .orderByDesc(AiPromptVersion::getVersion)
                        .last("LIMIT 1"));
        if (active != null) {
            cache.put(promptKey, active.getTemplate());
            return active.getTemplate();
        }
        return defaultTemplate;
    }

    public void invalidateCache(String promptKey) {
        cache.remove(promptKey);
    }

    public void invalidateAll() {
        cache.clear();
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
        List<AiPromptVersion> all = promptVersionMapper.selectList(
                new LambdaQueryWrapper<AiPromptVersion>()
                        .eq(AiPromptVersion::getPromptKey, promptKey)
                        .eq(AiPromptVersion::getStatus, 1));
        for (AiPromptVersion pv : all) {
            pv.setActive(pv.getVersion().equals(version));
            pv.setEditTime(new Date());
            promptVersionMapper.updateById(pv);
        }
        invalidateCache(promptKey);
    }
}
