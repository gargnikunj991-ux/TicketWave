package com.ticketwave.infrastructure.redis;

import com.ticketwave.seat.reclamation.RedisKeyExpirationListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@Slf4j
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }

    /**
     * Tier 1 Reactive Inventory Reclamation:
     * Registers a listener container subscribed to Redis key expiration events (__keyevent@*__:expired).
     * Active in production and default environments (bypassed in @ActiveProfiles("test") to allow offline testing).
     */
    @Bean
    @Profile("!test")
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisKeyExpirationListener expirationListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(expirationListener, new PatternTopic("__keyevent@*__:expired"));
        container.setErrorHandler(e -> log.warn("Redis pub/sub listener error: {}", e.getMessage()));
        return container;
    }
}
