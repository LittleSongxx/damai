package org.javaup.ai.infra.config;

import org.javaup.ai.infra.lease.RedisLeaseManager;
import org.javaup.ai.infra.lock.LockUtil;
import org.javaup.ai.infra.lock.aspect.ServiceLockAspect;
import org.javaup.ai.infra.lock.factory.ServiceLockFactory;
import org.javaup.ai.infra.repeat.aspect.RepeatExecuteLimitAspect;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonInfraConfiguration {

    @Bean
    public ServiceLockFactory serviceLockFactory(RedissonClient redissonClient) {
        return new ServiceLockFactory(redissonClient);
    }

    @Bean
    @ConditionalOnBean(ServiceLockFactory.class)
    public ServiceLockAspect serviceLockAspect(ServiceLockFactory serviceLockFactory) {
        return new ServiceLockAspect(serviceLockFactory);
    }

    @Bean
    public RedisLeaseManager redisLeaseManager(RedissonClient redissonClient) {
        return new RedisLeaseManager(redissonClient);
    }

    @Bean
    public RepeatExecuteLimitAspect repeatExecuteLimitAspect(RedissonClient redissonClient) {
        return new RepeatExecuteLimitAspect(redissonClient);
    }

    @Bean
    @ConditionalOnBean(ServiceLockFactory.class)
    public LockUtil lockUtil(ServiceLockFactory serviceLockFactory) {
        return new LockUtil(serviceLockFactory);
    }
}
