package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import lombok.val;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
public class SimpleRedisLock implements ILock{

    private RedisTemplate<String,String> redisTemplate;
    private String name;

    public SimpleRedisLock(RedisTemplate<String, String> redisTemplate, String name) {
        this.redisTemplate = redisTemplate;
        this.name = name;
    }
    private static final String PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString() + '-';
    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();//不同线程的threadId可能一样，再加一个uuid以区分
        Boolean success = redisTemplate.opsForValue().
                setIfAbsent(PREFIX+name,threadId,timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);//success可能为null
    }
    //静态常量和静态代码块，类一加载就初始化完成了，性能好
    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    //静态代码块初始化静态常量
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unLock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    @Override
    public void unLock() {
        redisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(PREFIX+name),
                ID_PREFIX + Thread.currentThread().getId());
    }
//    @Override
//    public void unLock() {
//        //解决分布式锁误删问题：宕机时间超过锁过期时间，另一线程拿到锁执行过程中，原线程回复，完成执行，误删新线程加的锁
//        //解决方法：解锁时对比加锁时的id（redis中的内容）和当前id
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        String id = redisTemplate.opsForValue().get(PREFIX + name);
//        //存在问题，如果判断完成后，删除之前，发生了阻塞，阻塞过程中锁过期，新线程加锁，之后阻塞结束还是会被误删
//        //JVM垃圾回收会阻塞所有代码
//        if(threadId.equals(id)){
//            redisTemplate.delete(PREFIX+name);
//        }
//    }
}
