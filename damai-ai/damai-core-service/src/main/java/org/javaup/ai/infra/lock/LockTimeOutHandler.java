package org.javaup.ai.infra.lock;

public interface LockTimeOutHandler {

    LockTimeOutStrategy strategy();

    Object handle(String lockName, String[] keys, long waitTime);
}
