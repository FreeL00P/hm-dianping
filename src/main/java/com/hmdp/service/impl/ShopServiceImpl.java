package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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

    @Override
    public Result queryById(Long id) {
        //从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            log.info("在Redis中查询到id={}店铺数据",shop.getId());
            return Result.ok(shop);
        }
        //不存在在数据库中查询
        Shop byId = this.getById(id);
        if (byId==null) {
            //数据库也不存在，返回错误
            return Result.fail("商铺信息不存在");
        }
        log.info("在数据库中中查询到id={}店铺数据",byId.getId());
        //存在，写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(byId),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(byId);
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
