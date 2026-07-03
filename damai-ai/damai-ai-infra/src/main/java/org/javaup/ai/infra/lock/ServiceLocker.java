package org.javaup.ai.infra.lock;

import java.util.concurrent.TimeUnit;

public interface ServiceLocker {

    LockType lockType();

    boolean tryLock(String lockName, long waitTime, TimeUnit timeUnit);

    void unlock(String lockName);
}
