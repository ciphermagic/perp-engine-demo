package com.perp.model;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 交易账户
 * 持有可用余额，负责保证金的冻结与释放
 */
public class Account {

    private final String id;
    // 使用 AtomicReference 保证多线程下余额操作的可见性
    // 生产环境应使用数据库行锁 + 乐观锁版本号
    private final AtomicReference<BigDecimal> availableBalance;

    public Account(String id, BigDecimal initialBalance) {
        this.id = id;
        this.availableBalance = new AtomicReference<>(initialBalance);
    }

    /**
     * 冻结保证金（开仓时调用）
     * @return true 表示扣款成功
     */
    public boolean freezeMargin(BigDecimal amount) {
        while (true) {
            BigDecimal current = availableBalance.get();
            if (current.compareTo(amount) < 0) return false;       // 余额不足
            BigDecimal next = current.subtract(amount);
            if (availableBalance.compareAndSet(current, next)) return true;
        }
    }

    /**
     * 释放保证金 + 结算盈亏（平仓/强平时调用）
     */
    public void releaseMargin(BigDecimal margin, BigDecimal pnl) {
        BigDecimal net = margin.add(pnl);
        while (true) {
            BigDecimal current = availableBalance.get();
            // 余额不能为负（亏损超过保证金时按0处理，剩余亏损由保险基金承担）
            BigDecimal next = current.add(net).max(BigDecimal.ZERO);
            if (availableBalance.compareAndSet(current, next)) return;
        }
    }

    public String getId()                    { return id; }
    public BigDecimal getAvailableBalance()  { return availableBalance.get(); }

    @Override
    public String toString() {
        return String.format("Account[%s balance=%s]", id, availableBalance.get());
    }
}
