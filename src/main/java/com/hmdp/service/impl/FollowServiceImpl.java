package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
 import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        UserDTO user = UserHolder.getUser();
        String key="follows:"+user.getId();
        //判断是关注还是取关
        if (isFollow){
            //关注
            Follow follow = new Follow();
            follow.setUserId(user.getId());
            follow.setFollowUserId(followUserId);
            boolean isSuccess = this.save(follow);
            if (isSuccess){
                //将关注用户id保存到redis
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else {
            //取关
            boolean isSuccess = this.remove(new QueryWrapper<Follow>().eq("user_id", user.getId())
                    .eq("follow_user_id", followUserId));
            if (isSuccess){
                //把关注用户id从redis中移除
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        //查询是否关注
        Long count = query().eq("follow_user_id", id).eq("user_id", UserHolder.getUser().getId()).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommon(Long followUserId) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String k1="follows:"+userId;
        String k2="follows:"+followUserId;
        //求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(k1, k2);
        if (intersect==null || intersect.size()==0){
            //没有共同关注返回一个空的集合
            return Result.ok(Collections.EMPTY_LIST);
        }
        //解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询user对象
        List<User> users = userService.listByIds(ids);
        //转换为DTO
        List<UserDTO> userDtos = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDtos);
    }
}
