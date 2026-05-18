package org.javaup.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisCacheTemplateConfiguration {

    @Bean
    public ChannelTopic cacheInvalidationTopic() {
        return new ChannelTopic("damai:cache:invalidation");
    }

    @Bean
    public RedisMessageListenerContainer cacheInvalidationContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter cacheInvalidationListener) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(cacheInvalidationListener, cacheInvalidationTopic());
        return container;
    }

    @Bean
    public MessageListenerAdapter cacheInvalidationListener(CacheInvalidationHandler handler) {
        return new MessageListenerAdapter(handler, "handleMessage");
    }

    @Bean("cacheRedisTemplate")
    public RedisTemplate<String, Object> cacheRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
