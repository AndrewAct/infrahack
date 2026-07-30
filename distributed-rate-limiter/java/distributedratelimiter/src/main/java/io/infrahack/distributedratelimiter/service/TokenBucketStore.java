package io.infrahack.distributedratelimiter.service;

import java.util.List;

/**
 * Atomic check-and-consume across one or more token buckets. Implementations must guarantee
 * all-or-nothing semantics: if any requested bucket cannot afford its cost, none are mutated.
 */
public interface TokenBucketStore {

    List<BucketResult> tryConsume(List<BucketRequest> requests);
}
