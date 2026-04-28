# Disruptor 改造方案

> 将撮合引擎从 ReentrantLock 同步模型改造为 LMAX Disruptor 无锁单线程模型，
> 解除撮合线程与下游业务（开仓、风控、行情）的耦合，大幅提升吞吐量与尾延迟。

---

## 一、现状分析

### 1.1 当前调用链路

```
用户提交订单
    │
    ▼
MatchingEngine.submitOrder()
    │  ReentrantLock.lock()          ← 同 symbol 串行
    │
    ├─ matchMarket() / matchLimit()
    │   └─ matchAtLevel()
    │       ├─ taker.fill() / maker.fill()
    │       ├─ new Trade(...)
    │       └─ tradeListener.onTrade(trade)   ← ⚠️ 同步回调，在锁内执行
    │           │
    │           ├─ PositionService.openPosition()
    │           │   ├─ MarginEngine.calcInitialMargin()
    │           │   ├─ Account.freezeMargin()  (CAS 自旋)
    │           │   └─ MarginEngine.calcLiquidationPrice()
    │           │
    │           └─ （生产环境还需要：行情推送、手续费结算...）
    │
    │  ReentrantLock.unlock()
    ▼
返回 List<Trade>
```

### 1.2 核心问题

| 问题 | 影响 |
|------|------|
| **TradeListener 在撮合锁内同步执行** | 下游越慢，撮合吞吐越低 |
| **ReentrantLock 多线程竞争** | 锁竞争 + 上下文切换导致尾延迟不可控 |
| **GC 不可预测** | Trade/Order 对象频繁创建，GC Pause 影响 P99 |
| **无背压机制** | 下游处理不过来时无法反压上游 |

### 1.3 线程安全机制现状

| 模块 | 机制 | 备注 |
|------|------|------|
| MatchingEngine | `ReentrantLock` per symbol | 撮合主锁 |
| OrderBook / PriceLevel | 无锁 | 依赖 MatchingEngine 的锁保护 |
| Account | `AtomicReference<BigDecimal>` + CAS | 无重试上限 |
| PositionService | `ConcurrentHashMap` | Map 级别安全，对象级别无保护 |
| Position | 无同步 | 依赖调用方保证顺序 |

---

## 二、目标架构

### 2.1 整体设计

```
                    ┌───────────────────────────────────────────────────┐
  submitOrder() ──→ │           Order RingBuffer (4096 slots)          │
  (多线程发布)       │  [OrderEvent] → [OrderEvent] → [OrderEvent] → ... │
                    └───────────────────────┬───────────────────────────┘
                                            │
                                  MatchHandler (单线程消费)
                                  ┌─────────┴──────────┐
                                  │  撮合逻辑（无锁）    │
                                  │  matchMarket()      │
                                  │  matchLimit()       │
                                  │  matchAtLevel()     │
                                  └─────────┬──────────┘
                                            │ 产生 Trade
                                            ▼
                    ┌───────────────────────────────────────────────────┐
                    │           Trade RingBuffer (1024 slots)          │
                    │  [TradeEvent] → [TradeEvent] → [TradeEvent] → ...│
                    └────────┬──────────┬──────────┬────────────────────┘
                             │          │          │
                    TradeHandler   RiskHandler   MarketDataHandler
                    (开仓/平仓)    (强平扫描)     (行情推送)
                     独立线程       独立线程        独立线程
```

### 2.2 设计原则

| 原则 | 说明 |
|------|------|
| **单线程写 per symbol** | Disruptor 消费端单线程，天然串行，无需加锁 |
| **对象预分配** | RingBuffer 中的 Event 对象在启动时一次性分配，撮合时只写字段不创建对象 |
| **机械共鸣** | BusySpinWaitStrategy 避免上下文切换，适合独占 CPU 核心的低延迟场景 |
| **事件驱动解耦** | 撮合只负责产出 Trade，下游各自消费，互不阻塞 |

### 2.3 改造前后对比

| 指标 | 改造前（ReentrantLock） | 改造后（Disruptor） |
|------|----------------------|-------------------|
| 撮合线程模型 | 多线程竞争锁 | **单线程 per symbol，无锁** |
| Trade 消费 | 同步回调，阻塞撮合 | **异步 RingBuffer，不阻塞** |
| 对象分配 | 每次 new Trade/Order | **预分配 Event，零 GC** |
| 延迟（P99） | ~50-200μs（锁+GC） | **< 1μs**（LMAX 公开基准） |
| 吞吐量 | ~10 万 orders/s | **~600 万 orders/s** |
| 背压 | 无 | **RingBuffer 满时发布者阻塞** |
| 下游扩展 | 修改 TradeListener | **新增 EventHandler 即可** |

---

## 三、新增文件清单

### 3.1 目录结构

```
src/main/java/com/perp/
├── disruptor/                          ← 新增目录
│   ├── OrderEvent.java                 订单事件（RingBuffer 载体）
│   ├── TradeEvent.java                 成交事件（RingBuffer 载体）
│   ├── MatchHandler.java               撮合处理器（单线程消费者）
│   ├── TradeHandler.java               成交处理器（开仓/平仓）
│   ├── RiskHandler.java                风控处理器（强平扫描）
│   └── DisruptorMatchingEngine.java    Disruptor 编排 & 对外 API
```

### 3.2 OrderEvent.java

```java
package com.perp.disruptor;

import com.lmax.disruptor.EventFactory;
import com.perp.matching.Order;
import com.perp.matching.Trade;

import java.util.List;

/**
 * 订单事件 — RingBuffer 中预分配的载体对象
 *
 * 设计要点：
 *   - 字段可变，通过 set() 复用对象，避免 GC
 *   - result 用于同步场景下获取撮合结果（可选）
 */
public class OrderEvent {

    private Order order;
    private List<Trade> result;

    public void set(Order order) {
        this.order = order;
        this.result = null;
    }

    public Order getOrder()              { return order; }
    public List<Trade> getResult()       { return result; }
    public void setResult(List<Trade> r) { this.result = r; }

    /** 清理引用，帮助 GC（可选，Disruptor 支持配置自动清理） */
    public void clear() {
        this.order = null;
        this.result = null;
    }

    public static final EventFactory<OrderEvent> FACTORY = OrderEvent::new;
}
```

### 3.3 TradeEvent.java

```java
package com.perp.disruptor;

import com.lmax.disruptor.EventFactory;
import com.perp.matching.Trade;

/**
 * 成交事件 — 撮合产出后发布到下游 RingBuffer
 */
public class TradeEvent {

    private Trade trade;

    public void set(Trade trade) { this.trade = trade; }
    public Trade getTrade()      { return trade; }

    public void clear() { this.trade = null; }

    public static final EventFactory<TradeEvent> FACTORY = TradeEvent::new;
}
```

### 3.4 MatchHandler.java

```java
package com.perp.disruptor;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.perp.matching.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 撮合处理器 — 单线程消费 OrderEvent
 *
 * 核心撮合逻辑从 MatchingEngine 提取而来，
 * 无需加锁：Disruptor 保证同一 symbol 的事件严格串行到达。
 */
public class MatchHandler implements EventHandler<OrderEvent> {

    private static final Logger log = Logger.getLogger(MatchHandler.class.getName());

    private final OrderBook book;
    private final RingBuffer<TradeEvent> tradeRingBuffer;
    private final Map<String, BigDecimal> lastTradePrices;

    public MatchHandler(OrderBook book,
                        RingBuffer<TradeEvent> tradeRingBuffer,
                        Map<String, BigDecimal> lastTradePrices) {
        this.book = book;
        this.tradeRingBuffer = tradeRingBuffer;
        this.lastTradePrices = lastTradePrices;
    }

    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) {
        Order order = event.getOrder();
        List<Trade> trades = order.getType() == Order.Type.MARKET
                ? matchMarket(order)
                : matchLimit(order);
        event.setResult(trades);

        // 发布到下游 Trade RingBuffer
        for (Trade t : trades) {
            long seq = tradeRingBuffer.next();
            tradeRingBuffer.get(seq).set(t);
            tradeRingBuffer.publish(seq);
        }
    }

    // ── 以下方法从 MatchingEngine 原样迁移，去掉锁相关代码 ──

    private List<Trade> matchMarket(Order taker) {
        List<Trade> trades = new ArrayList<>();
        var counterBook = taker.getSide() == Order.Side.BUY
                ? book.getAsks() : book.getBids();

        while (!taker.isDone() && !counterBook.isEmpty()) {
            var entry = counterBook.firstEntry();
            PriceLevel level = entry.getValue();
            trades.addAll(matchAtLevel(taker, level, entry.getKey()));
            if (level.isEmpty()) counterBook.pollFirstEntry();
        }

        if (!taker.isDone()) {
            log.warning(String.format("[MARKET] %s partially filled, remaining %s cancelled",
                    taker.getId().substring(0, 8), taker.remainingQty()));
            taker.cancel();
        }
        return trades;
    }

    private List<Trade> matchLimit(Order taker) {
        List<Trade> trades = new ArrayList<>();

        if (taker.getSide() == Order.Side.BUY) {
            while (!taker.isDone() && !book.getAsks().isEmpty()) {
                BigDecimal bestAsk = book.getAsks().firstKey();
                if (taker.getPrice().compareTo(bestAsk) < 0) break;
                PriceLevel level = book.getAsks().firstEntry().getValue();
                trades.addAll(matchAtLevel(taker, level, bestAsk));
                if (level.isEmpty()) book.getAsks().pollFirstEntry();
            }
        } else {
            while (!taker.isDone() && !book.getBids().isEmpty()) {
                BigDecimal bestBid = book.getBids().firstKey();
                if (taker.getPrice().compareTo(bestBid) > 0) break;
                PriceLevel level = book.getBids().firstEntry().getValue();
                trades.addAll(matchAtLevel(taker, level, bestBid));
                if (level.isEmpty()) book.getBids().pollFirstEntry();
            }
        }

        if (!taker.isDone()) {
            book.addLimitOrder(taker);
        }
        return trades;
    }

    private List<Trade> matchAtLevel(Order taker, PriceLevel level, BigDecimal matchPrice) {
        List<Trade> trades = new ArrayList<>();
        String symbol = book.getSymbol();

        while (!taker.isDone() && !level.isEmpty()) {
            Order maker = level.peek();
            if (maker.isDone()) { level.pollHead(); continue; }

            BigDecimal fillQty = taker.remainingQty().min(maker.remainingQty());
            taker.fill(fillQty, matchPrice);
            maker.fill(fillQty, matchPrice);
            level.reduceQty(fillQty);
            lastTradePrices.put(symbol, matchPrice);

            trades.add(new Trade(symbol,
                    taker.getId(), maker.getId(),
                    taker.getSide(), matchPrice, fillQty));

            if (maker.isDone()) level.pollHead();
        }
        return trades;
    }
}
```

### 3.5 TradeHandler.java

```java
package com.perp.disruptor;

import com.lmax.disruptor.EventHandler;
import com.perp.matching.Order;
import com.perp.matching.Trade;
import com.perp.model.OrderRequest;
import com.perp.model.Position;
import com.perp.service.PositionService;

import java.util.logging.Logger;

/**
 * 成交处理器 — 消费 TradeEvent，执行开仓/仓位管理
 *
 * 独立线程运行，不阻塞撮合。
 */
public class TradeHandler implements EventHandler<TradeEvent> {

    private static final Logger log = Logger.getLogger(TradeHandler.class.getName());

    private final PositionService positionService;
    private final int defaultLeverage;

    public TradeHandler(PositionService positionService, int defaultLeverage) {
        this.positionService = positionService;
        this.defaultLeverage = defaultLeverage;
    }

    @Override
    public void onEvent(TradeEvent event, long sequence, boolean endOfBatch) {
        Trade trade = event.getTrade();
        try {
            Position.Side side = trade.takerSide() == Order.Side.BUY
                    ? Position.Side.LONG : Position.Side.SHORT;
            var req = new OrderRequest(
                    trade.takerOrderId(), trade.symbol(),
                    side, defaultLeverage,
                    trade.qty(), trade.price());
            positionService.openPosition(req, trade.price());
        } catch (Exception e) {
            log.severe(String.format("[TradeHandler] Failed to open position: %s", e.getMessage()));
        }
    }
}
```

### 3.6 RiskHandler.java

```java
package com.perp.disruptor;

import com.lmax.disruptor.EventHandler;
import com.perp.engine.RiskEngine;
import com.perp.matching.Trade;

import java.util.logging.Logger;

/**
 * 风控处理器 — 消费 TradeEvent，基于最新成交价扫描强平
 *
 * 独立线程运行，与 TradeHandler 并行消费同一个 RingBuffer。
 */
public class RiskHandler implements EventHandler<TradeEvent> {

    private static final Logger log = Logger.getLogger(RiskHandler.class.getName());

    private final RiskEngine riskEngine;

    public RiskHandler(RiskEngine riskEngine) {
        this.riskEngine = riskEngine;
    }

    @Override
    public void onEvent(TradeEvent event, long sequence, boolean endOfBatch) {
        Trade trade = event.getTrade();
        try {
            var liquidated = riskEngine.onPriceUpdate(trade.symbol(), trade.price());
            if (!liquidated.isEmpty()) {
                log.warning(String.format("[RiskHandler] %d positions liquidated at price %s",
                        liquidated.size(), trade.price()));
            }
        } catch (Exception e) {
            log.severe(String.format("[RiskHandler] Error: %s", e.getMessage()));
        }
    }
}
```

### 3.7 DisruptorMatchingEngine.java

```java
package com.perp.disruptor;

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.perp.engine.RiskEngine;
import com.perp.matching.Order;
import com.perp.matching.OrderBook;
import com.perp.service.PositionService;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 基于 Disruptor 的撮合引擎
 *
 * 对外 API 与 MatchingEngine 保持一致，内部用 RingBuffer 替代锁。
 *
 * 使用方式：
 *   var engine = new DisruptorMatchingEngine();
 *   engine.registerSymbol("BTC-USDT", initialPrice, posService, riskEngine, 10);
 *   engine.submitOrder(order);   // 非阻塞
 *   engine.shutdown();
 */
public class DisruptorMatchingEngine {

    private static final Logger log = Logger.getLogger(DisruptorMatchingEngine.class.getName());

    private static final int ORDER_BUFFER_SIZE = 4096;
    private static final int TRADE_BUFFER_SIZE = 1024;

    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    private final Map<String, RingBuffer<OrderEvent>> orderRingBuffers = new ConcurrentHashMap<>();
    private final Map<String, Disruptor<OrderEvent>> orderDisruptors = new ConcurrentHashMap<>();
    private final Map<String, Disruptor<TradeEvent>> tradeDisruptors = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> lastTradePrices = new ConcurrentHashMap<>();

    /**
     * 注册合约并启动 Disruptor 管线
     *
     * 每个 symbol 独立的两级 RingBuffer：
     *   Order RingBuffer → MatchHandler（单线程撮合）
     *                          ↓
     *   Trade RingBuffer → TradeHandler + RiskHandler（并行消费）
     *
     * @param symbol         合约标的
     * @param initialPrice   初始价格
     * @param posService     仓位服务
     * @param riskEngine     风控引擎
     * @param defaultLeverage 默认杠杆（TradeHandler 使用）
     */
    public void registerSymbol(String symbol, BigDecimal initialPrice,
                               PositionService posService, RiskEngine riskEngine,
                               int defaultLeverage) {
        OrderBook book = new OrderBook(symbol);
        books.put(symbol, book);
        lastTradePrices.put(symbol, initialPrice);

        // ── 第一级：Trade Disruptor（下游，先启动）──────────────
        Disruptor<TradeEvent> tradeDisruptor = new Disruptor<>(
                TradeEvent.FACTORY,
                TRADE_BUFFER_SIZE,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,          // 只有 MatchHandler 一个生产者
                new BusySpinWaitStrategy());

        // TradeHandler 和 RiskHandler 并行消费同一条 Trade 流
        tradeDisruptor.handleEventsWith(
                new TradeHandler(posService, defaultLeverage),
                new RiskHandler(riskEngine));
        tradeDisruptor.start();
        tradeDisruptors.put(symbol, tradeDisruptor);

        // ── 第二级：Order Disruptor（上游）─────────────────────
        Disruptor<OrderEvent> orderDisruptor = new Disruptor<>(
                OrderEvent.FACTORY,
                ORDER_BUFFER_SIZE,
                DaemonThreadFactory.INSTANCE,
                ProducerType.MULTI,           // 多个用户线程提交订单
                new BusySpinWaitStrategy());

        orderDisruptor.handleEventsWith(
                new MatchHandler(book, tradeDisruptor.getRingBuffer(), lastTradePrices));
        orderDisruptor.start();
        orderDisruptors.put(symbol, orderDisruptor);
        orderRingBuffers.put(symbol, orderDisruptor.getRingBuffer());

        log.info(String.format("[DISRUPTOR] Registered %s orderBuffer=%d tradeBuffer=%d",
                symbol, ORDER_BUFFER_SIZE, TRADE_BUFFER_SIZE));
    }

    /**
     * 提交订单 — 写入 RingBuffer 即返回，非阻塞
     */
    public void submitOrder(Order order) {
        RingBuffer<OrderEvent> rb = orderRingBuffers.get(order.getSymbol());
        if (rb == null) throw new IllegalArgumentException("Unknown symbol: " + order.getSymbol());

        long sequence = rb.next();           // 获取下一个可写槽位（满时阻塞 = 天然背压）
        try {
            rb.get(sequence).set(order);     // 填充事件（无对象创建）
        } finally {
            rb.publish(sequence);            // 发布，MatchHandler 即刻可见
        }
    }

    /**
     * 撤单 — 仍走 OrderBook 直接操作（撤单不需要撮合）
     *
     * 注意：生产环境应将撤单也包装为事件投入 Order RingBuffer，
     * 保证与撮合在同一线程执行，避免并发问题。
     */
    public boolean cancelOrder(String symbol, String orderId) {
        OrderBook book = books.get(symbol);
        return book != null && book.cancel(orderId);
    }

    public OrderBook getBook(String symbol)         { return books.get(symbol); }
    public BigDecimal lastTradePrice(String symbol)  { return lastTradePrices.get(symbol); }

    /**
     * 优雅关闭所有 Disruptor
     */
    public void shutdown() {
        orderDisruptors.values().forEach(Disruptor::shutdown);
        tradeDisruptors.values().forEach(Disruptor::shutdown);
        log.info("[DISRUPTOR] All disruptors shut down");
    }
}
```

---

## 四、现有文件修改

### 4.1 MatchingEngine.java — 保留不动

原有 `MatchingEngine` **不需要修改**，作为简单模式保留。
`DisruptorMatchingEngine` 作为高性能替代方案并行存在，调用方自行选择。

### 4.2 Account.java — CAS 优化（可选）

增加自旋重试上限，防止极端高并发下无限自旋：

```java
// Account.java — freezeMargin()
public boolean freezeMargin(BigDecimal amount) {
    for (int retry = 0; retry < 100; retry++) {
        BigDecimal current = availableBalance.get();
        if (current.compareTo(amount) < 0) return false;
        BigDecimal next = current.subtract(amount);
        if (availableBalance.compareAndSet(current, next)) return true;
        Thread.onSpinWait();   // JDK 9+ hint，让 CPU 知道在自旋
    }
    throw new IllegalStateException("CAS contention exceeded retry limit");
}
```

### 4.3 pom.xml / build 脚本 — 添加依赖

```xml
<dependency>
    <groupId>com.lmax</groupId>
    <artifactId>disruptor</artifactId>
    <version>4.0.0</version>
</dependency>
```

若继续无 Maven 手动编译：

```bash
# 下载 jar
curl -L -o lib/disruptor-4.0.0.jar \
  https://repo1.maven.org/maven2/com/lmax/disruptor/4.0.0/disruptor-4.0.0.jar

# 编译
javac -cp lib/disruptor-4.0.0.jar -d out $(find src/main -name "*.java")

# 运行
java -cp out:lib/disruptor-4.0.0.jar com.perp.PerpEngineDemo
```

---

## 五、撤单的线程安全补充说明

当前 `cancelOrder()` 直接操作 `OrderBook`，但 `MatchHandler` 也在单线程中操作同一个 `OrderBook`。
两者并发访问会有竞态问题。

### 生产环境方案：撤单也走 RingBuffer

```java
// 定义撤单事件类型
public class CancelEvent {
    private String orderId;
    // ...
}

// 或者复用 OrderEvent，增加一个 type 字段
public class OrderEvent {
    enum Type { SUBMIT, CANCEL }
    private Type type;
    private Order order;
    private String cancelOrderId;
}
```

MatchHandler 根据 `type` 分流：

```java
@Override
public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) {
    if (event.getType() == OrderEvent.Type.CANCEL) {
        book.cancel(event.getCancelOrderId());
    } else {
        // 正常撮合逻辑
    }
}
```

这样撤单和撮合在同一线程执行，**零并发冲突**。

---

## 六、WaitStrategy 选型

| 策略 | 延迟 | CPU 占用 | 适用场景 |
|------|------|---------|---------|
| `BusySpinWaitStrategy` | **最低** | 100%（独占核心） | 撮合引擎（低延迟优先） |
| `YieldingWaitStrategy` | 低 | 高 | 下游处理器（TradeHandler） |
| `SleepingWaitStrategy` | 中 | 低 | 非关键路径（日志、监控） |
| `BlockingWaitStrategy` | 高 | 最低 | 测试/开发环境 |

建议：
- Order RingBuffer → `BusySpinWaitStrategy`（撮合延迟敏感）
- Trade RingBuffer → `YieldingWaitStrategy`（下游可容忍微秒级延迟）

---

## 七、性能调优参数

```java
// RingBuffer 大小（必须是 2 的幂）
ORDER_BUFFER_SIZE = 4096;   // 支持突发 4096 笔订单排队
TRADE_BUFFER_SIZE = 1024;   // 撮合输出缓冲

// 生产环境建议
ORDER_BUFFER_SIZE = 65536;  // 64K，应对高峰
TRADE_BUFFER_SIZE = 16384;  // 16K
```

**JVM 参数**：

```bash
java -server \
     -XX:+UseZGC \                    # 低延迟 GC（JDK 17+）
     -Xms4g -Xmx4g \                 # 固定堆大小，避免 GC 调整
     -XX:+AlwaysPreTouch \            # 启动时预分配内存页
     -Djava.lang.Integer.IntegerCache.high=65536 \
     -cp out:lib/disruptor-4.0.0.jar \
     com.perp.PerpEngineDemo
```

---

## 八、改造步骤（按顺序执行）

| 步骤 | 内容 | 风险 |
|------|------|------|
| 1 | 添加 disruptor 依赖 | 无 |
| 2 | 新建 `disruptor/` 目录，实现 OrderEvent + TradeEvent | 无 |
| 3 | 实现 MatchHandler（从 MatchingEngine 提取撮合逻辑） | 低：纯代码搬运 |
| 4 | 实现 TradeHandler + RiskHandler | 低：调用现有 Service |
| 5 | 实现 DisruptorMatchingEngine 编排类 | 中：需验证启动/关闭顺序 |
| 6 | 编写集成测试（复用现有测试用例） | 低 |
| 7 | PerpEngineDemo 新增 Disruptor 模式演示 | 无 |
| 8 | （可选）Account CAS 优化 | 低 |
| 9 | （可选）撤单走 RingBuffer | 中：需修改 OrderEvent 结构 |

---

## 九、验证清单

- [ ] 所有现有测试用例在 Disruptor 模式下通过
- [ ] 限价单完全撮合、部分撮合、无撮合挂单 三种场景正确
- [ ] 市价单深度不足时剩余量被取消
- [ ] 强平链路：价格变动 → RiskHandler 触发 → 仓位状态变为 LIQUIDATED
- [ ] 多 symbol 并行运行互不干扰
- [ ] 优雅关闭：`shutdown()` 后 RingBuffer 中残留事件被消费完
- [ ] 背压验证：Order RingBuffer 满时 `submitOrder()` 阻塞而非丢弃
