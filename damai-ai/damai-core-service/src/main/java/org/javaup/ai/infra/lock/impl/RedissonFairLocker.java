package org.javaup.ai.infra.lock.impl;

import org.javaup.ai.infra.lock.LockType;
import org.javaup.ai.infra.lock.ServiceLocker;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

public class RedissonFairLocker implements ServiceLocker {

    private final RedissonClient redissonClient;
    private final ThreadLocal<RLock> currentLock = new ThreadLocal<>();

    public RedissonFairLocker(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public LockType lockType() {
        return LockType.Fair;
    }

    @Override
    public boolean tryLock(String lockName, long waitTime, TimeUnit timeUnit) {
        RLock lock = redissonClient.getFairLock(lockName);
        try {
            boolean acquired = lock.tryLock(waitTime, timeUnit);
            if (acquired) {
                currentLock.set(lock);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void unlock(String lockName) {
        RLock lock = currentLock.get();
        if (lock != null && lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
            } finally {
                currentLock.remove();
            }
        }
    }
}
