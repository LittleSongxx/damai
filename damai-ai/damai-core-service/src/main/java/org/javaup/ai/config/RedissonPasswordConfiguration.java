package org.javaup.ai.config;

import org.redisson.config.BaseConfig;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

@Configuration
public class RedissonPasswordConfiguration {

    @Bean
    public RedissonAutoConfigurationCustomizer blankRedisPasswordCustomizer() {
        return config -> {
            clearBlankAuth(serverConfig(config, "getSingleServerConfig"));
            clearBlankAuth(serverConfig(config, "getClusterServersConfig"));
            clearBlankAuth(serverConfig(config, "getMasterSlaveServersConfig"));
            clearBlankAuth(serverConfig(config, "getReplicatedServersConfig"));
            BaseConfig<?> sentinel = serverConfig(config, "getSentinelServersConfig");
            clearBlankAuth(sentinel);
            if (sentinel instanceof SentinelServersConfig sentinelConfig) {
                if (!StringUtils.hasText(sentinelConfig.getSentinelUsername())) {
                    sentinelConfig.setSentinelUsername(null);
                }
                if (!StringUtils.hasText(sentinelConfig.getSentinelPassword())) {
                    sentinelConfig.setSentinelPassword(null);
                }
            }
        };
    }

    private BaseConfig<?> serverConfig(Config config, String methodName) {
        Method method = ReflectionUtils.findMethod(Config.class, methodName);
        if (method == null) {
            return null;
        }
        ReflectionUtils.makeAccessible(method);
        return (BaseConfig<?>) ReflectionUtils.invokeMethod(method, config);
    }

    private void clearBlankAuth(BaseConfig<?> config) {
        if (config == null) {
            return;
        }
        if (!StringUtils.hasText(config.getUsername())) {
            config.setUsername(null);
        }
        if (!StringUtils.hasText(config.getPassword())) {
            config.setPassword(null);
        }
    }
}
