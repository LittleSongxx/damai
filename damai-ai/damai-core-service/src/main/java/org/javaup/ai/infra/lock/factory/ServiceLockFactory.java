package org.javaup.ai.infra.lock.factory;

import org.javaup.ai.infra.lock.LockType;
import org.javaup.ai.infra.lock.ServiceLocker;
import org.javaup.ai.infra.lock.impl.RedissonFairLocker;
import org.javaup.ai.infra.lock.impl.RedissonReadLocker;
import org.javaup.ai.infra.lock.impl.RedissonReentrantLocker;
import org.javaup.ai.infra.lock.impl.RedissonWriteLocker;
import org.redisson.api.RedissonClient;

import java.util.EnumMap;
import java.util.Map;

public class ServiceLockFactory {

    private final Map<LockType, ServiceLocker> lockerMap;

    public ServiceLockFactory(RedissonClient redissonClient) {
        EnumMap<LockType, ServiceLocker> map = new EnumMap<>(LockType.class);
        map.put(LockType.Reentrant, new RedissonReentrantLocker(redissonClient));
        map.put(LockType.Fair, new RedissonFairLocker(redissonClient));
        map.put(LockType.Read, new RedissonReadLocker(redissonClient));
        map.put(LockType.Write, new RedissonWriteLocker(redissonClient));
        this.lockerMap = Map.copyOf(map);
    }

    public ServiceLocker get(LockType lockType) {
        ServiceLocker locker = lockerMap.get(lockType);
        if (locker == null) {
            throw new IllegalArgumentException("unsupported lock type: " + lockType);
        }
        return locker;
    }
}
