package com.hmdp.utils;


import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;

public class UserHolder {
    //定义一个静态的threadlocal常量，泛型是user，说明专门用于处理user
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
