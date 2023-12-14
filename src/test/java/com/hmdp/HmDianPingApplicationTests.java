package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;


@SpringBootTest
@RunWith(SpringRunner.class)
public class HmDianPingApplicationTests {
    @Resource
    ShopServiceImpl shopService;
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

}
