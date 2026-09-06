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
import org.springframework.beans.factory.annotation.Autowired;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ShopMapper shopMapper;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Autowired
    private CacheClient cacheClient;

    public Result queryById(Long id) {
        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("id 不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok(shop);
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    public Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        String s = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(s)){
            Shop shop = JSONUtil.toBean(s,Shop.class);
            return shop;
        }
        if (s != null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.HOURS);
            return null;
        }
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            while (!isLock) {
                Thread.sleep(50);
                isLock = tryLock(lockKey);
            }

            s = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(s)){
                shop = JSONUtil.toBean(s,Shop.class);
                return shop;
            }
            if (s != null) {
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.HOURS);
                return null;
            }

            shop = shopMapper.selectById(id);
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.HOURS);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return shop;
    }

    private void saveShop2Redis(Long id, Long expireSeconds) {
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    public Shop queryWithLogicExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String s = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(s)){
           return null;
        }
        RedisData data = JSONUtil.toBean(s,RedisData.class);
        LocalDateTime expireTime = data.getExpireTime();
        Shop shop = JSONUtil.toBean((JSONObject) data.getData(), Shop.class);
        if (expireTime.isBefore(LocalDateTime.now())) {
            String lock = RedisConstants.LOCK_SHOP_KEY + id;
            boolean isLock = tryLock(lock);
            if (isLock) {
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        this.saveShop2Redis(id, 30L);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        unlock(lock);
                    }
                });
            }
        }
        return shop;
    }

    public Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String s = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(s)){
            Shop shop = JSONUtil.toBean(s,Shop.class);
            return shop;
        }
        if (s != null) {
            return null;
        }
        Shop shop = shopMapper.selectById(id);
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.HOURS);
            return null;
        }
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        return shop;
    }
}
