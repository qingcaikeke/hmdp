package com.hmdp;

import ch.qos.logback.classic.spi.LoggerContextAware;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;


@SpringBootTest
@RunWith(SpringRunner.class)
public class HmDianPingApplicationTests {
    @Resource
    ShopServiceImpl shopService;
    @Autowired
    private RedisTemplate<String,String> redisTemplate;
    @Test
    public void loadShopData(){
        List<Shop> list = shopService.list();               //shop -> shop.getTypeId
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //map.entrySet().for
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                //redisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
            }
            redisTemplate.opsForGeo().add(key,locations);
        }
    }
    @Test
    public void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(1L,60L);
        System.out.println("A");
    }
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Test
    public void nextId() {
        long id = redisIdWorker.nextId("A");
        System.out.println(id);
    }
    //测试redis实现uv统计
    @Test
    public void testHyperLogLog(){
        String[] values = new String[1000];
        int j=0;
        for(int i=0;i<1000000;i++){
            j =i %1000;
            values[j] = "user_" + i;
            if(j==999){
                //模拟用户访问
                redisTemplate.opsForHyperLogLog().add("hl2",values);
            }
        }
        //统计数量
        Long count = redisTemplate.opsForHyperLogLog().size("hl2");
    }

}
