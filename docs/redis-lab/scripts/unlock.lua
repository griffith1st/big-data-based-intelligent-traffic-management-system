-- Owner-token unlock: delete only when the stored token matches ARGV[1].
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
