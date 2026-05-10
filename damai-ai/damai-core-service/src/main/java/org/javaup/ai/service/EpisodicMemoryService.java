package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.AiEpisodicMemory;
import org.javaup.ai.mapper.AiEpisodicMemoryMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EpisodicMemoryService {

    private final AiEpisodicMemoryMapper memoryMapper;

    public void save(Long userId, String eventType, String entityJson, String summary, String runId) {
        AiEpisodicMemory memory = new AiEpisodicMemory();
        memory.setUserId(userId);
        memory.setEventType(eventType);
        memory.setEntityJson(entityJson);
        memory.setSummary(summary);
        memory.setWeight(1.0);
        memory.setRunId(runId);
        memory.setCreateTime(new Date());
        memory.setEditTime(new Date());
        memory.setStatus(1);
        memoryMapper.insert(memory);
    }

    public List<AiEpisodicMemory> getRecent(Long userId, int limit) {
        return memoryMapper.selectList(
                new LambdaQueryWrapper<AiEpisodicMemory>()
                        .eq(AiEpisodicMemory::getUserId, userId)
                        .eq(AiEpisodicMemory::getStatus, 1)
                        .orderByDesc(AiEpisodicMemory::getCreateTime)
                        .last("LIMIT " + limit));
    }

    public void decayWeights(Long userId, double decayFactor) {
        List<AiEpisodicMemory> memories = memoryMapper.selectList(
                new LambdaQueryWrapper<AiEpisodicMemory>()
                        .eq(AiEpisodicMemory::getUserId, userId)
                        .eq(AiEpisodicMemory::getStatus, 1));
        for (AiEpisodicMemory m : memories) {
            m.setWeight(m.getWeight() * decayFactor);
            m.setEditTime(new Date());
            if (m.getWeight() < 0.01) {
                m.setStatus(0);
            }
            memoryMapper.updateById(m);
        }
    }
}
