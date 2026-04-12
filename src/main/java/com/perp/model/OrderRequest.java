package com.perp.model;

import java.math.BigDecimal;

/** 下单请求 */
public class OrderRequest {
    private final String accountId;
    private final String symbol;
    private final Position.Side side;
    private final int leverage;
    private final BigDecimal size;
    /** 限价单价格；市价单传 null 则引擎用标记价 */
    private final BigDecimal price;

    public OrderRequest(String accountId, String symbol, Position.Side side,
                        int leverage, BigDecimal size, BigDecimal price) {
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.leverage = leverage;
        this.size = size;
        this.price = price;
    }

    public String accountId() { return accountId; }
    public String symbol() { return symbol; }
    public Position.Side side() { return side; }
    public int leverage() { return leverage; }
    public BigDecimal size() { return size; }
    public BigDecimal price() { return price; }
}
