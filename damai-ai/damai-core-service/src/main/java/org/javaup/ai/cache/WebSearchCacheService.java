package org.javaup.ai.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WebSearchCacheService {

    private final CacheManager cacheManager;

    public String get(String query) {
        return cacheManager.getWebSearch(query);
    }

    public void put(String query, String jsonResult) {
        cacheManager.putWebSearch(query, jsonResult);
    }
}
