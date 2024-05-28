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
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
    @Override
    public Result queryById(long id) {
//        String key = RedisConstants.CACHE_SHOP_KEY +id;
//        //1.从redis查询商户缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //2. 判断是否存在
//        if(StrUtil.isNotBlank(shopJson)){
//            //3. 存在 直接返回
//            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
//            return Result.ok(shop);
//        }
//        //判断命中是否为空值
//        if(shopJson != null){
//            return Result.fail("店铺不存在！");
//        }
//
//        //4. 不存在 根据id查询数据库
//        Shop shop = getById(id);
//        //5 不存在 返回错误
//        if(shop == null){
//            //将空值写入redis
//            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(""),RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return Result.fail("店铺不存在！");
//        }
//        //6 存在 写入redis缓存
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//
//        return Result.ok(shop);


        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);

        //用逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);

        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);

    }
    //缓存穿透
    public Shop queryWithPassThrough(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY +id;
        //1.从redis查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在  既不是null 也不是 “”
        if(StrUtil.isNotBlank(shopJson)){
            //3. 存在 直接返回
            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }
        //判断命中是否为空值
        if(shopJson != null){
            return null;
        }

        //4. 不存在 根据id查询数据库
        Shop shop = getById(id);
        //5 不存在 返回错误
        if(shop == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(""),RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6 存在 写入redis缓存
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    //互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY +id;
        //1.从redis查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3. 存在 直接返回
            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }
        //判断命中是否为空值
        if(shopJson != null){
            return null;
        }
        // 4 实现缓存重建
        //4.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2 判断是否获取成功
            if(!isLock){
                //4.3 失败 休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4 成功 根据id查询数据库
            shop = getById(id);
            //模拟重建延迟
            Thread.sleep(200);
            //5 不存在 返回错误
            if(shop == null){
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(""),RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6 存在 写入redis缓存
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7 释放互斥锁
            unlock(lockKey);
        }
        //8 返回
        return shop;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑删除解决缓存击穿
    public Shop queryWithLogicalExpire(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY +id;
        //1.从redis查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在 未命中 直接返回 null和""
        if(StrUtil.isBlank(shopJson)){
            //3.存在 直接返回
           return null;
        }
        //4 命中 需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson,RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data,Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1 未过期 直接返回店铺信息
            return shop;
        }

        //5.2已过期 需要缓存重建
        //6 缓存重建
        //6.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Boolean isLock = tryLock(lockKey);
        //6.2 判断是否获取锁成功
        if(isLock){
            //6.3 成功 开启独立线程 实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally{
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        return shop;
    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        //防止出现空指针
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    public void  saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1 查询店铺数据
        Shop shop  = getById(id);
//        if(shop == null){
//            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(""),RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//        }
        Thread.sleep(200);
        //2 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3 写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));

    }
    public Result update(Shop shop){
        Long id =shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        //1 更新数据库
        updateById(shop);
        //2 删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY +id);
        return Result.ok();
    }
}
