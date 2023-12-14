package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Autowired
    RedisTemplate<String,String> redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //登录校验，访问频率非常高
        //1.获取session
        //HttpSession session = request.getSession();
        //token存在请求头中，具体叫什么看前端定义
        String token = request.getHeader("authorization");
        if(token==null){
            return true;
        }
        //2.从session中获取用户信息
        //Object user = session.getAttribute("user");           entry 条目，进入，参加
        Map<Object,Object> userMap = redisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
        //3.用户不存在，拦截(token过期)
        if(userMap.isEmpty()){
            return true;
        }
        //deprecate不赞成，从map到对象一般有库，一般使用反射实现，先用类.getInstance，再用getDeclaredFields
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap,new UserDTO(),false);
        //保存到threadlocal
        //之后所有需要登录校验的地方只需要去找thread拿信息，而不需要通过传递session，从session中拿信息
        UserHolder.saveUser(userDTO);
        //重设过期时间，相当于更新有效期
        redisTemplate.expire(LOGIN_USER_KEY + token,365, TimeUnit.DAYS);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
