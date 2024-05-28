package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    //逻辑过期 需要提前存入数据
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //缓存穿透
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id , Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key = keyPrefix +id;
        //1.从redis查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在  既不是null 也不是 “”
        if(StrUtil.isNotBlank(json)){
            //3. 存在 直接返回
            //反序列化
            return JSONUtil.toBean(json,type);
        }
        //判断命中是否为空值 是否为 “”
        if(json != null){
            return null;
        }
        //4. 不存在 根据id查询数据库 传入函数
        R r = dbFallback.apply(id);
        //5 不存在 返回错误
        if(r == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(""),RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6 存在 写入redis缓存
        this.set(key,r,time,unit);

        return r;
    }

    //用互斥锁解决缓存击穿  缓存穿透+ 互斥锁 缓存重建让查询线程等待
    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, type);
        }
        // 判断命中的是否是空值
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.实现缓存重建
        // 4.1.获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 4.4.获取锁成功，根据id查询数据库
            r = dbFallback.apply(id);
            // 5.不存在，返回错误
            if (r == null) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
            // 6.存在，写入redis
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 7.释放锁
            unlock(lockKey);
        }
        // 8.返回
        return r;
    }


    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑删除解决缓存击穿 需要提前在redis存入数据 用于热点商品 如双十一抢购  在重建过程中直接返回旧数据
    public <R,ID> R queryWithLogicalExpire(String keyprefix, ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key = keyprefix +id;
        //1.从redis查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在 未命中 直接返回 null和""
        if(StrUtil.isBlank(json)){
            //3.存在 直接返回
            return null;
        }
        //4 命中 需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json,RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data,type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1 未过期 直接返回店铺信息
            return r;
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
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally{
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        return r;
    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        //防止出现空指针
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }



}
