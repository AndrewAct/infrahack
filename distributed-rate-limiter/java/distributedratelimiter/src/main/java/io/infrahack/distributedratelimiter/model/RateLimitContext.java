package io.infrahack.distributedratelimiter.model;

/**
 * The identifying attributes of one incoming request, as resolved by the caller (a gateway, or
 * this service's own {@code RateLimitInterceptor}). A field left {@code null} simply means rules
 * keyed on that {@link Dimension} cannot match this request — e.g. anonymous traffic has no
 * {@code userId}, so only IP/endpoint-scoped rules apply to it. This service never looks up tier
 * or identity itself; it trusts whatever the caller (which owns auth) resolved.
 */
public record RateLimitContext(
        String userId,
        String apiKey,
        String tenantId,
        String ip,
        String endpoint,
        String tier,
        int cost) {

    public RateLimitContext {
        if (cost <= 0) {
            throw new IllegalArgumentException("cost must be positive");
        }
    }

    /** The value this context has for a given dimension, or null if the request lacks it. */
    public String valueFor(Dimension dimension) {
        return switch (dimension) {
            case USER -> userId;
            case API_KEY -> apiKey;
            case TENANT -> tenantId;
            case IP -> ip;
            case ENDPOINT -> endpoint;
        };
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String userId;
        private String apiKey;
        private String tenantId;
        private String ip;
        private String endpoint;
        private String tier;
        private int cost = 1;

        private Builder() {}

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder ip(String ip) {
            this.ip = ip;
            return this;
        }

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder tier(String tier) {
            this.tier = tier;
            return this;
        }

        /** Defaults to 1; set higher for expensive operations (e.g. model inference vs. a read). */
        public Builder cost(int cost) {
            this.cost = cost;
            return this;
        }

        public RateLimitContext build() {
            return new RateLimitContext(userId, apiKey, tenantId, ip, endpoint, tier, cost);
        }
    }
}
