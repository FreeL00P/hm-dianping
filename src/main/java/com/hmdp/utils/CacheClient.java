package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * CacheClient
 *
 * @author fj
 * @since 2023/3/3 22:05
 */
@Slf4j
@Component
public class CacheClient {

    private  final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public  void  set(String key, Object value, Long time, TimeUnit  timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,timeUnit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     *
     * @param keyPrefix 缓存key前缀
     * @param id 缓存对象id
     * @param type 存取的缓存对象
     * @param dbFallback 数据库函数
     * @param time 缓存过期时间
     * @param timeUnit 时间单位
     * @return 缓存或数据库查询结果
     * @param <R> 对象类型
     * @param <ID> 对象id类型
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix,ID id,Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit  timeUnit) {
        //从redis中查询商铺缓存
        String key = keyPrefix + id.toString();
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //存在直接返回
            log.info("在Redis中查询到id={}的数据",id);
            return JSONUtil.toBean(json, type);
        }
        //判断缓存中命中的是否是空值
        if (json!=null) return null;//返回错误信息
        //不存在在数据库中查询
        R r = dbFallback.apply(id);
        if (r==null) {
            //数据库也不存在，返回错误,将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"" ,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return null;
        }
        log.info("在数据库中中查询到id={}店铺数据",id);
        //存在，写入redis
        this.set(key,r,time,timeUnit);
        return r;
    }
    //逻辑过期解决缓存击穿问题
    public <R,ID> R queryWithLogicExpire(String keyPrefix,String lockKeyPrefix,ID id,Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit  timeUnit) {
        //从redis中查询商铺缓存
        String key = keyPrefix +id.toString();
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isBlank(json)) {
            //存在直接返回
            return null;
        }
        //存在判断是否过期
        //转换为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();//逻辑过期时间
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期直接返回
            return r;
        }
        //过期,重建缓存
        //获取互斥锁
        String lockKey = lockKeyPrefix + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            //开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                //重建缓存
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }

            });
        }
        //直接返回过期数据
        return r;
    }
   //获取锁
    private boolean tryLock(String key) {
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",RedisConstants.LOCK_SHOP_TTL,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //释放锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

}
