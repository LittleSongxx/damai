package org.javaup.ai.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.context.AiRequestContextHolder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RateLimitProperties properties;

    private static final RedisScript<Long> SLIDING_WINDOW_SCRIPT = RedisScript.of("""
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local now = redis.call('TIME')
            local nowMs = now[1] * 1000 + math.floor(now[2] / 1000)
            local windowStart = nowMs - window * 1000
            redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)
            local count = redis.call('ZCARD', key)
            if count >= limit then
                return 0
            end
            redis.call('ZADD', key, nowMs, nowMs .. '-' .. count)
            redis.call('EXPIRE', key, math.ceil(window * 1.5))
            return 1
            """);

    public boolean tryAcquire(String endpoint, HttpServletRequest request) {
        if (!properties.isEnabled()) return true;

        RateLimitProperties.EndpointLimit config = properties.getEndpoints().get(endpoint);
        if (config == null) return true;

        String key = buildKey(endpoint, config.getKeyType(), request);
        int window = config.getWindowSeconds() > 0 ? config.getWindowSeconds() : 1;
        Long result = redisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                List.of("damai:ratelimit:" + key),
                String.valueOf(config.getLimit()),
                String.valueOf(window)
        );
        boolean allowed = result != null && result == 1L;
        if (!allowed) {
            log.warn("Rate limit exceeded: endpoint={}, key={}, limit={}", endpoint, key, config.getLimit());
        }
        return allowed;
    }

    private String buildKey(String endpoint, String keyType, HttpServletRequest request) {
        String identifier = switch (keyType) {
            case "user" -> {
                var ctx = AiRequestContextHolder.getOptional();
                yield ctx.map(c -> String.valueOf(c.getUser().getUserId())).orElse(getClientIp(request));
            }
            case "ip" -> getClientIp(request);
            default -> getClientIp(request);
        };
        return endpoint + ":" + identifier;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip != null ? ip.split(",")[0].trim() : "unknown";
    }

    public static ApiResponse<?> rateLimitedResponse() {
        return ApiResponse.error(HttpStatus.TOO_MANY_REQUESTS.value(), "请求过于频繁，请稍后重试");
    }
}
