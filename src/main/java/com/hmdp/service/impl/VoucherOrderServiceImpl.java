package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
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
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author fj
 * @since 2023-3-6
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
    }
    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try{
                    //1 获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                }catch (Exception e){
                    log.error("处理订单异常");
                    e.printStackTrace();
                }
            }
        }

    }
    public void handleVoucherOrder(VoucherOrder voucherOrder){
        Long userId = UserHolder.getUser().getId();
        //创建锁对象
        RLock redissonLock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = redissonLock.tryLock();
        if (!isLock){
            //获取锁失败返回错误或者重试
            return;
        }
        try{
             proxy.createVoucherOrder(voucherOrder);
        }finally {
            //释放锁
            //redisLock.unlock();
            redissonLock.unlock();
        }
    }
    IVoucherOrderService  proxy;
    //    @Override
//    @Transactional
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否已经开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //未开始
//            return Result.fail("秒杀尚未开始");
//        }
//        //判断秒杀是否已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //已结束
//            return Result.fail("秒杀已结束");
//        }
//        //判断库存是否充足
//        if (voucher.getStock() < 1) {
//            //库存不足
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //synchronized(userId.toString().intern()) {}
//        //创建锁对象
////        SimpleRedisLock redisLock = new SimpleRedisLock(stringRedisTemplate,"order:"+userId);
////        boolean isLock = redisLock.tryLock(10);
//        RLock redissonLock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = redissonLock.tryLock();
//        if (!isLock){
//            //获取锁失败返回错误或者重试
//            return Result.fail("不允许重复下单");
//        }
//        try{
//            //拿到当前对象的代理对象
//            IVoucherOrderService  proxy =(IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            //释放锁
//            //redisLock.unlock();
//            redissonLock.unlock();
//        }
//
//
//
//
//    }
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //long orderId = redisIdWorker.nextId("order");
        //1 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(),userId.toString()
        );
        //判断结果是为0
        assert result != null;
        int r = result.intValue();
        if (r!=0){
            //2.1 不为0，代表没有购买资格
            return Result.fail(r==1?"库存不足":"重复下单");
        }
        //2.2 为0，代表已经购买资格，把下单信息返回到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        //用户id
        UserDTO user = UserHolder.getUser();
        voucherOrder.setUserId(user.getId());
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列
        orderTasks.add(voucherOrder);
        //拿到当前对象的代理对象
        proxy =(IVoucherOrderService) AopContext.currentProxy();

        //返回订单Id
        return Result.ok(orderId);
    }
    @Transactional
    @Override
    public  void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        //Long userId = UserHolder.getUser().getId();
        Long userId = voucherOrder.getUserId();
        Long voucherId=voucherOrder.getVoucherId();
        //查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            //订单已存在
            log.error("该用户已经购买过了");
        }
        //扣减库存
        boolean b = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock",0).update();
        if (!b) {
            //扣减库存失败
            log.error("扣减库存失败");
        }
//        //创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId  = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户id
        UserDTO user = UserHolder.getUser();
        voucherOrder.setUserId(user.getId());
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        boolean b1 = this.save(voucherOrder);
        if (!b1) {
            //创建订单失败
            log.error("创建订单失败");
        }
        //返回订单Id
        // return Result.ok(orderId);
    }

}
