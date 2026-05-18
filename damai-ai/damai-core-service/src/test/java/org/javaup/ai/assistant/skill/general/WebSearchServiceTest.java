package org.javaup.ai.assistant.skill.general;

import org.javaup.ai.cache.CacheManager;
import org.javaup.ai.config.CacheProperties;
import org.javaup.ai.config.WebSearchProperties;
import org.javaup.ai.resilience.CircuitBreakerService;
import org.javaup.ai.resilience.DegradationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSearchServiceTest {

    private CacheManager cacheManager;
    private CircuitBreakerService circuitBreakerService;
    private DegradationService degradationService;

    @BeforeEach
    void setUp() {
        cacheManager = mock(CacheManager.class);
        circuitBreakerService = mock(CircuitBreakerService.class);
        degradationService = mock(DegradationService.class);
        when(degradationService.begin(any())).thenReturn(mock(DegradationService.DegradationContext.class));
    }

    @Test
    void shouldUseTavilyWhenTavilyReturnsDocuments() {
        TavilyWebSearchClient tavily = mock(TavilyWebSearchClient.class);
        BochaWebSearchClient bocha = mock(BochaWebSearchClient.class);
        when(tavily.available()).thenReturn(true);
        when(tavily.search(any())).thenReturn(WebSearchResult.success("tavily", List.of(document("周杰伦"))));
        when(circuitBreakerService.executeWebSearch(any(), any())).thenAnswer(inv -> inv.getArgument(0, java.util.function.Supplier.class).get());
        WebSearchService service = new WebSearchService(new WebSearchProperties(), tavily, bocha, cacheManager, circuitBreakerService, degradationService);

        WebSearchResult result = service.search("周杰伦是谁");

        assertEquals("tavily", result.getProvider());
        assertTrue(result.hasDocuments());
        verify(bocha, never()).search(any());
    }

    @Test
    void shouldFallbackToBochaWhenTavilyFails() {
        TavilyWebSearchClient tavily = mock(TavilyWebSearchClient.class);
        BochaWebSearchClient bocha = mock(BochaWebSearchClient.class);
        when(tavily.available()).thenReturn(true);
        when(tavily.search(any())).thenThrow(new IllegalStateException("timeout"));
        when(bocha.available()).thenReturn(true);
        when(bocha.search(any())).thenReturn(WebSearchResult.success("bocha", List.of(document("林俊杰"))));
        when(circuitBreakerService.executeWebSearch(any(), any())).thenAnswer(inv -> inv.getArgument(0, java.util.function.Supplier.class).get());
        WebSearchService service = new WebSearchService(new WebSearchProperties(), tavily, bocha, cacheManager, circuitBreakerService, degradationService);

        WebSearchResult result = service.search("林俊杰代表作");

        assertEquals("bocha", result.getProvider());
        assertTrue(result.hasDocuments());
    }

    @Test
    void shouldReturnDisabledWhenNoProviderConfigured() {
        TavilyWebSearchClient tavily = mock(TavilyWebSearchClient.class);
        BochaWebSearchClient bocha = mock(BochaWebSearchClient.class);
        when(tavily.available()).thenReturn(false);
        when(bocha.available()).thenReturn(false);
        when(circuitBreakerService.executeWebSearch(any(), any())).thenAnswer(inv -> inv.getArgument(0, java.util.function.Supplier.class).get());
        WebSearchService service = new WebSearchService(new WebSearchProperties(), tavily, bocha, cacheManager, circuitBreakerService, degradationService);

        WebSearchResult result = service.search("随便搜一下");

        assertEquals("DISABLED", result.getStatus());
    }

    private WebSearchDocument document(String title) {
        return WebSearchDocument.builder()
                .title(title)
                .url("https://example.com")
                .snippet("简介")
                .source("test")
                .build();
    }
}
