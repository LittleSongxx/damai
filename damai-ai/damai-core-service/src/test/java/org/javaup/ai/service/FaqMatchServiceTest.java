package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import org.javaup.ai.cache.EmbeddingCacheService;
import org.javaup.ai.cache.FaqSearchCacheService;
import org.javaup.ai.entity.FaqEntry;
import org.javaup.ai.mapper.FaqEntryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiEmbeddingModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FaqMatchServiceTest {

    private final FaqEntryMapper faqEntryMapper = mock(FaqEntryMapper.class);
    private final EmbeddingCacheService embeddingCacheService = mock(EmbeddingCacheService.class);
    private final FaqSearchCacheService faqSearchCacheService = mock(FaqSearchCacheService.class);
    private final FaqMatchService service = new FaqMatchService(faqEntryMapper,
            mock(OpenAiEmbeddingModel.class), null, embeddingCacheService, faqSearchCacheService);

    @Test
    void shouldReturnCachedFaqMatchBeforeSearchingDatabase() {
        FaqMatchService.FaqMatchResult cached = new FaqMatchService.FaqMatchResult(
                "faq-1", "退票规则", "可在订单页申请退票", "ticket",
                "keyword", 0.9D, List.of("faq:faq-1"), "1", null, java.util.Map.of());
        when(faqSearchCacheService.get("退票规则")).thenReturn(JSON.toJSONString(cached));

        FaqMatchService.FaqMatchResult result = service.match(" 退 票 规 则 ");

        assertEquals("faq-1", result.faqId());
        assertEquals("keyword", result.matchMethod());
        verifyNoInteractions(faqEntryMapper);
        verify(faqSearchCacheService, never()).put(any(), any());
    }

    @Test
    void shouldCacheKeywordMatchResultAfterMiss() {
        FaqEntry entry = faq("faq-2", "如何退票", "退票", "退票,订单,申请");
        when(faqSearchCacheService.get("如何退票")).thenReturn(null);
        when(faqEntryMapper.selectList(any())).thenReturn(List.of(entry));

        FaqMatchService.FaqMatchResult result = service.match("如何退票");

        assertEquals("faq-2", result.faqId());
        assertEquals("keyword", result.matchMethod());
        verify(faqSearchCacheService).put(eq("如何退票"), contains("faq-2"));
        verify(faqEntryMapper).updateById(entry);
    }

    @Test
    void shouldInvalidateFaqCacheWhenIndexChanges() {
        service.deleteFaqIndex("faq-3");

        verify(faqSearchCacheService).invalidate();
    }

    private FaqEntry faq(String faqId, String question, String answer, String keywords) {
        FaqEntry entry = new FaqEntry();
        entry.setId(1L);
        entry.setFaqId(faqId);
        entry.setQuestion(question);
        entry.setAnswer(answer);
        entry.setCategory("ticket");
        entry.setKeywords(keywords);
        entry.setEnabled(1);
        entry.setPriority(10);
        entry.setHitCount(0);
        return entry;
    }
}
