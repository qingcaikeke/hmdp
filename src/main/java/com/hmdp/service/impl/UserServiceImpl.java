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
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import sun.util.resources.cldr.ext.CurrencyNames_en_SL;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
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

    @Override
    //用 位图（bitmap）实现签到 key是用户id加月份，val是一个32位数，第5位为1就表示第五天签到了
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        redisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    //统计的是当包括前天的连续签到天数，而不是最大连续签到
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //BITFIELD key GET(可以有多个命令，get，set,incrby) u14（type(无符号数)）（查14位） 0（从第一位开始查）
        //因为可以有多个命令，所以返回的是一个list
        List<Long> result = redisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        //注意：返回的签到位图是一个十进制数
        Long num = result.get(0);
        //计算从最后一天开始，连续签到了几天
        int count=0;
        while (true){
            if((num & 1)==0){
                break;
            }else {
                count++;
            }
            num = num >>> 1 ;//无符号右移
        }
        return Result.ok(count);
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
