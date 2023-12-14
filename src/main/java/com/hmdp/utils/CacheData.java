package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;

@Component
@Resource
//封装redis缓存工具类
public class CacheData {
    @Autowired
    private RedisTemplate<String,String> redisTemplate;

    //将任意类型的对象序列化成Json，缓存至redis
    public void set(String key, Object value, Long time, TimeUnit unit){
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    //任意对象存redis，设置逻辑过期时间
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }
    //根据指定key去查询，反序列化为对象
    //<>中为定义泛型，之后的是使用泛型
    public <R,ID> R queryWithPathThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbQuery, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        String json = redisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json,type);
        }
        if(json!=null){
            return null;
        }
        R r = dbQuery.apply(id);
        if(r==null){
            redisTemplate.opsForValue().set(key,"",2,TimeUnit.MINUTES);
            return null;
        }
        this.set(key,r,time,unit);
        return r;
    }
    //逻辑过期时间+创建线程解决缓存击穿
    private boolean tryLock(String lock){
        //拆箱：封装类到基本类，装箱：相反                      加一个过期时间，防止出现故障，锁一直没有删
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(lock,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //解锁就是把锁删了
    private void unLock(String lock){
        redisTemplate.delete(lock);
    }
    //创建一个线程池，ctrl+shift+u小写转大写
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //逻辑过期时间解决缓存击穿
    public <R,ID> R queryWithLogicalExpire(String prefix,ID id,Class<R> type,Function<ID,R> dbQuerty,Long time,TimeUnit unit){
        String key = prefix + id;
        String shopJson = redisTemplate.opsForValue().get(key);
        //简单处理，缓存中查不到直接返回null
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //查到了看是否逻辑过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        //如果没过期
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return r;
        }
        String lockKey = LOGIN_CODE_KEY + id;
        //过期，看是否能加锁，能的话加锁，创建线程，更新缓存
        boolean isLock = tryLock(lockKey);
        if(isLock){//最好做双重检测，看缓存有没有被更新
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R newR = dbQuerty.apply(id);
                    this.setWithLogicalExpire(key,newR,time,unit);
                } catch ( Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }
            });
        }
        //加锁失败返回过期的数据
        return r;
    }







}
