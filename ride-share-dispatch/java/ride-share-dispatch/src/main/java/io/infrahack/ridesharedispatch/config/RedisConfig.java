package io.infrahack.ridesharedispatch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    /**
     * Plain string keys/values everywhere. Hot state is a handful of scalar fields
     * (lat, lng, sequence number, timestamp, status) -- a String template keeps the
     * key layout inspectable with `redis-cli` instead of hiding it behind a serializer.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
