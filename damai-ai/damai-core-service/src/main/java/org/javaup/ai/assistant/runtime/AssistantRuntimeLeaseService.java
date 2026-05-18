package org.javaup.ai.assistant.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.config.AssistantRuntimeLeaseProperties;
import org.javaup.ai.infra.lease.RedisLeaseManager;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantRuntimeLeaseService {

    private final RedisLeaseManager leaseManager;
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
        Duration ttl = Duration.ofMillis(properties.getTtlMs());
        long deadline = System.currentTimeMillis() + properties.getAcquireTimeoutMs();

        while (System.currentTimeMillis() < deadline) {
            try {
                boolean acquired = leaseManager.acquire(key, token, ttl);
                if (acquired) {
                    return new RedisLeaseHandle(key, token, scheduleRenewal(key, token, ttl));
                }
            } catch (Exception ex) {
                if (properties.isFailOpen()) {
                    log.warn("runtime lease acquire failed, falling back to local execution conversationId={} reason={}",
                            conversationId, ex.getMessage());
                    return LeaseHandle.noop();
                }
                throw new IllegalStateException("runtime lease acquire failed", ex);
            }
            sleep(properties.getRetryIntervalMs());
        }
        throw new IllegalStateException("waiting for conversation lease timed out conversationId=" + conversationId);
    }

    private ScheduledFuture<?> scheduleRenewal(String key, String token, Duration ttl) {
        long renewInterval = Math.max(1000L, ttl.toMillis() / 3);
        return scheduler.scheduleAtFixedRate(
                () -> renewSafely(key, token, ttl),
                renewInterval, renewInterval, TimeUnit.MILLISECONDS);
    }

    private void renewSafely(String key, String token, Duration ttl) {
        try {
            boolean renewed = leaseManager.renew(key, token, ttl);
            if (!renewed) {
                log.warn("runtime lease renewal rejected (key may have expired or been taken over) key={}", key);
            }
        } catch (Exception ex) {
            log.warn("runtime lease renewal failed key={} reason={}", key, ex.getMessage());
        }
    }

    private void releaseSafely(String key, String token) {
        try {
            leaseManager.release(key, token);
        } catch (Exception ex) {
            log.warn("runtime lease release failed key={} reason={}", key, ex.getMessage());
        }
    }

    private void sleep(long durationMs) {
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for conversation lease", ex);
        }
    }

    public interface LeaseHandle extends AutoCloseable {

        static LeaseHandle noop() {
            return () -> {};
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
            releaseSafely(key, token);
        }
    }
}
