package com.perp.matching;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * 撮合引擎（Matching Engine）
 *
 * ┌─────────────────────────────────────────────────────────┐
 * │  撮合流程                                                 │
 * │                                                         │
 * │  submitOrder(order)                                     │
 * │      │                                                  │
 * │      ├── MARKET 单 ──→ matchMarket()                    │
 * │      │                    └── 以对手盘最优价持续撮合      │
 * │      │                        直到全部成交或对手盘耗尽    │
 * │      │                                                  │
 * │      └── LIMIT 单  ──→ matchLimit()                     │
 * │                           ├── 先尝试撮合（taker 逻辑）   │
 * │                           └── 有剩余 → addLimitOrder()  │
 * │                               挂入订单簿（maker 逻辑）   │
 * │                                                         │
 * │  每次撮合产生 Trade，推送至成交流水                         │
 * └─────────────────────────────────────────────────────────┘
 *
 * 线程安全策略：
 *   每个 symbol 持有独立的 ReentrantLock。
 *   高吞吐场景可升级为 Disruptor + 单线程 per symbol。
 */
public class MatchingEngine {

    private static final Logger log = Logger.getLogger(MatchingEngine.class.getName());

    // symbol → OrderBook
    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    // symbol → 独立撮合锁（避免不同合约互相阻塞）
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    // 最新成交价（用作市价单参考 & 标记价格）
    private final Map<String, BigDecimal> lastTradePrices = new ConcurrentHashMap<>();

    // 成交回调（生产环境替换为 Kafka Producer / Disruptor EventHandler）
    private TradeListener tradeListener = trade -> {};

    // ── 公开 API ─────────────────────────────────────────

    /**
     * 注册合约
     */
    public void registerSymbol(String symbol, BigDecimal initialPrice) {
        books.put(symbol, new OrderBook(symbol));
        locks.put(symbol, new ReentrantLock());
        lastTradePrices.put(symbol, initialPrice);
        log.info("[ENGINE] Registered symbol: " + symbol + " initPrice=" + initialPrice);
    }

    /**
     * 提交委托单，返回本次撮合产生的所有 Trade
     *
     * 加锁保证同一 symbol 的订单严格串行处理，防止双重成交。
     */
    public List<Trade> submitOrder(Order order) {
        String symbol = order.getSymbol();
        ReentrantLock lock = locks.get(symbol);
        if (lock == null) throw new IllegalArgumentException("Unknown symbol: " + symbol);

        lock.lock();
        try {
            return order.getType() == Order.Type.MARKET
                    ? matchMarket(order)
                    : matchLimit(order);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 撤单
     */
    public boolean cancelOrder(String symbol, String orderId) {
        ReentrantLock lock = locks.get(symbol);
        if (lock == null) return false;
        lock.lock();
        try {
            return getBook(symbol).cancel(orderId);
        } finally {
            lock.unlock();
        }
    }

    public OrderBook getBook(String symbol)           { return books.get(symbol); }
    public BigDecimal lastTradePrice(String symbol)   { return lastTradePrices.get(symbol); }
    public void setTradeListener(TradeListener l)     { this.tradeListener = l; }

    // ── 市价单撮合 ────────────────────────────────────────

    /**
     * 市价单：持续吃对手盘，直到全部成交或对手盘耗尽
     *
     * 买单吃 asks（从最低 ask 开始）
     * 卖单吃 bids（从最高 bid 开始）
     */
    private List<Trade> matchMarket(Order taker) {
        List<Trade> trades = new ArrayList<>();
        OrderBook book = getBook(taker.getSymbol());

        var counterBook = taker.getSide() == Order.Side.BUY
                ? book.getAsks()   // 买入 → 吃卖单
                : book.getBids();  // 卖出 → 吃买单

        while (!taker.isDone() && !counterBook.isEmpty()) {
            var entry    = counterBook.firstEntry();
            PriceLevel level = entry.getValue();
            BigDecimal levelPrice = entry.getKey();

            List<Trade> levelTrades = matchAtLevel(taker, level, levelPrice, book.getSymbol());
            trades.addAll(levelTrades);

            if (level.isEmpty()) counterBook.pollFirstEntry();
        }

        if (!taker.isDone()) {
            // 市价单未能全部成交（对手盘不足），剩余部分撤销
            log.warning(String.format("[MARKET] Order %s partially filled, remaining %s cancelled",
                    taker.getId().substring(0, 8), taker.remainingQty()));
            taker.cancel();
        }
        return trades;
    }

    // ── 限价单撮合 ────────────────────────────────────────

    /**
     * 限价单：先尝试以 taker 身份撮合，剩余挂单
     *
     * 撮合条件：
     *   BUY  limit：order.price ≥ bestAsk  → 可吃
     *   SELL limit：order.price ≤ bestBid  → 可吃
     */
    private List<Trade> matchLimit(Order taker) {
        List<Trade> trades = new ArrayList<>();
        OrderBook book = getBook(taker.getSymbol());

        if (taker.getSide() == Order.Side.BUY) {
            // 买单：吃价格 ≤ taker.price 的卖单
            while (!taker.isDone() && !book.getAsks().isEmpty()) {
                BigDecimal bestAsk = book.getAsks().firstKey();
                if (taker.getPrice().compareTo(bestAsk) < 0) break;  // 价格不够，不撮合

                PriceLevel level = book.getAsks().firstEntry().getValue();
                trades.addAll(matchAtLevel(taker, level, bestAsk, book.getSymbol()));
                if (level.isEmpty()) book.getAsks().pollFirstEntry();
            }
        } else {
            // 卖单：吃价格 ≥ taker.price 的买单
            while (!taker.isDone() && !book.getBids().isEmpty()) {
                BigDecimal bestBid = book.getBids().firstKey();
                if (taker.getPrice().compareTo(bestBid) > 0) break;

                PriceLevel level = book.getBids().firstEntry().getValue();
                trades.addAll(matchAtLevel(taker, level, bestBid, book.getSymbol()));
                if (level.isEmpty()) book.getBids().pollFirstEntry();
            }
        }

        // 剩余未成交部分 → 挂入订单簿成为 maker
        if (!taker.isDone()) {
            book.addLimitOrder(taker);
            log.fine(String.format("[LIMIT] Order %s resting in book price=%s remaining=%s",
                    taker.getId().substring(0, 8), taker.getPrice(), taker.remainingQty()));
        }
        return trades;
    }

    // ── 档位内撮合（核心逻辑）────────────────────────────

    /**
     * 在一个价格档位内完成撮合，FIFO 消费 maker 队列
     *
     * 成交价 = maker 价格（价格优先原则：taker 享受 maker 价格）
     */
    private List<Trade> matchAtLevel(Order taker, PriceLevel level,
                                     BigDecimal matchPrice, String symbol) {
        List<Trade> trades = new ArrayList<>();

        while (!taker.isDone() && !level.isEmpty()) {
            Order maker = level.peek();

            // 惰性清理：跳过已完成的 maker 订单
            if (maker.isDone()) {
                level.pollHead();
                continue;
            }

            // 计算本次成交量 = min(taker剩余, maker剩余)
            BigDecimal fillQty = taker.remainingQty().min(maker.remainingQty());

            // 双边成交
            taker.fill(fillQty, matchPrice);
            maker.fill(fillQty, matchPrice);
            level.reduceQty(fillQty);

            // 更新最新成交价
            lastTradePrices.put(symbol, matchPrice);

            // 产生 Trade
            Trade trade = new Trade(symbol,
                    taker.getId(), maker.getId(),
                    taker.getSide(), matchPrice, fillQty);
            trades.add(trade);
            tradeListener.onTrade(trade);

            log.fine(String.format("[MATCH] %s", trade));

            // maker 全部成交 → 移出档位
            if (maker.isDone()) level.pollHead();
        }
        return trades;
    }

    // ── 回调接口 ─────────────────────────────────────────

    @FunctionalInterface
    public interface TradeListener {
        void onTrade(Trade trade);
    }
}
