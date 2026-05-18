package org.javaup.ai.infra.lock.impl;

import org.javaup.ai.infra.lock.LockType;
import org.javaup.ai.infra.lock.ServiceLocker;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

public class RedissonWriteLocker implements ServiceLocker {

    private final RedissonClient redissonClient;
    private final ThreadLocal<RReadWriteLock> currentLock = new ThreadLocal<>();

    public RedissonWriteLocker(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public LockType lockType() {
        return LockType.Write;
    }

    @Override
    public boolean tryLock(String lockName, long waitTime, TimeUnit timeUnit) {
        RReadWriteLock rwLock = redissonClient.getReadWriteLock(lockName);
        try {
            boolean acquired = rwLock.writeLock().tryLock(waitTime, timeUnit);
            if (acquired) {
                currentLock.set(rwLock);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void unlock(String lockName) {
        RReadWriteLock rwLock = currentLock.get();
        if (rwLock != null) {
            try {
                rwLock.writeLock().unlock();
            } finally {
                currentLock.remove();
            }
        }
    }
}
