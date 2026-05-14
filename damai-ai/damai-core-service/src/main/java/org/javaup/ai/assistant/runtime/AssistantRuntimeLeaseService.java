package org.javaup.ai.assistant.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.config.AssistantRuntimeLeaseProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantRuntimeLeaseService {

    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
            """
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('del', KEYS[1])
                    end
                    return 0
                    """,
            Long.class
    );

    private static final DefaultRedisScript<Long> COMPARE_AND_PEXPIRE = new DefaultRedisScript<>(
            """
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('pexpire', KEYS[1], ARGV[2])
                    end
                    return 0
                    """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final AssistantRuntimeLeaseProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "assistant-runtime-lease-renew");
        thread.setDaemon(true);
        return thread;
    });

    public LeaseHandle acquireConversationLease(String conversationId, String runId) {
        if (!properties.isEnabled() || !StringUtils.hasText(conversationId)) {
            return LeaseHandle.noop();
        }
        String key = properties.getKeyPrefix() + conversationId;
        String token = runId + ":" + UUID.randomUUID();
        long deadline = System.currentTimeMillis() + properties.getAcquireTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            try {
                Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, Duration.ofMillis(properties.getTtlMs()));
                if (Boolean.TRUE.equals(acquired)) {
                    return new RedisLeaseHandle(key, token, scheduleRenewal(key, token));
                }
            } catch (Exception ex) {
                if (properties.isFailOpen()) {
                    log.warn("运行时租约获取失败，回退为本地继续执行 conversationId={}, reason={}", conversationId, ex.getMessage());
                    return LeaseHandle.noop();
                }
                throw new IllegalStateException("运行时租约获取失败", ex);
            }
            sleep(properties.getRetryIntervalMs());
        }
        throw new IllegalStateException("等待会话租约超时 conversationId=" + conversationId);
    }

    private ScheduledFuture<?> scheduleRenewal(String key, String token) {
        long renewInterval = Math.max(1000L, properties.getTtlMs() / 3);
        return scheduler.scheduleAtFixedRate(() -> renew(key, token), renewInterval, renewInterval, TimeUnit.MILLISECONDS);
    }

    private void renew(String key, String token) {
        try {
            redisTemplate.execute(COMPARE_AND_PEXPIRE, List.of(key), token, String.valueOf(properties.getTtlMs()));
        } catch (Exception ex) {
            log.warn("运行时租约续租失败 key={}, reason={}", key, ex.getMessage());
        }
    }

    private void release(String key, String token) {
        try {
            redisTemplate.execute(COMPARE_AND_DELETE, List.of(key), token);
        } catch (Exception ex) {
            log.warn("运行时租约释放失败 key={}, reason={}", key, ex.getMessage());
        }
    }

    private void sleep(long durationMs) {
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待会话租约时被中断", ex);
        }
    }

    public interface LeaseHandle extends AutoCloseable {

        static LeaseHandle noop() {
            return () -> {
            };
        }

        @Override
        void close();
    }

    private final class RedisLeaseHandle implements LeaseHandle {

        private final String key;
        private final String token;
        private final ScheduledFuture<?> renewalFuture;

        private RedisLeaseHandle(String key, String token, ScheduledFuture<?> renewalFuture) {
            this.key = key;
            this.token = token;
            this.renewalFuture = renewalFuture;
        }

        @Override
        public void close() {
            if (renewalFuture != null) {
                renewalFuture.cancel(true);
            }
            release(key, token);
        }
    }
}
