-- 1.参数列表
-- 1.1优惠券id
local voucherId = argv[1]
-- 1.2用户id
local userId = argv[2]

-- 2.数据key
-- 2.1库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key  用于判断是否一人一单，把买过这个单的用户放到一个set里
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1判断库存是否充足
if(tonumber(redis.call('get',stockKey)) <= 0 then
    return 1
end
-- 3.2判断用户是否下过单  用户储存在redis的set里 ， 判断是否为set成员：sismeber orderKey userId
if(redis.call('sismeber',orderKey,userId) == 1) then
    return 2
end