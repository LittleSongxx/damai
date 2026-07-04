package org.javaup.ai.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FaqSearchCacheService {

    private final CacheManager cacheManager;

    public String get(String query) {
        return cacheManager.getFaqSearch(query);
    }

    public void put(String query, String jsonResult) {
        cacheManager.putFaqSearch(query, jsonResult);
    }

    public void invalidate() {
        cacheManager.invalidateFaqSearch();
    }
}
