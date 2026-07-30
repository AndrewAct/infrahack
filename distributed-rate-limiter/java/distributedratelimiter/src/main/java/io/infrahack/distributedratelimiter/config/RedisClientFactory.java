package io.infrahack.distributedratelimiter.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Builds a Redis connection from {@link AppConfig#redisUrl()}, e.g. {@code redis://localhost:6379}
 * or {@code redis://:password@host:6379}. Not itself a Spring bean (see {@code ApplicationBeans}),
 * so callers own its lifecycle.
 */
public final class RedisClientFactory {

    private RedisClientFactory() {}

    public static LettuceConnectionFactory create(AppConfig config) {
        URI uri = URI.create(config.redisUrl().orElseThrow(
                () -> new IllegalStateException("REDIS_URL is required for Redis mode")));

        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        if (uri.getUserInfo() != null) {
            String[] parts = uri.getUserInfo().split(":", 2);
            standalone.setPassword(parts.length == 2 ? parts[1] : parts[0]);
        }

        // A slow Redis must surface as "store unavailable" well within the hot-path latency
        // budget, not hang the request - this is what lets RateLimiterService apply its failure
        // policy instead of the whole request timing out unexplained.
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(100))
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, clientConfig);
        factory.afterPropertiesSet();
        return factory;
    }
}
