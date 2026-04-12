package com.perp.matching;

import java.math.BigDecimal;
import java.util.*;

/**
 * 订单簿（Order Book）
 *
 * 核心数据结构：
 *
 * 卖单（asks）：TreeMap<BigDecimal, PriceLevel> 升序（最低卖价在头部）
 * 买单（bids）：TreeMap<BigDecimal, PriceLevel> 降序（最高买价在头部）
 *
 * 撮合条件：
 * 买单最优价（bestBid）≥ 卖单最优价（bestAsk）时可撮合
 *
 * 价格优先 → 时间优先（FIFO），由 PriceLevel 内部 Deque 保证
 *
 * 时间复杂度：
 * 挂单：O(log N) — TreeMap 插入
 * 撮合：O(k log N) — k 为成交档位数，通常 k 很小
 * 查询最优价：O(1) — firstKey()
 * 深度查询：O(D) — 遍历前 D 档
 *
 * 线程安全：本类非线程安全，调用方（MatchingEngine）负责加锁
 */
public class OrderBook {

    private final String symbol;

    // 卖单：价格升序，最低 ask 在 firstKey()
    private final TreeMap<BigDecimal, PriceLevel> asks = new TreeMap<>(Comparator.naturalOrder());

    // 买单：价格降序，最高 bid 在 firstKey()
    private final TreeMap<BigDecimal, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());

    // 按 orderId 快速定位委托（用于撤单）
    private final Map<String, Order> orderIndex = new HashMap<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    // ── 挂单 ─────────────────────────────────────────────

    /**
     * 将限价单加入订单簿
     */
    public void addLimitOrder(Order order) {
        TreeMap<BigDecimal, PriceLevel> book = bookFor(order.getSide());
        book.computeIfAbsent(order.getPrice(), PriceLevel::new)
                .add(order);
        orderIndex.put(order.getId(), order);
    }

    // ── 撤单 ─────────────────────────────────────────────

    /**
     * 撤销委托
     * O(log N)：先找到价格档位，再从档位移除（档位内 O(N) 最坏情况，实际极少）
     */
    public boolean cancel(String orderId) {
        Order order = orderIndex.remove(orderId);
        if (order == null || order.isDone())
            return false;
        order.cancel();
        // 主动从档位移除，确保 bestAsk/bestBid 和深度快照准确
        TreeMap<BigDecimal, PriceLevel> book = bookFor(order.getSide());
        PriceLevel level = book.get(order.getPrice());
        if (level != null) {
            level.remove(order);
            if (level.isEmpty())
                book.remove(order.getPrice());
        }
        return true;
    }

    // ── 查询 ─────────────────────────────────────────────

    /** 最优卖价（最低 ask），无挂单时返回 null */
    public BigDecimal bestAsk() {
        return asks.isEmpty() ? null : asks.firstKey();
    }

    /** 最优买价（最高 bid），无挂单时返回 null */
    public BigDecimal bestBid() {
        return bids.isEmpty() ? null : bids.firstKey();
    }

    /** 中间价 = (bestBid + bestAsk) / 2 */
    public Optional<BigDecimal> midPrice() {
        if (bestAsk() == null || bestBid() == null)
            return Optional.empty();
        return Optional.of(bestBid().add(bestAsk())
                .divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP));
    }

    /**
     * 获取盘口深度快照（行情推送用）
     *
     * @param depth 档位数量
     * @return [asks档位列表（升序）, bids档位列表（降序）]
     */
    public List<PriceLevel>[] snapshot(int depth) {
        @SuppressWarnings("unchecked")
        List<PriceLevel>[] result = new List[2];
        result[0] = topLevels(asks, depth);
        result[1] = topLevels(bids, depth);
        return result;
    }

    // ── 内部访问（供 MatchingEngine 使用）─────────────────

    TreeMap<BigDecimal, PriceLevel> getAsks() {
        return asks;
    }

    TreeMap<BigDecimal, PriceLevel> getBids() {
        return bids;
    }

    Map<String, Order> getOrderIndex() {
        return orderIndex;
    }

    // ── 私有工具 ─────────────────────────────────────────

    private TreeMap<BigDecimal, PriceLevel> bookFor(Order.Side side) {
        return side == Order.Side.SELL ? asks : bids;
    }

    @SuppressWarnings("unused")
    private void cleanEmptyLevel(TreeMap<BigDecimal, PriceLevel> book, BigDecimal price) {
        PriceLevel level = book.get(price);
        if (level != null && level.isEmpty())
            book.remove(price);
    }

    private List<PriceLevel> topLevels(TreeMap<BigDecimal, PriceLevel> book, int depth) {
        List<PriceLevel> result = new ArrayList<>();
        int count = 0;
        for (PriceLevel level : book.values()) {
            if (count++ >= depth)
                break;
            if (!level.isEmpty())
                result.add(level);
        }
        return result;
    }

    public String getSymbol() {
        return symbol;
    }
}
