package io.infrahack.distributedratelimiter.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as guarded by the rate limiter (the embedded/library deployment mode,
 * as opposed to a gateway calling {@code POST /v1/rate-limit/check} out of process). The value is
 * a stable endpoint identifier used for the {@code ENDPOINT} dimension, independent of the actual
 * request-mapping path, so renaming a route doesn't reset anyone's bucket.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    String value();
}
