package org.javaup.ai.infra.lock;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.infra.lock.factory.ServiceLockFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@Slf4j
public class LockUtil {

    private final ServiceLockFactory lockFactory;

    public LockUtil(ServiceLockFactory lockFactory) {
        this.lockFactory = lockFactory;
    }

    public <T> T withLock(String lockName, Callable<T> task) {
        return withLock(LockType.Reentrant, lockName, 10, TimeUnit.SECONDS, task);
    }

    public <T> T withLock(LockType lockType, String lockName, long waitTime, TimeUnit timeUnit, Callable<T> task) {
        ServiceLocker locker = lockFactory.get(lockType);
        if (!locker.tryLock(lockName, waitTime, timeUnit)) {
            throw new IllegalStateException("failed to acquire lock: " + lockName);
        }
        try {
            return task.call();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            locker.unlock(lockName);
        }
    }

    public void withLock(String lockName, Runnable task) {
        withLock(LockType.Reentrant, lockName, 10, TimeUnit.SECONDS, () -> {
            task.run();
            return null;
        });
    }
}
