package org.javaup.ai.service;

import org.javaup.ai.dto.CustomerQuickAnswerRequest;
import org.javaup.ai.entity.CustomerWorkItem;
import org.javaup.ai.mapper.AiCustomerHotQuestionMapper;
import org.javaup.ai.vo.CustomerQuickAnswerResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerHotQuestionServiceTest {

    @Test
    void cachedHotQuestionShouldReturnDirectAnswerWithoutRuntimeFallback() {
        AiCustomerHotQuestionMapper hotQuestionMapper = mock(AiCustomerHotQuestionMapper.class);
        SentimentAnalysisService sentimentService = mock(SentimentAnalysisService.class);
        CustomerIntentResolver intentResolver = new CustomerIntentResolver();
        CustomerWorkItemService workItemService = mock(CustomerWorkItemService.class);
        CustomerServiceMetricsService metricsService = mock(CustomerServiceMetricsService.class);
        when(hotQuestionMapper.selectList(any())).thenReturn(java.util.List.of());
        when(sentimentService.quickAnalyze(anyString(), isNull(), anyString(), anyLong()))
                .thenReturn(new SentimentAnalysisService.SentimentResult("NEUTRAL", 0D, false, java.util.List.of(), false, null));
        CustomerHotQuestionService service = new CustomerHotQuestionService(
                hotQuestionMapper, sentimentService, intentResolver, workItemService, metricsService);

        CustomerQuickAnswerRequest request = new CustomerQuickAnswerRequest();
        request.setChatId("chat-1");
        request.setMessage("节目开演前还能退票吗");
        request.setHotQuestionId("refund-rule");
        CustomerQuickAnswerResponse response = service.quickAnswer(request, 7L);

        assertTrue(response.getHit());
        assertEquals("CACHED_ANSWER", response.getAnswerMode());
        assertEquals("REFUND_RULE", response.getIntentCode());
        assertTrue(response.getDirectAnswer().contains("退票"));
        assertFalse(response.getSourceRefs().isEmpty());
        assertEquals("customer_service", response.getClientContext().get("scene"));
        verify(workItemService, never()).handoff(any(), anyLong());
        verify(metricsService).record(isNull(), anyString(), anyLong(),
                org.mockito.ArgumentMatchers.eq(CustomerServiceMetricsService.QUICK_ANSWER_HIT),
                anyDouble(), anyLong(), anyMap());
    }

    @Test
    void unknownQuestionShouldReturnAssistantRuntimeContextAndClarificationButtons() {
        AiCustomerHotQuestionMapper hotQuestionMapper = mock(AiCustomerHotQuestionMapper.class);
        SentimentAnalysisService sentimentService = mock(SentimentAnalysisService.class);
        CustomerIntentResolver intentResolver = new CustomerIntentResolver();
        CustomerWorkItemService workItemService = mock(CustomerWorkItemService.class);
        CustomerServiceMetricsService metricsService = mock(CustomerServiceMetricsService.class);
        when(hotQuestionMapper.selectList(any())).thenReturn(java.util.List.of());
        when(sentimentService.quickAnalyze(anyString(), isNull(), anyString(), anyLong()))
                .thenReturn(new SentimentAnalysisService.SentimentResult("NEUTRAL", 0D, false, java.util.List.of(), false, null));
        CustomerHotQuestionService service = new CustomerHotQuestionService(
                hotQuestionMapper, sentimentService, intentResolver, workItemService, metricsService);

        CustomerQuickAnswerRequest request = new CustomerQuickAnswerRequest();
        request.setChatId("chat-2");
        request.setMessage("帮我比较一下两个城市的演出体验");
        CustomerQuickAnswerResponse response = service.quickAnswer(request, 8L);

        assertFalse(response.getHit());
        assertEquals("RUN_ASSISTANT", response.getAnswerMode());
        assertEquals("customer_service", response.getClientContext().get("scene"));
        assertNotNull(response.getClientContext().get("intentHint"));
        assertFalse(response.getActionButtons().isEmpty());
        verify(metricsService).record(isNull(), anyString(), anyLong(),
                org.mockito.ArgumentMatchers.eq(CustomerServiceMetricsService.QUICK_ANSWER_MISS),
                anyDouble(), anyLong(), anyMap());
    }

    @Test
    void handoffHotQuestionShouldCreateWorkItem() {
        AiCustomerHotQuestionMapper hotQuestionMapper = mock(AiCustomerHotQuestionMapper.class);
        SentimentAnalysisService sentimentService = mock(SentimentAnalysisService.class);
        CustomerIntentResolver intentResolver = new CustomerIntentResolver();
        CustomerWorkItemService workItemService = mock(CustomerWorkItemService.class);
        CustomerServiceMetricsService metricsService = mock(CustomerServiceMetricsService.class);
        when(hotQuestionMapper.selectList(any())).thenReturn(java.util.List.of());
        when(sentimentService.quickAnalyze(anyString(), isNull(), anyString(), anyLong()))
                .thenReturn(new SentimentAnalysisService.SentimentResult("NEGATIVE", 0.9D, true,
                        java.util.List.of("crisis_keyword"), true, "命中投诉/维权高危词"));
        CustomerWorkItem item = new CustomerWorkItem();
        item.setWorkItemId("work-item-1");
        when(workItemService.handoff(any(), anyLong())).thenReturn(item);
        CustomerHotQuestionService service = new CustomerHotQuestionService(
                hotQuestionMapper, sentimentService, intentResolver, workItemService, metricsService);

        CustomerQuickAnswerRequest request = new CustomerQuickAnswerRequest();
        request.setChatId("chat-3");
        request.setMessage("我要投诉并转人工");
        request.setHotQuestionId("human-handoff");
        CustomerQuickAnswerResponse response = service.quickAnswer(request, 9L);

        assertTrue(response.getHit());
        assertEquals("work-item-1", response.getWorkItem().getWorkItemId());
        assertTrue(String.valueOf(response.getSentiment().get("emotionTags")).contains("crisis_keyword"));
        verify(workItemService).handoff(any(), org.mockito.ArgumentMatchers.eq(9L));
        verify(metricsService).record(isNull(), anyString(), anyLong(),
                org.mockito.ArgumentMatchers.eq(CustomerServiceMetricsService.WORK_ITEM_CREATED),
                anyDouble(), anyLong(), anyMap());
    }
}
