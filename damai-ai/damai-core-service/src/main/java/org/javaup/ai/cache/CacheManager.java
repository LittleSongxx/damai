package org.javaup.ai.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.config.CacheProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class CacheManager {

    private final Cache<String, float[]> embeddingCache;
    private final Cache<String, String> nl2sqlSchemaCache;
    private final CacheProperties properties;
    private final CacheMetrics metrics;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String FAQ_SEARCH_PREFIX = "damai:cache:faq:";
    private static final String WEB_SEARCH_PREFIX = "damai:cache:web:";
    private static final String USER_CTX_PREFIX = "damai:cache:user:";

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String chatModel;

    public CacheManager(CacheProperties properties, CacheMetrics metrics,
                         @Qualifier("cacheRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.properties = properties;
        this.metrics = metrics;
        this.redisTemplate = redisTemplate;
        this.embeddingCache = Caffeine.newBuilder()
                .maximumSize(properties.getEmbedding().getMaxSize())
                .expireAfterWrite(properties.getEmbedding().getTtlMinutes(), TimeUnit.MINUTES)
                .recordStats()
                .build();
        this.nl2sqlSchemaCache = Caffeine.newBuilder()
                .maximumSize(properties.getNl2sqlSchema().getMaxSize())
                .expireAfterWrite(properties.getNl2sqlSchema().getTtlMinutes(), TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    // --- embedding cache ---

    public float[] getEmbedding(String text) {
        if (!properties.getEmbedding().isEnabled()) return null;
        float[] result = embeddingCache.getIfPresent(text);
        metrics.recordEmbedding(result != null);
        return result;
    }

    public void putEmbedding(String text, float[] vector) {
        if (properties.getEmbedding().isEnabled() && text != null && vector != null) {
            embeddingCache.put(text, vector);
        }
    }

    // --- FAQ search cache (Redis) ---

    public String getFaqSearch(String query) {
        if (!properties.getFaqSearch().isEnabled()) return null;
        String key = faqSearchKey(query);
        String result = (String) redisTemplate.opsForValue().get(key);
        metrics.recordFaqSearch(result != null);
        return result;
    }

    public void putFaqSearch(String query, String jsonResult) {
        if (properties.getFaqSearch().isEnabled() && StringUtils.hasText(query) && StringUtils.hasText(jsonResult)) {
            String key = faqSearchKey(query);
            redisTemplate.opsForValue().set(key, jsonResult, Duration.ofMinutes(properties.getFaqSearch().getTtlMinutes()));
        }
    }

    public void invalidateFaqSearch() {
        redisTemplate.delete(redisTemplate.keys(FAQ_SEARCH_PREFIX + "*"));
        log.info("FAQ search cache invalidated");
    }

    // --- Web search cache (Redis) ---

    public String getWebSearch(String query) {
        if (!properties.getWebSearch().isEnabled()) return null;
        String key = webSearchKey(query);
        String result = (String) redisTemplate.opsForValue().get(key);
        metrics.recordWebSearch(result != null);
        return result;
    }

    public void putWebSearch(String query, String jsonResult) {
        if (properties.getWebSearch().isEnabled() && StringUtils.hasText(query) && StringUtils.hasText(jsonResult)) {
            String key = webSearchKey(query);
            redisTemplate.opsForValue().set(key, jsonResult, Duration.ofMinutes(properties.getWebSearch().getTtlMinutes()));
        }
    }

    // --- User context cache (Redis) ---

    public org.javaup.ai.context.AiUserContext getUserContext(String token) {
        if (!properties.getUserContext().isEnabled() || !StringUtils.hasText(token)) return null;
        String key = USER_CTX_PREFIX + sha256(token);
        Object cached = redisTemplate.opsForValue().get(key);
        metrics.recordUserContext(cached != null);
        if (cached instanceof org.javaup.ai.context.AiUserContext ctx) return ctx;
        return null;
    }

    public void putUserContext(String token, org.javaup.ai.context.AiUserContext ctx) {
        if (properties.getUserContext().isEnabled() && StringUtils.hasText(token) && ctx != null) {
            String key = USER_CTX_PREFIX + sha256(token);
            redisTemplate.opsForValue().set(key, ctx, Duration.ofMinutes(properties.getUserContext().getTtlMinutes()));
        }
    }

    // --- NL2SQL schema cache (Caffeine) ---

    public org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlSchemaContext getNl2sqlSchema(String datasourceKey) {
        if (!properties.getNl2sqlSchema().isEnabled() || !StringUtils.hasText(datasourceKey)) return null;
        String cached = nl2sqlSchemaCache.getIfPresent(datasourceKey);
        metrics.recordNl2sqlSchema(cached != null);
        if (cached != null) {
            try {
                return com.alibaba.fastjson2.JSON.parseObject(cached,
                        org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlSchemaContext.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize cached NL2SQL schema", e);
            }
        }
        return null;
    }

    public void putNl2sqlSchema(String datasourceKey, org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlSchemaContext schema) {
        if (properties.getNl2sqlSchema().isEnabled() && StringUtils.hasText(datasourceKey) && schema != null) {
            nl2sqlSchemaCache.put(datasourceKey, com.alibaba.fastjson2.JSON.toJSONString(schema));
        }
    }

    public void invalidateNl2sqlSchema(String datasourceKey) {
        nl2sqlSchemaCache.invalidate(datasourceKey);
    }

    // --- stats ---

    public java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        var embedStats = embeddingCache.stats();
        stats.put("embedding", Map.of(
                "hitRate", String.format("%.2f%%", embedStats.hitRate() * 100),
                "hitCount", embedStats.hitCount(),
                "missCount", embedStats.missCount(),
                "evictionCount", embedStats.evictionCount()
        ));
        stats.put("nl2sqlSchema", Map.of(
                "estimatedSize", nl2sqlSchemaCache.estimatedSize()
        ));
        return stats;
    }

    private String faqSearchKey(String query) {
        return FAQ_SEARCH_PREFIX + sha256(query + "|" + chatModel);
    }

    private String webSearchKey(String query) {
        return WEB_SEARCH_PREFIX + sha256(query + "|" + chatModel);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
