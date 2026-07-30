package io.infrahack.distributedratelimiter.model;

/** What to do with a request when the token-bucket store can't be reached. */
public enum FailurePolicy {
    ALLOW, DENY
}
