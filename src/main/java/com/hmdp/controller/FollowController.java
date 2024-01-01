package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Autowired
    private IFollowService iFollowService;
    //手动关注或取关
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId,@PathVariable("isFollow") boolean isFollow){
        return iFollowService.follow(followUserId,isFollow);
    }
    //主页显示是否以关注
    @GetMapping("/or/not/{id}")
    public Result follow(@PathVariable("id") Long followUserId){
        return iFollowService.isFollow(followUserId);
    }
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id){
        return iFollowService.followCommons(id);
    }






}
