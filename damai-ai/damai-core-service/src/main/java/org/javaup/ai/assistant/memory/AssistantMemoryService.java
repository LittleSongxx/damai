package org.javaup.ai.assistant.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiConversationMemorySummary;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.mapper.AiConversationMemorySummaryMapper;
import org.javaup.ai.mapper.AiRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AssistantMemoryService {

    private static final int COMPRESSION_TRIGGER_RUNS = 6;
    private static final int RECENT_RUN_LIMIT = 8;
    private static final int SUMMARY_CHAR_LIMIT = 1200;

    private final AiConversationMemorySummaryMapper memorySummaryMapper;
    private final AiRunMapper runMapper;

    public AssistantMemoryContext load(String conversationId, Long userId) {
        AiConversationMemorySummary summary = latestSummary(conversationId, userId);
        if (summary == null || !StringUtils.hasText(summary.getSummary())) {
            return AssistantMemoryContext.empty();
        }
        return new AssistantMemoryContext(summary.getSummary(), true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void refreshAfterRun(AiRun run) {
        if (run == null || !StringUtils.hasText(run.getConversationId()) || run.getUserId() == null) {
            return;
        }
        List<AiRun> recentRuns = recentCompletedRuns(run.getConversationId(), run.getUserId());
        if (recentRuns.size() < COMPRESSION_TRIGGER_RUNS) {
            return;
        }
        AiConversationMemorySummary latest = latestSummary(run.getConversationId(), run.getUserId());
        if (latest != null && run.getRunId().equals(latest.getCoveredRunId())) {
            return;
        }
        String summaryText = buildSummary(recentRuns);
        if (!StringUtils.hasText(summaryText)) {
            return;
        }
        AiConversationMemorySummary summary = new AiConversationMemorySummary();
        summary.setConversationId(run.getConversationId());
        summary.setUserId(run.getUserId());
        summary.setCoveredRunId(run.getRunId());
        summary.setSummary(summaryText);
        summary.setCompressionCount(latest == null ? 1 : latest.getCompressionCount() + 1);
        summary.setStatus(1);
        memorySummaryMapper.insert(summary);
    }

    private AiConversationMemorySummary latestSummary(String conversationId, Long userId) {
        return memorySummaryMapper.selectOne(Wrappers.lambdaQuery(AiConversationMemorySummary.class)
                .eq(AiConversationMemorySummary::getConversationId, conversationId)
                .eq(AiConversationMemorySummary::getUserId, userId)
                .eq(AiConversationMemorySummary::getStatus, 1)
                .orderByDesc(AiConversationMemorySummary::getCreateTime)
                .last("limit 1"));
    }

    private List<AiRun> recentCompletedRuns(String conversationId, Long userId) {
        return runMapper.selectList(Wrappers.lambdaQuery(AiRun.class)
                .eq(AiRun::getConversationId, conversationId)
                .eq(AiRun::getUserId, userId)
                .eq(AiRun::getStatus, 1)
                .orderByDesc(AiRun::getCreateTime)
                .last("limit " + RECENT_RUN_LIMIT));
    }

    private String buildSummary(List<AiRun> recentRuns) {
        String summary = recentRuns.stream()
                .filter(run -> StringUtils.hasText(run.getUserMessage()) || StringUtils.hasText(run.getResponseSummary()))
                .sorted(java.util.Comparator.comparing(AiRun::getCreateTime, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(run -> "用户：" + safe(run.getUserMessage()) + "\n助手：" + safe(run.getResponseSummary()))
                .collect(Collectors.joining("\n---\n"));
        if (summary.length() <= SUMMARY_CHAR_LIMIT) {
            return summary;
        }
        return summary.substring(summary.length() - SUMMARY_CHAR_LIMIT);
    }

    private String safe(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }
}
