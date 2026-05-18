package org.javaup.ai.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.cache.CacheManager;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheInvalidationHandler {

    private final CacheManager cacheManager;
    private final org.javaup.ai.service.PromptVersionService promptVersionService;

    public void handleMessage(String message) {
        if (message == null || message.isBlank()) return;
        log.info("Cache invalidation received: {}", message);
        if ("faq_search".equals(message)) {
            cacheManager.invalidateFaqSearch();
        } else if ("nl2sql_schema".equals(message)) {
            cacheManager.invalidateNl2sqlSchema("default");
        } else if (message.startsWith("prompt:")) {
            String promptKey = message.substring("prompt:".length());
            promptVersionService.invalidateCache(promptKey);
        }
    }
}
