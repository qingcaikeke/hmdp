package com.hmdp.interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //登录校验，访问频率非常高
        //1.获取session
        HttpSession session = request.getSession();
        //2.从session中获取用户信息
        Object user = session.getAttribute("user");
        //3.用户不存在，拦截
        if(user==null){
            response.setStatus(401);
            return false;
        }
        //4.用户存在，保存到threadlocal
        UserHolder.saveUser((UserDTO)user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
