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
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
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
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;
    @Override
    public Result queryBlogById(Long id) {
        //查询博客
        Blog blog = this.getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        //查询blog相关用户
        queryBlogUser(blog);
        //查询blog是否点赞
        isBlogLike(blog);
        return Result.ok(blog);
    }

    private void isBlogLike(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) return;
        String key= RedisConstants.BLOG_LIKED_KEY+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key,user.getId().toString());
        blog.setIsLike(score!= null);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLike(blog);
        });

        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        UserDTO user = UserHolder.getUser();
        //获取登录用户
        String key= RedisConstants.BLOG_LIKED_KEY+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        //判断当前登录用户是否已经点赞
        if (score==null) {//未点赞
            //点赞数加一
            boolean isSuccess = this.update().setSql("liked=liked+1").eq("id", id).update();
            if(isSuccess){
                //将用户保存redis缓存
                stringRedisTemplate.opsForZSet().add(key, user.getId().toString(),System.currentTimeMillis());
            }
        }else { //已点赞
            //取消点赞 点赞数减一
            boolean isSuccess = this.update().setSql("liked=liked-1").eq("id", id).update();
            if(isSuccess){
                //将用户从redis缓存中移除
                stringRedisTemplate.opsForZSet().remove(key, user.getId().toString());
            }
        }
        return Result.ok();
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryBlogLikesById(Long id) {
        String key= RedisConstants.BLOG_LIKED_KEY+id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5==null||top5.size()==0) {
            return Result.ok(Collections.emptyList());
        }
        //解析出用户id
        List<Long> collect = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String ids = StrUtil.join(",", collect);
        List<User> users = userService.query().in("id", collect).last("ORDER BY FIELD(id,"+ids+")").list();
        List<UserDTO> userDTOS = users.stream().map(user -> {
            UserDTO userDTO = new UserDTO();
            BeanUtil.copyProperties(user, userDTO);
            return userDTO;
        }).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("保存Blog失败");
        }
        //查询笔记作者的所有粉丝
        List<Follow> followUserId = followService.query().eq("follow_user_id", user.getId()).list();
        for (Follow follow : followUserId) {
            //获取粉丝id
            Long userId = follow.getId();
            String key=RedisConstants.FEED_KEY+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());

        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryMyFollowBlog(Long max, Integer offset) {
        //获取当前用户
        UserDTO user = UserHolder.getUser();
        String key=RedisConstants.FEED_KEY+user.getId();
        //查询收件箱
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if (typedTuples==null||typedTuples.size()==0) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids=new ArrayList<>(typedTuples.size());
        long minTime=0;
        int os=1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //获取id
            String idStr = typedTuple.getValue();
            ids.add(Long.valueOf(idStr));
            //获取分数 （时间戳）
            long time = typedTuple.getScore().longValue();
            if (time==minTime){
                os++;
            }else {
                minTime=time;

            }
        }
        //根据id查询blog
        String idsStr = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idsStr + ")").list();
        for (Blog blog : blogs) {
            //查询blog有关用户
            this.queryBlogUser(blog);
            //查询blog点赞数量
            this.isBlogLike(blog);
        }

        //封装返回
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setMinTime(minTime);
        result.setOffset(os);
        //解析数据
        return Result.ok(result);
    }
}
