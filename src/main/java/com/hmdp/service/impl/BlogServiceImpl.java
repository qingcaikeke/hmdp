package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Autowired
    private RedisTemplate<String,String> redisTemplate;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户,this::query调用当前对象的 queryBlogUser 方法
//        records.forEach(this::queryBlogUser);
        records.forEach(blog ->{
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(blog==null) {
            return Result.fail("笔记不存在");
        }
        //查到博客后填充用户信息(是谁发的博客)，填充用户是否点过赞（当前用户），前端通过isLiked字段判断是否需要高亮
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }
    private void isBlogLiked(Blog blog){
        UserDTO user = UserHolder.getUser();
        if(user==null){

        }
        Long userId = user.getId();
        String key = "blog:liked:" + blog.getId();
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }
    //给博客点赞
    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = "blog:liked:" + id;
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        if(score==null){
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess){// zadd key val score
                redisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if(isSuccess){
                redisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = "blog:liked:" + id;
        //zrange key 0 4 查前5个
        Set<String> top5 = redisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids  = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //需要根据拿到的id顺序去数据库查点赞用户信息，但数据库默认会调成id升序
        List<UserDTO> userDTOS = userService.query()
                //where id in (5,1) order by field (id,5,1)
                .in("id",ids).last("oredr by field ( id,"+idStr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class)).
                collect(Collectors.toList());
        return Result.ok(userDTOS);
    }


    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
