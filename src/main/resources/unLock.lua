-- 一人一单，解锁脚本
if(redis.call('get',KEYS[1])==ARGV[1]) then
    return redis.call('DEl',KEYS[1])
end
return 0