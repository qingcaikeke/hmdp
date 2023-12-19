package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;
//自定义逻辑过期时间
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
