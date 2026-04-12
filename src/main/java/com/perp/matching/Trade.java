package com.perp.matching;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 成交记录（Trade / Fill）
 *
 * 每次撮合成功产生一条 Trade，对应：
 * - taker 订单（主动成交方）
 * - maker 订单（挂单被动方）
 * - 成交价、成交量、成交时间
 *
 * 生产环境中 Trade 会：
 * 1. 写入成交流水表
 * 2. 推送至行情服务（更新 Last Price / 24H 成交量）
 * 3. 触发仓位服务开仓/平仓
 * 4. 触发手续费结算
 */
public record Trade(
        String id,
        String symbol,
        String takerOrderId,
        String makerOrderId,
        Order.Side takerSide, // taker 方向（BUY/SELL）
        BigDecimal price,
        BigDecimal qty,
        Instant timestamp) {
    public Trade(String symbol, String takerOrderId, String makerOrderId,
            Order.Side takerSide, BigDecimal price, BigDecimal qty) {
        this(UUID.randomUUID().toString(), symbol, takerOrderId, makerOrderId,
                takerSide, price, qty, Instant.now());
    }

    /** 成交名义价值 */
    public BigDecimal notional() {
        return price.multiply(qty);
    }

    @Override
    public String toString() {
        return String.format("Trade[%s %s price=%.2f qty=%s notional=%.2f]",
                symbol, takerSide, price, qty, notional());
    }
}
