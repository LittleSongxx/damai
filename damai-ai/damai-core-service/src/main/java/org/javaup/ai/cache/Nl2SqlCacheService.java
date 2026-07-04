package org.javaup.ai.cache;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlSchemaContext;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class Nl2SqlCacheService {

    private final CacheManager cacheManager;

    public Nl2SqlSchemaContext getSchema(String key) {
        return cacheManager.getNl2sqlSchema(key);
    }

    public void putSchema(String key, Nl2SqlSchemaContext schema) {
        cacheManager.putNl2sqlSchema(key, schema);
    }

    public void invalidateSchema(String key) {
        cacheManager.invalidateNl2sqlSchema(key);
    }

    public String getResult(String key) {
        return cacheManager.getNl2sqlResult(key);
    }

    public void putResult(String key, String jsonResult) {
        cacheManager.putNl2sqlResult(key, jsonResult);
    }
}
