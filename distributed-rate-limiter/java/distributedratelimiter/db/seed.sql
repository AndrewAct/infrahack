-- Sample rules mirroring InMemoryRateLimitRuleRepository, so Postgres mode and the default no-DB
-- mode start out enforcing the same limits.
--
-- Edit a row (e.g. `UPDATE rate_limit_rules SET limit_amount = 999 WHERE name = 'user-pro-rps';`)
-- to see dynamic config in action: RuleCache picks up the change within
-- rate-limiter.rule-refresh-interval-ms (default 10s), with no gateway/backend restart.

INSERT INTO rate_limit_rules
    (name, dimensions, tier, limit_amount, window_seconds, burst_capacity, failure_policy, priority, enabled)
VALUES
    ('user-free-rps',           ARRAY['USER'],              'free',       5,      1,     10,     NULL,   10,  true),
    ('user-pro-rps',            ARRAY['USER'],              'pro',        50,     1,     100,    NULL,   10,  true),
    ('user-enterprise-rps',     ARRAY['USER'],              'enterprise', 500,    1,     1000,   NULL,   10,  true),
    -- A second, independent window on the same USER dimension: per-second AND per-day.
    ('user-daily-quota',        ARRAY['USER'],              NULL,         100000, 86400, 100000, NULL,   20,  true),
    -- Protects a shared backend regardless of which user within a tenant calls it.
    ('tenant-endpoint-rps',     ARRAY['TENANT','ENDPOINT'], NULL,         200,    1,     400,    NULL,   30,  true),
    -- No userId on anonymous/unauthenticated traffic, so IP is the only usable dimension.
    ('ip-anonymous-rps',        ARRAY['IP'],                NULL,         10,     1,     20,     NULL,   5,   true),
    ('api-key-rps',             ARRAY['API_KEY'],           NULL,         100,    1,     200,    NULL,   15,  true),
    -- Per-rule override: fails closed even though the platform default is fail-open.
    ('billing-endpoint-strict', ARRAY['TENANT','ENDPOINT'], NULL,         10,     1,     10,     'DENY', 100, true)
ON CONFLICT (name) DO NOTHING;
