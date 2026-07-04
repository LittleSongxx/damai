package org.javaup.ai.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmbeddingCacheService {

    private final CacheManager cacheManager;

    public float[] get(String text) {
        return cacheManager.getEmbedding(text);
    }

    public void put(String text, float[] vector) {
        cacheManager.putEmbedding(text, vector);
    }
}
