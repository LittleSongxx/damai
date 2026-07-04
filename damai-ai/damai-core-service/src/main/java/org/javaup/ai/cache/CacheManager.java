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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
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
    private static final String FAQ_SEARCH_VERSION_KEY = "damai:cache:version:faq";
    private static final String WEB_SEARCH_PREFIX = "damai:cache:web:";
    private static final String USER_CTX_PREFIX = "damai:cache:user:";
    private static final String NL2SQL_RESULT_PREFIX = "damai:cache:nl2sql:";

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String chatModel;

    @Value("${spring.ai.openai.embedding.options.model:unknown}")
    private String embeddingModel;

    @Value("${spring.ai.openai.embedding.options.dimensions:0}")
    private Integer embeddingDimensions;

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

    // --- embedding cache (Caffeine + optional Redis persistence) ---

    public float[] getEmbedding(String text) {
        if (!properties.getEmbedding().isEnabled()) return null;
        String key = embeddingCacheKey(text);
        float[] result = embeddingCache.getIfPresent(key);
        if (result != null) {
            metrics.recordEmbedding(true);
            return result;
        }
        // Try Redis-persisted embedding
        if (properties.getEmbedding().isRedisPersistenceEnabled()) {
            result = getEmbeddingFromRedis(text);
            if (result != null) {
                embeddingCache.put(key, result);
                metrics.recordEmbedding(true);
                return result;
            }
        }
        metrics.recordEmbedding(false);
        return null;
    }

    public void putEmbedding(String text, float[] vector) {
        if (properties.getEmbedding().isEnabled() && text != null && vector != null) {
            embeddingCache.put(embeddingCacheKey(text), vector);
            if (properties.getEmbedding().isRedisPersistenceEnabled()) {
                putEmbeddingToRedis(text, vector);
            }
        }
    }

    private float[] getEmbeddingFromRedis(String text) {
        try {
            String key = embeddingRedisKey(text);
            Object value = redisTemplate.opsForValue().get(key);
            if (value instanceof String base64) {
                byte[] bytes = Base64.getDecoder().decode(base64);
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                float[] vector = new float[bytes.length / Float.BYTES];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = buf.getFloat();
                }
                return vector;
            }
        } catch (Exception e) {
            log.warn("Failed to load embedding from Redis: {}", e.getMessage());
        }
        return null;
    }

    private void putEmbeddingToRedis(String text, float[] vector) {
        try {
            String key = embeddingRedisKey(text);
            ByteBuffer buf = ByteBuffer.allocate(vector.length * Float.BYTES);
            for (float v : vector) {
                buf.putFloat(v);
            }
            String base64 = Base64.getEncoder().encodeToString(buf.array());
            redisTemplate.opsForValue().set(key, base64,
                    Duration.ofMinutes(properties.getEmbedding().getTtlMinutes()));
        } catch (Exception e) {
            log.warn("Failed to persist embedding to Redis: {}", e.getMessage());
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
        refreshFaqSearchVersion();
        var connectionFactory = redisTemplate.getConnectionFactory();
        if (connectionFactory == null) return;
        try (var connection = connectionFactory.getConnection()) {
            var cursor = connection.keyCommands().scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(FAQ_SEARCH_PREFIX + "*")
                            .count(100)
                            .build());
            while (cursor.hasNext()) {
                byte[] key = cursor.next();
                redisTemplate.delete(new String(key, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.warn("Failed to scan-invalidate FAQ cache: {}", e.getMessage());
        }
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

    // --- NL2SQL result cache (Redis) ---

    public String getNl2sqlResult(String sql) {
        if (!properties.getNl2sqlResult().isEnabled() || !StringUtils.hasText(sql)) return null;
        String key = NL2SQL_RESULT_PREFIX + sha256(sql);
        String result = (String) redisTemplate.opsForValue().get(key);
        metrics.recordNl2sqlResult(result != null);
        return result;
    }

    public void putNl2sqlResult(String sql, String jsonResult) {
        if (properties.getNl2sqlResult().isEnabled() && StringUtils.hasText(sql) && StringUtils.hasText(jsonResult)) {
            String key = NL2SQL_RESULT_PREFIX + sha256(sql);
            redisTemplate.opsForValue().set(key, jsonResult,
                    Duration.ofMinutes(properties.getNl2sqlResult().getTtlMinutes()));
        }
    }

    // --- stats ---

    public java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        var embedStats = embeddingCache.stats();
        stats.put("embedding", Map.of(
                "enabled", properties.getEmbedding().isEnabled(),
                "ttlMinutes", properties.getEmbedding().getTtlMinutes(),
                "maxSize", properties.getEmbedding().getMaxSize(),
                "redisPersistenceEnabled", properties.getEmbedding().isRedisPersistenceEnabled(),
                "hitRate", String.format("%.2f%%", embedStats.hitRate() * 100),
                "hitCount", embedStats.hitCount(),
                "missCount", embedStats.missCount(),
                "evictionCount", embedStats.evictionCount(),
                "estimatedSize", embeddingCache.estimatedSize()
        ));
        var nl2sqlSchemaStats = nl2sqlSchemaCache.stats();
        stats.put("nl2sqlSchema", Map.of(
                "enabled", properties.getNl2sqlSchema().isEnabled(),
                "ttlMinutes", properties.getNl2sqlSchema().getTtlMinutes(),
                "maxSize", properties.getNl2sqlSchema().getMaxSize(),
                "hitRate", String.format("%.2f%%", nl2sqlSchemaStats.hitRate() * 100),
                "hitCount", nl2sqlSchemaStats.hitCount(),
                "missCount", nl2sqlSchemaStats.missCount(),
                "evictionCount", nl2sqlSchemaStats.evictionCount(),
                "estimatedSize", nl2sqlSchemaCache.estimatedSize()
        ));
        stats.put("redisBacked", Map.of(
                "faqSearch", Map.of(
                        "enabled", properties.getFaqSearch().isEnabled(),
                        "ttlMinutes", properties.getFaqSearch().getTtlMinutes()),
                "webSearch", Map.of(
                        "enabled", properties.getWebSearch().isEnabled(),
                        "ttlMinutes", properties.getWebSearch().getTtlMinutes()),
                "userContext", Map.of(
                        "enabled", properties.getUserContext().isEnabled(),
                        "ttlMinutes", properties.getUserContext().getTtlMinutes()),
                "nl2sqlResult", Map.of(
                        "enabled", properties.getNl2sqlResult().isEnabled(),
                        "ttlMinutes", properties.getNl2sqlResult().getTtlMinutes())
        ));
        return stats;
    }

    private String faqSearchKey(String query) {
        return FAQ_SEARCH_PREFIX + faqSearchVersion() + ":" + sha256(query + "|" + chatModel);
    }

    private String webSearchKey(String query) {
        return WEB_SEARCH_PREFIX + sha256(query + "|" + chatModel);
    }

    private String embeddingCacheKey(String text) {
        return embeddingFingerprint() + "|" + text;
    }

    private String embeddingRedisKey(String text) {
        return properties.getEmbedding().getRedisKeyPrefix() + sha256(embeddingFingerprint() + "|" + text);
    }

    private String embeddingFingerprint() {
        return nullToUnknown(embeddingModel) + ":" + (embeddingDimensions == null ? 0 : embeddingDimensions);
    }

    private String faqSearchVersion() {
        try {
            Object version = redisTemplate.opsForValue().get(FAQ_SEARCH_VERSION_KEY);
            return version != null ? String.valueOf(version) : "0";
        } catch (Exception e) {
            log.warn("Failed to load FAQ cache version: {}", e.getMessage());
            return "0";
        }
    }

    private void refreshFaqSearchVersion() {
        try {
            redisTemplate.opsForValue().set(FAQ_SEARCH_VERSION_KEY, String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("Failed to refresh FAQ cache version: {}", e.getMessage());
        }
    }

    private String nullToUnknown(String value) {
        return StringUtils.hasText(value) ? value : "unknown";
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
