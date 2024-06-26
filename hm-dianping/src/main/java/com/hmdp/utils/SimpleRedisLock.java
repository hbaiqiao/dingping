package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";

    private  static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate){
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程提示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String key = KEY_PREFIX + name;
        //获取锁
        Boolean success =  stringRedisTemplate.opsForValue()
                .setIfAbsent(key,threadId,timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }
    //调用Lua脚本

    public void unlock(){
        //调用lua脚本  保证判断和删除的原子性
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX +Thread.currentThread().getId()
        );
    }


//    @Override
//    public void unlock() {
//        //获取线程标示
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //获取锁中的标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断是否一致
//        if(threadId.equals(id)){
//            //释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//
//    }


}
