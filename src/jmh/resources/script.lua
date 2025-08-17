-- KEYS[1]: 버킷 키
-- ARGV[1]: capacity                (버킷 최대 토큰 수, 정수)
-- ARGV[2]: refill_tokens            (리필되는 토큰 수, 예: 10)
-- ARGV[3]: refill_interval_ms       (해당 토큰이 리필되는 주기(ms), 예: 1000 → 초당 10토큰)
-- ARGV[4]: requested               (소비할 토큰 수, 기본 1)
-- ARGV[5]: ttl_seconds             (키 TTL(초), 기본: 리필주기의 약 2배)

-- 반환(MULTI): [allowed(0|1), remaining_tokens(int), retry_after_ms, now_ms]
-- allowed=0인 경우 소비 실패
-- retry_after_ms < 0인 경우 불가능 (e.g. requested > capacity)

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_tokens = tonumber(ARGV[2])
local refill_interval_ms = tonumber(ARGV[3])
local requested = tonumber(ARGV[4]) or 1
local ttl_seconds = tonumber(ARGV[5])

local t = redis.call('TIME')
-- t[1]: seconds, t[2]: microseconds
local now_ms = (tonumber(t[1]) * 1000) + math.floor(tonumber(t[2]) / 1000)
-- ttl fallback
if not ttl_seconds or ttl_seconds <= 0 then
    ttl_seconds = math.max(1, math.floor((refill_interval_ms / 1000) * 2 + 0.5))
end

-- 남은 토큰 개수(tokens_str), 최근 소비 시간(last_ms) 조회
local tokens_str = redis.call('HGET', key, 'tokens')
local last_ms_str = redis.call('HGET', key, 'last_ms')

local tokens
local last_ms

if not tokens_str or not last_ms_str then
    -- 초기 요청
    tokens = capacity
    last_ms = now_ms
else
    -- 이미 키 값이 존재한다면,
    tokens = tonumber(tokens_str)
    last_ms = tonumber(last_ms_str)
end

-- 토큰 refill
local delta = now_ms - last_ms
if delta < 0 then
    delta = 0
end

-- greedily refill
local rate_per_ms = refill_tokens / refill_interval_ms
if delta > 0 and tokens < capacity then
    tokens = math.min(capacity, tokens + (delta * rate_per_ms))
    last_ms = now_ms
end

local allowed = 0
local retry_after_ms = 0

if requested > capacity then
    -- 버킷 용량을 초과하는 요청은 처리 불가능
    allowed = 0
    retry_after_ms = -1
else
    if tokens >= requested then
        -- 소비 가능한 경우
        allowed = 1
        tokens = tokens - requested
    else
        -- 소비 불가능한 경우 재시도 가능 시간을 반환
        -- 경쟁 상황에서는 재시도 후에도 실패 가능
        allowed = 0
        local deficit = requested - tokens
        retry_after_ms = math.ceil(deficit / rate_per_ms)
    end
end

-- update
redis.call('HSET', key, 'tokens', tokens, 'last_ms', last_ms)
redis.call('PEXPIRE', key, ttl_seconds * 1000)

-- 반환 시 남은 토큰은 정수로 내림 처리
return { allowed, math.floor(tokens), retry_after_ms, now_ms }
