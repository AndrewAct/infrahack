package io.infrahack.distributedratelimiter.model;

/** Request attributes a {@link RateLimitRule} can key its bucket on, alone or combined. */
public enum Dimension {
    USER, API_KEY, TENANT, IP, ENDPOINT
}
