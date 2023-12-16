package com.hmdp.utils;

import lombok.var;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
//生成全局唯一Id ：要求唯一性，高性能，高可用，递增性，安全性
// 为什么不用主键自增：1.会泄漏信息 2.分表后重新从零开始自增，会出现冲突
//生成策略：1.redis 2.uuid 3.snowflake算法
public class RedisIdWorker {
    public static final long beginTimeSecond = 1672531200L;
    @Autowired
    RedisTemplate<String,String> redisTemplate;
    //时间戳（31位） + 序列号（32位）
    public long nextId(String keyPrefix){
        LocalDateTime now = LocalDateTime.now();
        //生成时间戳
        long nowSecond =now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - beginTimeSecond;
        //每天使用不同的key，便于统计，防止超界
        //使用冒号分隔层级,可以以此统计某年/某月/某天订单数  比如，可以使用 user:123:* 来查找用户123的所有数据
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = redisTemplate.opsForValue().increment("incr" + keyPrefix + ":" + date);
        return timeStamp<<32 | count;
    }

    public static void main(String[] args) {
        //结果为:1672531200
        long nowSecond = LocalDateTime.of(2023,1,1,0,0,0).toEpochSecond(ZoneOffset.UTC);
        System.out.println(nowSecond);
    }

}
