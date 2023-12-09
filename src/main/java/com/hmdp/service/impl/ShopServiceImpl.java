package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private RedisTemplate<String,String> redisTemplate;
    @Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis中取
        String shopJson = redisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        if(shopJson!=null){//不等于null只剩空字符串了
            return Result.fail("店铺不存在");
        }
        //2.取不到去sql取
        Shop shop = getById(id);
        if(shop==null){
            //解决缓存穿透，储存null，设置一个短的过期时间
            redisTemplate.opsForValue().set(key,"",2, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        //3.存入缓存并返回,存对象的第二种方式，第一种对象转map，存map，第二种对象转json，存json，注意对应的取的方式
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1.从redis中取
        String shopJson = redisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson!=null){//不等于null只剩空字符串了
            //查到空是为了缓存击穿，查不到返回null
            return null;
        }
        //修改，解决缓存穿透，为了防止并发的去构建缓存
        String lock = "lock:shop:" + id;
        Shop shop = null;
        try {//ctrl+alt+t快速try catch
            //1.获取互斥锁
            boolean isLock = tryLock(lock);
            //加锁失败，等待，重试
            if(!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //加锁成功，去缓存
            //最好加一个双重校验，如果缓存成功就不用再缓存了
            shop = getById(id);
            //模拟重建耗时
            Thread.sleep(200);
            if(shop==null){
                //解决缓存穿透，储存null，设置一个短的过期时间
                redisTemplate.opsForValue().set(key,"",2, TimeUnit.MINUTES);
                return null;//返回错误信息
            }
            //写入redis
            redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lock);
        }
        return shop;
    }
    //加锁方法，用redis的setnx
    //不能使用synchornize或lock，因为不是拿到锁就执行一样的逻辑
    private boolean tryLock(String lock){
        //拆箱：封装类到基本类，装箱：相反                      加一个过期时间，防止出现故障，锁一直没有删
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(lock,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //解锁就是把锁删了
    private void unLock(String lock){
        redisTemplate.delete(lock);
    }

    @Transactional
    @Override
    //需实现数据库与缓存的双写一致性，权衡数据不一致和数据修改失败
    public Result update(Shop shop) {
        //选择删除缓存而不是更新缓存，因为更新可能一直不查，浪费资源
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        //先更新数据库，再删除缓存（因为考虑多线程）
        updateById(shop);
        redisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
