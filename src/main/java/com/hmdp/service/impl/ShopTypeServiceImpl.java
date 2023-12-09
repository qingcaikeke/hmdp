package com.hmdp.service.impl;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.w3c.dom.ls.LSInput;

import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private RedisTemplate<String,String> redisTemplate;//什么类型？
    @Override
    public List<ShopType> queryTypeList() {
        //redis中存的是什么类型，怎么转回对象列表
        if(false){
            //todo 使用hutool将json转为List<ShopType>
            return null;
        }
        //todo 使用mybatis-plus从数据库中查到所有店铺类型，形成list
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        //数据库查到的是对象列表，怎么存到redis？

        return shopTypes;
    }
}
