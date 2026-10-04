-- Teaching fixed-window limiter: every attempt consumes a slot, regardless of outcome.
-- KEYS[1] = attempt key; ARGV[1] = cap; ARGV[2] = window in milliseconds.
local cap = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local current = tonumber(redis.call('GET', KEYS[1]) or '0')
if current >= cap then
  local ttl = redis.call('PTTL', KEYS[1])
  return { 0, current, ttl }
end
local next = redis.call('INCR', KEYS[1])
if next == 1 then
  redis.call('PEXPIRE', KEYS[1], window)
end
return { 1, next, redis.call('PTTL', KEYS[1]) }
