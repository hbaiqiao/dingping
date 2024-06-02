package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private ExecutorService es = Executors.newFixedThreadPool(500);


    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(100);
        Runnable task = () -> {
            for(int i =0;i<100;i++){
                long id = redisIdWorker.nextId("order");
                System.out.println("id=" +id);
            }
            latch.countDown();
        };
        long begin  = System.currentTimeMillis();
        for(int i = 0;i<100;i++){
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time=" + (end-begin));
    }
    @Test
    void testSavaShop(){
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY + 1L,shop,10L, TimeUnit.SECONDS);
    }

    @Test
    void testGetAll() {
        List<User> users = userService.list();
        users.forEach(
                user -> {
                    //          7.1,随机生成token,作为登录令牌
                    String token = UUID.randomUUID().toString(true);
//        7.2,将User对象转化为HashMap存储
                    UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
                    File file = new File("src/main/resources/token.txt");
                    FileOutputStream output = null;
                    try {
                        output = new FileOutputStream(file, true);
                        byte[] bytes = token.getBytes();
                        output.write(bytes);
                        output.write("\r\n".getBytes());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        try {
                            output.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                            CopyOptions.create()
                                    .setIgnoreNullValue(true)
                                    .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
//        7.3,存储
                    String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
                    stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
//        7.4,设置token有效期
                    stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
                }
        );
    }

    @Test
    void LoadhopData(){
        //1 查询店铺信息
        List<Shop> list = shopService.list();
        //2 把店铺分组 按照typeId 分组 ，typeId一致的放到一个集合
        Map<Long, List<Shop>> map = list
                .stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3 分批完成写入Redis
        for (Map.Entry<Long,List<Shop>> entry: map.entrySet()){
               //3.1 获取类型id
                Long typeId = entry.getKey();
                String key = RedisConstants.SHOP_GEO_KEY + typeId;
                //3.2 获取同类型的店铺的集合
                List<Shop> value = entry.getValue();
                List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
                //3.3 写入redis GEOADD key 经度纬度 member
                for(Shop shop: value){
//                    stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(), shop.getY()),shop.getId().toString());
                      locations.add(new RedisGeoCommands.GeoLocation<>(
                              shop.getId().toString(),new Point(shop.getX(), shop.getY())
                      ));
                }
                stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }

}
