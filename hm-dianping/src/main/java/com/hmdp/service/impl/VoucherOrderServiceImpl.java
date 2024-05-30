package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    private  static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private BlockingQueue<VoucherOrder> ordersTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run(){
             while (true){
                 try {
                     //获取队列中的订单信息
                     VoucherOrder voucherOrder = ordersTasks.take();
                     //创建订单
                     handleVoucherOrder(voucherOrder);
                 } catch (Exception e) {
                     e.printStackTrace();
                 }
             }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        /**
        //获取用户
         Long userId  = voucherOrder.getUserId();
         //获取锁对象
         RLock lock =redissonClient.getLock("lock:order:"+userId);
         //获取锁
         boolean isLock = lock.tryLock();
         if(!isLock){
             //获取锁失败 返回错误信息
             log.error("不允许重复下单");
             return;
         } **/
         try{
             //获取代理对象(事务）
              proxy.createVoucherOrder(voucherOrder);
         }finally {

         }
    }


    //@Transactional
    /**
     *  @Override
    public Result seckillVoucher(Long voucherId) {
        //1、查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2、判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀未开始");
        }
        //3、判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        //4 判断库存是否充足
        if(voucher.getStock()<1){
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
       //自定义锁
       // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //redisson锁
        RLock lock = redissonClient.getLock("order:" + userId);
        //获取锁 分布式锁
        boolean isLock = lock.tryLock();
        //判断释放获取锁成功
        if(!isLock){
             //获取锁失败返回错误或重试
              return Result.fail("不允许重复下单");
        }
        try {
            //获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
             lock.unlock();
        }
    } **/



    private IVoucherOrderService proxy;
    public Result seckillVoucher(Long voucherId) {
        //1、查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2、判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀未开始");
        }
        //3、判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }

        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1 执行lua脚本
        Long result =  stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        //2 判断结果是否为0
        int r = result.intValue();
        if(r!=0){
            //2.1 不为0 ，代表没有购买资格
            return Result.fail(r==1? "库存不足":"不能重复下单");
        }
        //2.2 为0 有购买资格 把下单信息保存到阻塞队列

        VoucherOrder voucherOrder = new VoucherOrder();
        //2.3 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //2.4用户id
        userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        //2.5 订单id
        voucherOrder.setVoucherId(voucherId);

        //2.6 放入阻塞队列
         ordersTasks.add(voucherOrder);

         //3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //3 返回订单id
        return Result.ok(orderId);
    }

    public void createVoucherOrder(VoucherOrder voucherOrder){
        //5 一人一单
        Long userId = voucherOrder.getUserId();

        int count = query().eq("user_id",userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count >0){
            //用户已经购买过了
            return;
        }

        //6 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0) //乐观锁 查询库存和修改时的库存是否一致 where id =? and stock>0
                .update();
        if(!success){
            return ;
        }

        //7 创建订单
        save(voucherOrder);


    }


    //原先版本
    /**public Result createVoucherOrder(Long voucherId){
        //5 一人一单
            Long userId = UserHolder.getUser().getId();

            int count = query().eq("user_id",userId).eq("voucher_id",voucherId).count();
            if(count >0){
                //用户已经购买过了
                return Result.fail("用户已经购买过一次了");
            }

            //6 扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock-1")
                    .eq("voucher_id",voucherId)
                    .gt("stock",0) //乐观锁 查询库存和修改时的库存是否一致 where id =? and stock>0
                    .update();
            if(!success){
                return Result.fail("库存不足");
            }

            //7 创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //7.1 订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //7.2用户id
            userId = UserHolder.getUser().getId();
            voucherOrder.setUserId(userId);
            //7.3 订单id
            voucherOrder.setVoucherId(voucherId);

            save(voucherOrder);
            return Result.ok(orderId);
            
    }**/
}
