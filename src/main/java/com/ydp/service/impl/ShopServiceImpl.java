package com.ydp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ydp.dto.Result;
import com.ydp.entity.Shop;
import com.ydp.mapper.ShopMapper;
import com.ydp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ydp.utils.RedisConstants;
import com.ydp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.ydp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        // Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
        Shop shop = queryWithLogicExpire(id);


        if(shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 逻辑过期解决缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithLogicExpire(Long id) {
        // 1. 从Redis中查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断缓存是否命中
        if (StrUtil.isBlank(shopJson)) {
            //3. 未命中, 直接返回
            return null;
        }
        // 4. 命中，需要先把JSON反序列化未对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5 判断缓存是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期， 返回商铺信息
            return shop;
        }
        // 5.2 过期，需要缓存重建
        // 6. 缓存重建
        // 6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2 判断是否获取锁成功
        if(isLock) {
            // 6.3 成功，开启独立线程（建议线程池），实现缓存重建
            // 注意，此处获取锁成功后应该再次检测redis缓存是否过期，做DoubleCheck，如果存在则无需缓存重建
            if(expireTime.isAfter(LocalDateTime.now())) {
                return shop;
            }
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                // 重建缓存
                try {
                    // test
                    this.saveShop2Redis(id, 20L);
                    // 实际需要30分钟
                    // this.saveShop2Redis(id, CACHE_SHOP_TTL);
                    // 释放锁
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });

        }
        // 6.4 返回商铺信息
        return shop;
    }

    /**
     * 互斥锁预防缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        // 1. 从Redis中查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 命中 返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断命中的是否为空值(预防缓存穿透)
        if(shopJson != null) {
            return null;
        }
        // 4. 实现缓存重建
        // 4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2 判断是否获取成功
            if(!isLock) {
                // 4.3 获取失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4 获取成功，根据id查询数据库
            shop = getById(id);
            Thread.sleep(200);
            // 5. 不存在，返回错误
            if(shop == null) {
                // 将null值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6. 存在，写入Redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7. 释放互斥锁
            unLock(lockKey);
        }
        // 8. 返回
        return shop;
    }

    /**
     * 存储空值预防缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        // 1. 从Redis中查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 命中 返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断命中的是否为空值（预防缓存穿透）
        if(shopJson != null) {
            return null;
        }
        // 4. 未命中 查询数据库
        Shop shop = getById(id);
        // 5. 不存在，返回错误
        if(shop == null) {
            // 将null值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6. 存在，写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7. 返回
        return shop;
    }


    /**
     * 获取redis锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放redis锁
     * @param key
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1. 查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}
