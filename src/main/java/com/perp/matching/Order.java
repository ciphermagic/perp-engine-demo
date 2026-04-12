package com.perp.matching;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 委托单
 *
 * 撮合引擎内部流转的核心对象。
 * 一个 Order 代表用户提交的一笔委托，在被完全成交或撤销前持续存在于订单簿中。
 *
 * 字段不可变（final），状态通过 filledQty / status 变化。
 * 生产环境应使用乐观锁版本号防止并发写冲突。
 */
public class Order implements Comparable<Order> {

    public enum Type   { LIMIT, MARKET }
    public enum Side   { BUY, SELL }
    public enum Status { PENDING, PARTIAL, FILLED, CANCELLED }

    private final String    id;
    private final String    accountId;
    private final String    symbol;
    private final Type      type;
    private final Side      side;
    private final BigDecimal price;     // MARKET 单为 null
    private final BigDecimal qty;       // 原始委托量（BTC）
    private final Instant   createdAt;

    // 可变状态
    private BigDecimal filledQty = BigDecimal.ZERO;
    private BigDecimal avgFillPrice = BigDecimal.ZERO;
    private Status     status = Status.PENDING;

    public Order(String accountId, String symbol, Type type, Side side,
                 BigDecimal price, BigDecimal qty) {
        this.id        = UUID.randomUUID().toString();
        this.accountId = accountId;
        this.symbol    = symbol;
        this.type      = type;
        this.side      = side;
        this.price     = price;
        this.qty       = qty;
        this.createdAt = Instant.now();
    }

    /** 剩余未成交量 */
    public BigDecimal remainingQty() {
        return qty.subtract(filledQty);
    }

    /** 是否已全部成交或撤销 */
    public boolean isDone() {
        return status == Status.FILLED || status == Status.CANCELLED;
    }

    /**
     * 记录一次成交（部分或全部）
     * 更新均价 = 加权均价
     */
    public void fill(BigDecimal fillQty, BigDecimal fillPrice) {
        // 更新加权均价
        BigDecimal prevNotional = avgFillPrice.multiply(filledQty);
        BigDecimal newNotional  = fillPrice.multiply(fillQty);
        filledQty    = filledQty.add(fillQty);
        avgFillPrice = filledQty.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : prevNotional.add(newNotional).divide(filledQty, 8, java.math.RoundingMode.HALF_UP);

        // 更新状态
        if (remainingQty().compareTo(BigDecimal.ZERO) == 0) status = Status.FILLED;
        else                                                  status = Status.PARTIAL;
    }

    public void cancel() { this.status = Status.CANCELLED; }

    /**
     * 订单簿排序规则（价格优先，时间次之）
     *
     * 买单：价格高的优先（降序）→ compareTo 返回负 = 排前面
     * 卖单：价格低的优先（升序）→ compareTo 返回正 = 排后面
     *
     * 注意：此处用于买单（BUY side）的 TreeMap key 排序。
     * 卖单使用独立 Comparator，见 OrderBook。
     */
    @Override
    public int compareTo(Order o) {
        // 同价格按时间先后（FIFO）
        int priceCmp = this.price.compareTo(o.price);
        if (priceCmp != 0) return priceCmp;
        return this.createdAt.compareTo(o.createdAt);
    }

    // ── Getters ──────────────────────────────────────────
    public String    getId()           { return id; }
    public String    getAccountId()    { return accountId; }
    public String    getSymbol()       { return symbol; }
    public Type      getType()         { return type; }
    public Side      getSide()         { return side; }
    public BigDecimal getPrice()       { return price; }
    public BigDecimal getQty()         { return qty; }
    public BigDecimal getFilledQty()   { return filledQty; }
    public BigDecimal getAvgFillPrice(){ return avgFillPrice; }
    public Status    getStatus()       { return status; }
    public Instant   getCreatedAt()    { return createdAt; }

    @Override
    public String toString() {
        return String.format("Order[%s %s %s %s qty=%s filled=%s price=%s status=%s]",
                id.substring(0, 8), symbol, side, type,
                qty, filledQty, price, status);
    }
}
