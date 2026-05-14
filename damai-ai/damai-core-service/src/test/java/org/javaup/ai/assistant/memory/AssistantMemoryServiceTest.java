package org.javaup.ai.assistant.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.javaup.ai.entity.AiConversationMemorySummary;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.mapper.AiConversationMemorySummaryMapper;
import org.javaup.ai.mapper.AiRunMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantMemoryServiceTest {

    @Test
    void shouldLoadLatestSummaryWhenPresent() {
        AiConversationMemorySummaryMapper summaryMapper = mock(AiConversationMemorySummaryMapper.class);
        AiRunMapper runMapper = mock(AiRunMapper.class);
        AiConversationMemorySummary summary = new AiConversationMemorySummary();
        summary.setSummary("用户关注退票规则");
        when(summaryMapper.selectOne(any(Wrapper.class))).thenReturn(summary);
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        AssistantMemoryService service = new AssistantMemoryService(summaryMapper, runMapper, chatModel);

        AssistantMemoryContext context = service.load("chat_1", 1L);

        assertTrue(context.present());
        assertEquals("用户关注退票规则", context.summary());
    }

    @Test
    void shouldLoadStructuredMemoryWhenPresent() {
        AiConversationMemorySummaryMapper summaryMapper = mock(AiConversationMemorySummaryMapper.class);
        AiRunMapper runMapper = mock(AiRunMapper.class);
        AiConversationMemorySummary summary = new AiConversationMemorySummary();
        summary.setSummary("用户关注退票规则");
        summary.setMemoryJson("""
                {"summary":"用户关注退票规则","conversationGoal":"查询退票","stableFacts":["关注退票"],"pendingQuestions":["多久到账"],"retrievalHints":["退票","到账"]}
                """);
        when(summaryMapper.selectOne(any(Wrapper.class))).thenReturn(summary);
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        AssistantMemoryService service = new AssistantMemoryService(summaryMapper, runMapper, chatModel);

        AssistantMemoryContext context = service.load("chat_1", 1L);

        assertEquals("查询退票", context.structuredMemory().conversationGoal());
        assertEquals(List.of("退票", "到账"), context.structuredMemory().retrievalHints());
    }

    @Test
    void shouldReturnEmptyMemoryWhenNoSummaryExists() {
        AiConversationMemorySummaryMapper summaryMapper = mock(AiConversationMemorySummaryMapper.class);
        AiRunMapper runMapper = mock(AiRunMapper.class);
        when(summaryMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        AssistantMemoryService service = new AssistantMemoryService(summaryMapper, runMapper, chatModel);

        AssistantMemoryContext context = service.load("chat_1", 1L);

        assertFalse(context.present());
    }

    @Test
    void shouldInsertSummaryAfterEnoughRuns() {
        AiConversationMemorySummaryMapper summaryMapper = mock(AiConversationMemorySummaryMapper.class);
        AiRunMapper runMapper = mock(AiRunMapper.class);
        when(summaryMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(runMapper.selectList(any(Wrapper.class))).thenReturn(runs(6));
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        AssistantMemoryService service = new AssistantMemoryService(summaryMapper, runMapper, chatModel);

        service.refreshAfterRun(run("run_6", "chat_1", 1L, 6));

        ArgumentCaptor<AiConversationMemorySummary> captor = ArgumentCaptor.forClass(AiConversationMemorySummary.class);
        verify(summaryMapper).insert(captor.capture());
        AiConversationMemorySummary summary = captor.getValue();
        assertEquals("chat_1", summary.getConversationId());
        assertEquals(1L, summary.getUserId());
        assertEquals("run_6", summary.getCoveredRunId());
        assertEquals(1, summary.getCompressionCount());
        assertTrue(summary.getSummary().contains("用户问题6"));
    }

    @Test
    void shouldSkipSummaryWhenRunsBelowThreshold() {
        AiConversationMemorySummaryMapper summaryMapper = mock(AiConversationMemorySummaryMapper.class);
        AiRunMapper runMapper = mock(AiRunMapper.class);
        when(summaryMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(runMapper.selectList(any(Wrapper.class))).thenReturn(runs(5));
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        AssistantMemoryService service = new AssistantMemoryService(summaryMapper, runMapper, chatModel);

        service.refreshAfterRun(run("run_5", "chat_1", 1L, 5));

        verify(summaryMapper, never()).insert(isA(AiConversationMemorySummary.class));
    }

    private List<AiRun> runs(int count) {
        List<AiRun> runs = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            runs.add(run("run_" + index, "chat_1", 1L, index));
        }
        return runs;
    }

    private AiRun run(String runId, String chatId, Long userId, int index) {
        AiRun run = new AiRun();
        run.setRunId(runId);
        run.setConversationId(chatId);
        run.setUserId(userId);
        run.setUserMessage("用户问题" + index);
        run.setResponseSummary("助手回答" + index);
        run.setCreateTime(new Date(index * 1000L));
        return run;
    }
}
