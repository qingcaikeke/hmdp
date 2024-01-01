package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
//继承mybatis-plus，的serviceimpl，指定mapper和实体类，可以便捷的完成单表增删改查，有一些方法
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private RedisTemplate<String,String> redisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号，方法写在Regex正则工具类里了
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //2.生成验证码 使用hutool包
        String code = RandomUtil.randomNumbers(6);
        //3.保存到session Attribute属性，归因于
        //3.1 集群无法共享session，可能需要重复登录，改用redis
        //session.setAttribute("code",code);
        //只使用phone当key可能会与其他业务产生冲突，最后加个前缀，为了防止之后调用的时候打错，把前缀定义为常量存在工具类里
        redisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //4.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //2.校验验证码
        //Object cacheCode = session.getAttribute("code");
        String cacheCode = redisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode==null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //3.根据手机号查用户
        User user = query().eq("phone",phone).one();
        //4.查不到创建用户
        if(user==null){
            System.out.println("chabudao");
            user = createUserWithPhone(phone);
        }
        //5.用户信息保存到session
        //session.setAttribute("user",userDTO);
        //改为到redis 1.生成uuid作为key
        System.out.println("user!=null");
        String token = UUID.randomUUID().toString(true);
        //2.使用哈希数据类型，占用空间更少，可以对单个字段做crud
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);//有一个user字段是long类型，存到redis会报错，所以转到map的时候，要把这个字段改为string
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setFieldValueEditor((fieldName,fieldVal)->fieldVal.toString()));

        redisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,userMap);
        //设置过期时间，之后还需要设置通过拦截器更新有效期
        redisTemplate.expire(LOGIN_USER_KEY + token,365,TimeUnit.DAYS);
        //返回token
        return Result.ok(token);
    }

    @Override
    public Result logout(HttpServletRequest request) {
        String token = request.getHeader("authorization");
        redisTemplate.delete(LOGIN_USER_KEY + token);
        UserHolder.removeUser();
        return Result.ok("redis中删除，userholder中删除");
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        //使用工具类中的 systemConstants 系统常量
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //2.保存用户,使用mybatis-plus
        save(user);
        System.out.println(user.getPhone().toString()+user.getNickName().toString()+"A");
        System.out.println("------------------");
        return user;
    }
}
