package com.ydp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.ydp.entity.ShopType;
import com.ydp.mapper.ShopTypeMapper;
import com.ydp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.ydp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> getTypeList() {
        String key = CACHE_SHOP_TYPE_KEY;
        // 1. 查询Redis缓存
        String shopTypeJsonArray = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断缓存是否命中
        if(StrUtil.isNotBlank(shopTypeJsonArray)) {
            // 3. 命中，返回商铺列表
            List<ShopType> typeList = JSONUtil.toList(shopTypeJsonArray, ShopType.class);
            return typeList;
        }

        // 4. 未命中，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 5. 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList));
        // 6. 返回
        return typeList;
    }
}
