package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheData;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.var;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    @Autowired
    private CacheData cacheData;
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //使用自己封装的工具类解决缓存穿透
        Shop shop = cacheData.
                queryWithPathThrough(CACHE_SHOP_KEY,id, Shop.class, this::getById,2L, TimeUnit.MINUTES);
        //解决缓存击穿
        //Shop shop = queryWithMutex(id);
        //Shop shop = queryWithLogicalExpire(id);

        if(shop==null) return Result.fail("店铺不存在");
        return Result.ok(shop);
    }
    //用于解决缓存穿透，方法为缓存null
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1.从redis中取
        String shopJson = redisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson!=null){//不等于null只剩空字符串了
            return null;
        }
        //2.取不到去sql取
        Shop shop = getById(id);
        //2.1如果数据库中也没有
        if(shop==null){
            //解决缓存穿透，储存null，设置一个短的过期时间
            redisTemplate.opsForValue().set(key,"",2, TimeUnit.MINUTES);
            return null;
        }
        //3.存入缓存并返回,存对象的第二种方式，第一种对象转map，存map，第二种对象转json，存json，注意对应的取的方式
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
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
    //创建一个线程池，ctrl+shift+u小写转大写
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        String shopJson = redisTemplate.opsForValue().get(key);
        //简单处理，缓存中查不到直接返回null
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //查到了看是否逻辑过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        //如果没过期
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return shop;
        }
        String lockKey = LOGIN_CODE_KEY + id;
        //过期，看是否能加锁，能的话加锁，创建线程，更新缓存
        boolean isLock = tryLock(lockKey);
        if(isLock){//最好做双重检测，看缓存有没有被更新
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id,30L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }
            });
        }
        //加锁失败返回过期的诗句
        return shop;
    }
    //用一个类封装shop信息和逻辑过期时间，存到redis
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);//模拟缓存延迟
        //封装逻辑过期时间
        RedisData data = new RedisData();
        data.setData(shop);
        data.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        String key = CACHE_SHOP_KEY + id;
        redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(data));
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //给坐标了就查询当前位置指定范围内的商铺，得到id和距离，再根据id查到具体信息，封装进去距离，然后再返回
        // 2.计算分页参数
        String key = SHOP_GEO_KEY + typeId;
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //// GEOSEARCH key BYLONLAT(经纬度longatutide) x y BYRADIUS 10 WITHDISTANC
        //key是店铺类型，查询以当前坐标为中心，距离5000m以内的所有店铺，并返回距离，底层是zset，所以返回的是距离递增的集合
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        //只能做逻辑分页，本质上是查出了0到end，然后自己去截from的部分
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        //转成list，存的东西包括 店铺id和距离
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        //本质是分页查询，可能当前页已经没数据了，即之前都查过了
        if(list.size()<=from){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        //保证顺序不变
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
