package com.perp.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 永续合约持仓（逐仓模式）
 *
 * 核心字段说明：
 *   notionalValue = size × entryPrice          // 名义价值
 *   margin        = notionalValue / leverage    // 初始保证金
 *   liqPrice      = 由 MarginEngine 计算        // 强平价
 */
public class Position {

    public enum Side { LONG, SHORT }
    public enum Status { OPEN, CLOSED, LIQUIDATED }

    private final String id;
    private final String accountId;
    private final String symbol;           // e.g. "BTC-USDT"
    private final Side side;
    private final int leverage;

    // 仓位核心数据
    private BigDecimal size;               // BTC 数量
    private BigDecimal entryPrice;         // 开仓均价
    private BigDecimal margin;             // 已占用保证金
    private BigDecimal liqPrice;           // 强平价

    // 累计已结算资金费
    private BigDecimal accumulatedFunding = BigDecimal.ZERO;

    private Status status = Status.OPEN;
    private final Instant openedAt;
    private Instant closedAt;

    public Position(String accountId, String symbol, Side side,
                    int leverage, BigDecimal size, BigDecimal entryPrice,
                    BigDecimal margin, BigDecimal liqPrice) {
        this.id = UUID.randomUUID().toString();
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.leverage = leverage;
        this.size = size;
        this.entryPrice = entryPrice;
        this.margin = margin;
        this.liqPrice = liqPrice;
        this.openedAt = Instant.now();
    }

    /**
     * 计算未实现盈亏（按标记价格）
     * LONG:  PnL = (markPrice - entryPrice) × size
     * SHORT: PnL = (entryPrice - markPrice) × size
     */
    public BigDecimal unrealizedPnl(BigDecimal markPrice) {
        BigDecimal priceDiff = side == Side.LONG
                ? markPrice.subtract(entryPrice)
                : entryPrice.subtract(markPrice);
        return priceDiff.multiply(size);
    }

    /**
     * 当前保证金余额 = 初始保证金 + 未实现盈亏 - 累计资金费
     */
    public BigDecimal currentMarginBalance(BigDecimal markPrice) {
        return margin
                .add(unrealizedPnl(markPrice))
                .subtract(accumulatedFunding);
    }

    /**
     * 当前保证金率 = 当前保证金余额 / 名义价值
     */
    public BigDecimal marginRate(BigDecimal markPrice) {
        BigDecimal notional = size.multiply(markPrice);
        if (notional.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return currentMarginBalance(markPrice).divide(notional, 8, java.math.RoundingMode.DOWN);
    }

    public void addFundingCharge(BigDecimal amount) {
        this.accumulatedFunding = this.accumulatedFunding.add(amount);
    }

    public BigDecimal getAccumulatedFunding() { return accumulatedFunding; }

    public void close() {
        this.status = Status.CLOSED;
        this.closedAt = Instant.now();
    }

    public void liquidate() {
        this.status = Status.LIQUIDATED;
        this.closedAt = Instant.now();
    }

    // ── Getters ──
    public String getId()             { return id; }
    public String getAccountId()      { return accountId; }
    public String getSymbol()         { return symbol; }
    public Side getSide()             { return side; }
    public int getLeverage()          { return leverage; }
    public BigDecimal getSize()       { return size; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public BigDecimal getMargin()     { return margin; }
    public BigDecimal getLiqPrice()   { return liqPrice; }
    public Status getStatus()         { return status; }
    public Instant getOpenedAt()      { return openedAt; }
    public Instant getClosedAt()      { return closedAt; }

    @Override
    public String toString() {
        return String.format("Position[%s %s %s x%d size=%s entry=%s liq=%s status=%s]",
                symbol, side, id.substring(0, 8), leverage, size, entryPrice, liqPrice, status);
    }
}
