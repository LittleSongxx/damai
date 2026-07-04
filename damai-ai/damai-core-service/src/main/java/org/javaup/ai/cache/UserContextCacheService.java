package org.javaup.ai.cache;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.context.AiUserContext;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserContextCacheService {

    private final CacheManager cacheManager;

    public AiUserContext get(String token) {
        return cacheManager.getUserContext(token);
    }

    public void put(String token, AiUserContext context) {
        cacheManager.putUserContext(token, context);
    }
}
