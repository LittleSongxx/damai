package org.javaup.ai.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.cache.FaqSearchCacheService;
import org.javaup.ai.cache.Nl2SqlCacheService;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheInvalidationHandler {

    private final FaqSearchCacheService faqSearchCacheService;
    private final Nl2SqlCacheService nl2SqlCacheService;
    private final org.javaup.ai.service.PromptVersionService promptVersionService;

    public void handleMessage(String message) {
        if (message == null || message.isBlank()) return;
        log.info("Cache invalidation received: {}", message);
        if ("faq_search".equals(message)) {
            faqSearchCacheService.invalidate();
        } else if ("nl2sql_schema".equals(message)) {
            nl2SqlCacheService.invalidateSchema("default");
        } else if (message.startsWith("prompt:")) {
            String promptKey = message.substring("prompt:".length());
            promptVersionService.invalidateCacheLocal(promptKey);
        }
    }
}
