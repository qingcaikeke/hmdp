package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
//redisson：在分布式系统中使用redis储存的一个框架
//用config去,配置构造Redisson客户端
//可以将RedissonConfig看做一个工厂，从里面拿到redisson的各种工具
//这样的配置类通常用于创建和配置一些需要在整个应用程序中共享的实例
@Configuration
public class RedissonConfig {
    @Bean//声明一个方法，该方法将返回一个由 Spring 管理的 bean 对象
    //之后注入的是redissonClient而不是RedissonConfig
    public RedissonClient redissonClient(){
        Config config = new Config();
        config.useSingleServer().setAddress("redis://123.57.229.124:6379");
        // 创建 Redisson 客户端
        RedissonClient redisson = Redisson.create(config);
        return redisson;
    }
}
