package com.perp.matching;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 订单簿价格档位（Price Level）
 *
 * 同一价格的所有挂单按 FIFO 顺序排列在一个队列中。
 * 撮合时从队头开始消费（时间优先）。
 *
 * 数据结构选择：
 *   ArrayDeque — O(1) 头部弹出、尾部追加，无锁开销
 *   生产环境高频场景可换为 lock-free MPSC 队列
 */
public class PriceLevel {

    private final BigDecimal price;
    private final Deque<Order> orders = new ArrayDeque<>();
    private BigDecimal totalQty = BigDecimal.ZERO;

    public PriceLevel(BigDecimal price) {
        this.price = price;
    }

    /** 追加新委托到队尾 */
    public void add(Order order) {
        orders.addLast(order);
        totalQty = totalQty.add(order.remainingQty());
    }

    /** 查看队头委托（不移除） */
    public Order peek() {
        return orders.peekFirst();
    }

    /** 队头委托已全部成交或撤销，移除 */
    public void pollHead() {
        Order head = orders.pollFirst();
        if (head != null) totalQty = totalQty.subtract(head.getQty()).max(BigDecimal.ZERO);
    }

    /**
     * 主动移除指定委托（撤单时调用）
     * O(N) 但实际档位内委托数很少；生产场景可用 LinkedHashMap 优化为 O(1)
     */
    public boolean remove(Order order) {
        boolean removed = orders.remove(order);
        if (removed) {
            totalQty = totalQty.subtract(order.remainingQty()).max(BigDecimal.ZERO);
        }
        return removed;
    }

    /** 更新档位总量（部分成交后调用） */
    public void reduceQty(BigDecimal fillQty) {
        totalQty = totalQty.subtract(fillQty).max(BigDecimal.ZERO);
    }

    public boolean isEmpty()          { return orders.isEmpty(); }
    public BigDecimal getPrice()      { return price; }
    public BigDecimal getTotalQty()   { return totalQty; }
    public int        getOrderCount() { return orders.size(); }
}
