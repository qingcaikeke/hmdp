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
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
    @Override

    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }
        if (voucher.getStock()<1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        //存在问题，集群情况下每个jvm都可以加锁
        synchronized (userId.toString().intern()) {
            //现在同时有一人一单的悲观锁和防止超售的乐观锁
            //新问题：在同一个类的另一个方法中通过this关键字调用被@Transactional注解修饰的方法，事务可能不生效，原因是没有使用spring生成的代理对象
            //获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }
    @Transactional //涉及两张表的操作，加个事务
    //加悲观锁：1.在方法上加锁public synchronized Result createVoucherOrder
    //相当于加锁对象为this，不管哪个用户进来都需要加这个锁，而实际上希望根据用户id加锁
    //新问题：先释放锁，后提交事务，可能在提交之前又有新线程获得锁了
    public Result createVoucherOrder(Long voucherId){
        //实现一人一单：去数据库查，看是否有订单号和用户id都相同的数据
        //存在并发问题，乐观锁适合更新数据，插入只能用悲观锁
        long userId = UserHolder.getUser().getId(); //threadlocal中有登录的用户信息
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已经购买过");
        }

        //扣减库存，生成订单
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock-1")
                .eq("voucher_id", voucherId)
                //乐观锁解决超售，if判断有剩余库存后，更新时再判断一次，看当前数据库中的库存与最开始判断时的库存是否一致，一致才能更新
                // update table set stock = stock-1 where id = voucherID and stock = stockPre  stock自身就相当于一个版本号
                //.eq("stock",voucher.getStock())
                //但乐观锁太保守又有新问题，很多线程本来还有库存可以购买，但是因为判断版本号不一致，直接返回了库存不足，所以需要修改一下乐观锁
                .gt("stock", 0)//where id = ? and stock > 0 (gt:greater than)
                //也可使用分段锁（concurrentHashMap的实现）（分到十个表，有一个能抢到就行）
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
        //生成订单：订单id+用户id+代金券id
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
