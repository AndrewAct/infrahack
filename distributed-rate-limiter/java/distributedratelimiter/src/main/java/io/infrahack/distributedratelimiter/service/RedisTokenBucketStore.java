package io.infrahack.distributedratelimiter.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Executes {@code scripts/token_bucket_check.lua} as a single {@code EVAL}/{@code EVALSHA}: check
 * and consume across every matched rule in one network round trip. Atomicity comes from Redis's
 * single-threaded command execution (a Lua script runs to completion before any other command),
 * not from any client-side locking.
 *
 * <p>Owns the {@link LettuceConnectionFactory} it's built with (not itself a Spring bean, so
 * nothing else closes it); implements {@link AutoCloseable} so Spring's default {@code @Bean}
 * destroy-method inference releases the connection pool on shutdown.
 */
public final class RedisTokenBucketStore implements TokenBucketStore, AutoCloseable {

    private final LettuceConnectionFactory connectionFactory;
    private final StringRedisTemplate redis;
    private final RedisScript<List> script;

    public RedisTokenBucketStore(LettuceConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
        this.redis = new StringRedisTemplate(connectionFactory);
        this.redis.afterPropertiesSet();
        DefaultRedisScript<List> tokenBucketScript = new DefaultRedisScript<>();
        tokenBucketScript.setLocation(new ClassPathResource("scripts/token_bucket_check.lua"));
        tokenBucketScript.setResultType(List.class);
        this.script = tokenBucketScript;
    }

    @Override
    public List<BucketResult> tryConsume(List<BucketRequest> requests) {
        List<String> keys = requests.stream().map(BucketRequest::key).toList();
        List<String> args = new ArrayList<>(requests.size() * 3);
        for (BucketRequest r : requests) {
            args.add(Double.toString(r.capacity()));
            args.add(Double.toString(r.refillPerSecond()));
            args.add(Integer.toString(r.cost()));
        }

        List<?> raw;
        try {
            raw = redis.execute(script, keys, args.toArray());
        } catch (DataAccessException e) {
            throw new StoreUnavailableException("Redis unavailable for rate limit check", e);
        }

        List<BucketResult> results = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            boolean allowed = "1".equals(raw.get(3 * i));
            double remaining = Double.parseDouble((String) raw.get(3 * i + 1));
            long retryAfterMs = Long.parseLong((String) raw.get(3 * i + 2));
            results.add(new BucketResult(allowed, remaining, retryAfterMs));
        }
        return results;
    }

    @Override
    public void close() {
        connectionFactory.destroy();
    }
}
