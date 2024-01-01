package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
    @Autowired
    private IFollowService followService;
    //按点赞数降序显示博客
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
    //点开一条博客，详情页
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
    //查询前五个点赞的用户
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
                .in("id",ids).last("order by field ( id,"+idStr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class)).
                collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    //点开博客详情页需要查发博客的人的信息
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
    //点开详情页需要查看当前用户是否点过赞
    private void isBlogLiked(Blog blog){
        UserDTO user = UserHolder.getUser();
        if(user==null){
            return;
        }
        Long userId = user.getId();
        String key = "blog:liked:" + blog.getId();
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }
    //发送一条博客并推送到所有粉丝
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        System.out.println("pre"+blog.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        System.out.println(blog.getId());
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        //获取笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = "feed:"+userId;
            redisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }
    //用接收自己关注的人最近一段时间发的博客
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        //查询收件箱,实现滚动分页查询，因为会经常有新博客，所以下标会改变，不能用基于下标的分页查询
        // zrevRangeByScore key max min offset count 返回小于等于max的偏移offset个的
        String key = "feed:"+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples =
                redisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(typedTuples==null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //需要返回博客，min（下一次的起始score），偏移量（上一次有多少个等于min）
        List<Long> blogIds = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int offsetNext = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            String blogIdStr = tuple.getValue();
            Long blogId = Long.valueOf(blogIdStr);
            blogIds.add(blogId);
            long time = tuple.getScore().longValue();
            if(minTime == time){
                offsetNext++;
            }else {
                minTime = time;
                offsetNext = 1 ;
            }
        }
        //保证按分值顺序（时间先后）返回给前端
        String idsStr = StrUtil.join(",",blogIds);
        List<Blog> blogs = query().in("id",blogIds).last("order by field ( id,"+idsStr+")").list();
        //记得响应博客是谁发的及当前用户是否点过赞
        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        //封装返回结果
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(offsetNext);
        return Result.ok(scrollResult);
    }
}
