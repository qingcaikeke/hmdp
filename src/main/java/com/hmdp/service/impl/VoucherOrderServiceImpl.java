package com.hmdp.service.impl;

import com.hmdp.dto.Result;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
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

    //1.根据id查秒杀券相关信息，需要用到秒杀券的service
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private RedisTemplate<String,String> redisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;//shift + f6批量重命名
    //静态代码块初始化静态常量
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //类bean初始化完成后立刻执行此方法
    @PostConstruct
    private void init(){
        //向线程池（执行器）提交一个任务，意味着任务将被异步完成,submit会执行run方法，类似于thread start执行run
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    //创建线程任务
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    //获取阻塞队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //因为使用新线程异步执行，所以不能从userholder中拿线程了
        Long userId = voucherOrder.getUserId();
        //获取锁对象，加锁，理论上不加也行，因为已经在redis中通过脚本判断过一人一单了
        RLock lock = redissonClient.getLock("lock:order" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            return;
        }
        try {
            //需要代理对象触发事务，但代理对象也是通过threadlocal，因此需要在原线程触发
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }
    private IVoucherOrderService proxy;
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
//        1.执行lua脚本
        Long result = redisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(), voucherId.toString(), userId.toString());
        int r = result.intValue();
        // 2.看结果是否为0 ，为0表示有资格
        if(result.intValue()!=0){
            return Result.fail(r==1? "库存不足" : "不能重复下单");
        }

        //生成订单：订单id+用户id+代金券id
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        orderTasks.add(voucherOrder);//至此抢单任务完成，后续通过线程池异步的从阻塞队列里拿任务进行下单
        //执行器需要拿到代理对象，两种方式，一种把proxy也放到阻塞队列，第二种把proxy变成成员变量
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }
//    public Result seckillVoucher(Long voucherId) {
//
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已结束");
//        }
//        if (voucher.getStock()<1) {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //存在问题，集群情况下每个jvm都可以加锁
//        //初始锁：synchronized (userId.toString().intern()) { 不要手动释放锁
//        // -> 改用自定义分布式锁版本1
//        //创建分布式锁对象,key是前缀lock+业务名order+用户id，val是线程id
//
//        //SimpleRedisLock lock  = new SimpleRedisLock(redisTemplate,"order:"+userId);
//        //获取锁,setnx是不可重入锁 redisson和ReentrantLock是可重入锁
//        //boolean isLock = lock.tryLock(1200);
//        RLock lock = redissonClient.getLock("lock:order" + userId);
//        boolean isLock = lock.tryLock();
//
//        if(!isLock){
//            return Result.fail("一人只能下一单");
//        }
//        //现在同时有一人一单的悲观锁和防止超售的乐观锁
//            //新问题：在同一个类的另一个方法中通过this关键字调用被@Transactional注解修饰的方法，事务可能不生效，原因是没有使用spring生成的代理对象
//            //获取代理对象
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//        //}
//    }

    //事务不意味着不会被其他线程抢占，只是说中途执行如果发生异常会回滚，以保证一致性
    @Transactional //涉及两张表的操作，加个事务
    //加悲观锁：1.在方法上加锁public synchronized Result createVoucherOrder
    //相当于加锁对象为this，不管哪个用户进来都需要加这个锁，而实际上希望根据用户id加锁
    //新问题：先释放锁，后提交事务，可能在提交之前又有新线程获得锁了
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //实现一人一单：去数据库查，看是否有订单号和用户id都相同的数据
        //存在并发问题，乐观锁适合更新数据，插入只能用悲观锁
        long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            return ;
        }
        //扣减库存，生成订单
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                //乐观锁解决超售，if判断有剩余库存后，更新时再判断一次，看当前数据库中的库存与最开始判断时的库存是否一致，一致才能更新
                // update table set stock = stock-1 where id = voucherID and stock = stockPre  stock自身就相当于一个版本号
                //.eq("stock",voucher.getStock())
                //但乐观锁太保守又有新问题，很多线程本来还有库存可以购买，但是因为判断版本号不一致，直接返回了库存不足，所以需要修改一下乐观锁
                .gt("stock", 0)//where id = ? and stock > 0 (gt:greater than)
                //也可使用分段锁（concurrentHashMap的实现）（分到十个表，有一个能抢到就行）
                .update();
        if (!success) {
            return ;
        }
        save(voucherOrder);
    }

//    @Transactional //涉及两张表的操作，加个事务
//    //加悲观锁：1.在方法上加锁public synchronized Result createVoucherOrder
//    //相当于加锁对象为this，不管哪个用户进来都需要加这个锁，而实际上希望根据用户id加锁
//    //新问题：先释放锁，后提交事务，可能在提交之前又有新线程获得锁了
//    public Result createVoucherOrder(Long voucherId){
//        //实现一人一单：去数据库查，看是否有订单号和用户id都相同的数据
//        //存在并发问题，乐观锁适合更新数据，插入只能用悲观锁
//        long userId = UserHolder.getUser().getId(); //threadlocal中有登录的用户信息
//        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        if (count > 0) {
//            return Result.fail("用户已经购买过");
//        }
//
//        //扣减库存，生成订单
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock-1")
//                .eq("voucher_id", voucherId)
//                //乐观锁解决超售，if判断有剩余库存后，更新时再判断一次，看当前数据库中的库存与最开始判断时的库存是否一致，一致才能更新
//                // update table set stock = stock-1 where id = voucherID and stock = stockPre  stock自身就相当于一个版本号
//                //.eq("stock",voucher.getStock())
//                //但乐观锁太保守又有新问题，很多线程本来还有库存可以购买，但是因为判断版本号不一致，直接返回了库存不足，所以需要修改一下乐观锁
//                .gt("stock", 0)//where id = ? and stock > 0 (gt:greater than)
//                //也可使用分段锁（concurrentHashMap的实现）（分到十个表，有一个能抢到就行）
//                .update();
//        if (!success) {
//            return Result.fail("库存不足");
//        }
//        //生成订单：订单id+用户id+代金券id
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        save(voucherOrder);
//        return Result.ok(orderId);
//    }
}
