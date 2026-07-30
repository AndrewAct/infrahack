-- Atomic multi-key token bucket check-and-consume.
--
-- KEYS[i]  = Redis hash holding bucket i's state: fields "tokens" (float) and "ts" (ms epoch).
-- ARGV holds 3 values per key, in the same order as KEYS:
--   capacity_i        bucket size (steady-state limit + allowed burst)
--   refill_per_sec_i  tokens added per second
--   cost_i            tokens this request would consume from bucket i
--
-- All-or-nothing: if ANY bucket cannot afford its cost, NO bucket is mutated. Without this, a
-- request that fails rule 2 would already have spent tokens out of rule 1's bucket on every
-- attempt, silently over-consuming quota it was never granted.
--
-- Uses redis.call('TIME') instead of a client-supplied timestamp so refill math stays correct
-- even if gateway instances' system clocks have drifted relative to each other.
--
-- Returns a flat array of 3 values per key, in KEYS order: allowed ("1"/"0"), remaining tokens
-- (string), retry_after_ms (string; 0 unless this bucket itself was a blocker).

local now = redis.call('TIME')
local now_ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)

local n = #KEYS
local tokens = {}
local retry_after_ms = {}

for i = 1, n do
    local capacity = tonumber(ARGV[3 * (i - 1) + 1])
    local refill_per_sec = tonumber(ARGV[3 * (i - 1) + 2])
    local cost = tonumber(ARGV[3 * (i - 1) + 3])

    local bucket = redis.call('HMGET', KEYS[i], 'tokens', 'ts')
    local cur_tokens = tonumber(bucket[1])
    local last_ts = tonumber(bucket[2])
    if cur_tokens == nil or last_ts == nil then
        -- No bucket yet, or corrupt/missing fields: treat as a fresh, full bucket.
        cur_tokens = capacity
        last_ts = now_ms
    end

    local elapsed_sec = math.max(0, (now_ms - last_ts) / 1000)
    cur_tokens = math.min(capacity, cur_tokens + elapsed_sec * refill_per_sec)
    tokens[i] = cur_tokens

    if cur_tokens < cost then
        local deficit = cost - cur_tokens
        retry_after_ms[i] = math.ceil((deficit / refill_per_sec) * 1000)
    else
        retry_after_ms[i] = 0
    end
end

local all_allowed = true
for i = 1, n do
    if retry_after_ms[i] > 0 then
        all_allowed = false
    end
end

local result = {}
for i = 1, n do
    local capacity = tonumber(ARGV[3 * (i - 1) + 1])
    local refill_per_sec = tonumber(ARGV[3 * (i - 1) + 2])
    local cost = tonumber(ARGV[3 * (i - 1) + 3])

    if all_allowed then
        tokens[i] = tokens[i] - cost
        redis.call('HMSET', KEYS[i], 'tokens', tostring(tokens[i]), 'ts', tostring(now_ms))
        -- Bound memory: expire idle buckets after ~2x the time needed to refill from empty.
        local ttl_seconds = math.max(1, math.ceil((capacity / refill_per_sec) * 2))
        redis.call('EXPIRE', KEYS[i], ttl_seconds)
    end

    result[#result + 1] = all_allowed and "1" or "0"
    result[#result + 1] = tostring(tokens[i])
    result[#result + 1] = tostring(retry_after_ms[i])
end

return result
