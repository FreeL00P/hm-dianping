package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author fj
 * @since 2023-3-2
 */
@Service
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryList() {
        //从redis中查询
        String shopType = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_KEY);
        //redis中是否存在
        if(StrUtil.isNotBlank(shopType)){
            //存在直接返回
            log.info("在Redis中查询到店铺类型列表");
            return Result.ok(JSONUtil.toList(shopType,ShopType.class));
        }
        //redis中不存在去数据库中查询
        List<ShopType> typeList = this
                .query().orderByAsc("sort").list();
        if (typeList==null){
            return Result.fail("店铺类型信息不存在");
        }
        log.info("在数据库中查询到店铺类型列表");
        //保存到redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);
    }
}
