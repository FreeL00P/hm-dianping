package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,
                RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        if (shop==null) {
//            return Result.fail("查询失败,店铺不存在");
//        }
        return Result.ok(shop);

    }
    //缓存穿透
    public Shop queryWithPassThrough(Long id) {
        //从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            log.info("在Redis中查询到id={}店铺数据",shop.getId());
            return shop;
        }
        //判断缓存中命中的是否是空值
        if (StrUtil.isNotBlank(shopJson)) return null;
        //不存在在数据库中查询
        Shop byId = this.getById(id);
        if (byId==null) {
            //数据库也不存在，返回错误,将空值写入redis
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,"" ,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return null;
        }
        log.info("在数据库中中查询到id={}店铺数据",byId.getId());
        //存在，写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(byId),RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
        return byId;
    }

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //逻辑过期解决缓存击穿问题
    public Shop queryWithLogicExpire(Long id) {
        //从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //存在直接返回
            return null;
        }
        //存在判断是否过期
        //转换为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();//逻辑过期时间
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期直接返回
            return shop;
        }
        //过期,重建缓存
        //获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean lock = tryLock(lockKey);
        if (lock){
            //开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                //重建缓存
                try {
                    this.savaShop2Redis(id,RedisConstants.CACHE_SHOP_TTL);
                } catch (Exception e) {
                   throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }

            });
        }
        //直接返回过期数据
        return shop;
    }
    //互斥锁解决缓存穿透问题
    public Shop queryWithMutex(Long id) {
        //从redis中查询商铺缓存
        String key=RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            log.info("在Redis中查询到id={}店铺数据",shop.getId());
            return shop;
        }
        //判断缓存中命中的是否是空值
        if (StrUtil.isNotBlank(shopJson)) return null;
        //实现缓存重建
        //获取互斥锁、
        String lockKey= RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean lock = tryLock(lockKey);
            //判断是否成功
            if (!lock){
                //如果失败则休眠并重试
                Thread.sleep(50);
                queryWithMutex(id);
            }

            //不存在在数据库中查询
            shop = this.getById(id);
            //模拟重建的延时
            Thread.sleep(200);
            if (shop==null) {
                //数据库也不存在，返回错误,将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"" ,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return null;
            }
            log.info("在数据库中中查询到id={}店铺数据",shop.getId());
            //存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unlock(lockKey);
        }
        return shop;
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

    public void savaShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //查询店铺数据
        Shop shop = this.getById(id);
        Thread.sleep(200);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        redisData.setData(shop);
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + shop.getId(), JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id==null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        this.updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
