# perp-engine

一个用纯 Java 实现的**永续合约（Perpetual Swap）核心引擎** Demo，无任何第三方依赖，仅需 JDK 17+。

目标是还原中心化交易所（CEX）永续合约后端的核心业务逻辑，适合用于学习，作为更完整系统的起点。

> 博客文章: https://www.ciphermagic.cn/perp-engine-demo.html

---

## 模块概览

```
src/main/java/com/perp/
├── PerpEngineDemo.java          入口，6 个完整演示场景
│
├── matching/                    撮合引擎
│   ├── Order.java               委托单（LIMIT/MARKET，BUY/SELL）
│   ├── Trade.java               成交记录
│   ├── PriceLevel.java          单价格档位（ArrayDeque FIFO）
│   ├── OrderBook.java           双边订单簿（TreeMap）
│   └── MatchingEngine.java      撮合主逻辑，per-symbol 加锁
│
├── engine/                      业务引擎
│   ├── MarginEngine.java        保证金计算 & 强平价公式
│   ├── RiskEngine.java          强平扫描器
│   └── FundingEngine.java       资金费率计算 & 结算
│
├── service/
│   └── PositionService.java     开仓 / 平仓 / 强平业务流
│
├── model/
│   ├── Position.java            仓位实体（含 unrealizedPnl / marginRate）
│   ├── Account.java             账户（AtomicReference 余额）
│   └── OrderRequest.java        下单请求 DTO
│
└── exception/
    ├── InsufficientMarginException.java
    └── InvalidOrderException.java
```

---

## 快速开始

```bash
# 0. 安装 JDK 17（可选）
mise use java@temurin-17

# 1. 解压
unzip perp-engine-v2.zip && cd perp-engine

# 2. 编译
javac -d out $(find src/main -name "*.java")

# 3. 运行 Demo
java -cp out com.perp.PerpEngineDemo

# 4. 运行撮合引擎测试（9 个用例，无需 JUnit）
javac -cp out -d out/test src/test/java/com/perp/matching/MatchingEngineTest.java
java -cp out:out/test com.perp.matching.MatchingEngineTest
```

---

## 核心概念与实现

### 撮合引擎（Matching Engine）

订单簿使用双边 `TreeMap` 结构：

```
asks: TreeMap<BigDecimal, PriceLevel>  升序 → firstKey() = bestAsk  O(1)
bids: TreeMap<BigDecimal, PriceLevel>  降序 → firstKey() = bestBid  O(1)
```

每个 `PriceLevel` 内部是 `ArrayDeque<Order>`，保证同价格按提交时间 **FIFO** 撮合。

**撮合规则：价格优先，时间次之**

| 操作 | 复杂度 |
|------|--------|
| 挂限价单 | O(log N) |
| 撮合成交 | O(k log N)，k = 成交档位数 |
| 查最优价 | O(1) |
| 撤单 | O(log N) + O(M)，M = 同档位委托数 |

**限价单流程：**
1. 以 taker 身份尝试与对手盘撮合
2. 满足条件（`buyPrice ≥ bestAsk`）则逐档吃单
3. 剩余未成交部分挂入订单簿成为 maker

**市价单流程：**
1. 不限价，持续吃对手盘最优档位
2. 对手盘耗尽后剩余量自动取消（不挂单）

### 保证金系统（MarginEngine）

逐仓模式（Isolated Margin）核心公式：

```
初始保证金  = size × price / leverage
手续费      = size × price × takerFeeRate

// 多头强平价
liqPrice = entryPrice × (1 - 1/leverage + mmr)

// 空头强平价
liqPrice = entryPrice × (1 + 1/leverage - mmr)

// 当前保证金率
marginRate = (margin + unrealizedPnl) / (size × markPrice)

// 触发强平条件
marginRate ≤ maintenanceMarginRate（默认 0.5%）
```

### 资金费率（FundingEngine）

永续合约用资金费率替代交割机制，每 8 小时结算一次，将合约价格锚定至现货指数：

```
premium      = (midPrice - indexPrice) / indexPrice
fundingRate  = clamp(premium + interestRate, -0.75%, +0.75%)

// 多头付款（fundingRate > 0 时）
fundingFee   = size × markPrice × fundingRate
```

正费率时多头付给空头；负费率时空头付给多头。

### 风控引擎（RiskEngine）

每次标记价格更新时扫描全部持仓，保证金率跌破维持保证金率则触发强平：

```java
// 生产环境：独立微服务订阅行情 feed，走单独强平撮合队列
// Demo：同步扫描，调用 PositionService.liquidate()
riskEngine.onPriceUpdate("BTC-USDT", markPrice);
```

---

## Demo 场景

| Part | 场景 | 演示要点 |
|------|------|---------|
| 1 | 做市商建立深度 | 多档位挂单，双边订单簿初始化 |
| 2 | 限价单撮合 | taker 吃 maker，价格优先 |
| 3 | 市价单穿越档位 | 跨 3 档成交，加权均价计算 |
| 4 | 部分成交后撤单 | PARTIAL → CANCELLED 状态流转 |
| 5 | 强平触发 | 50x 多仓，价格跌破强平价 |
| 6 | 最终盘口状态 | 成交后 bestAsk/bestBid/midPrice |

---

## 与生产环境的差距

本 Demo 是为了清晰展示核心逻辑而刻意简化的，生产系统还需要：

| 功能 | 生产实现 | Demo 简化 |
|------|---------|-----------|
| 并发安全 | 数据库行锁 + 乐观锁版本号 | AtomicReference / ReentrantLock |
| 持久化 | MySQL/TiDB + Redis | ConcurrentHashMap 内存 |
| 消息推送 | Kafka / Disruptor | TradeListener 回调 |
| 撮合性能 | 单线程 per-symbol + Disruptor | ReentrantLock |
| 阶梯保证金 | Notional Tier 分级 mmr | 固定 0.5% |
| 保险基金 | 亏损超保证金时动用 | 忽略 |
| ADL 自动减仓 | 按盈利率排序减对手仓 | 未实现 |
| 标记价格 | 现货指数 + 基差加权 | 直接用成交价 |

---

## 技术栈

- **语言**：Java 17（使用 record、var、sealed 等现代特性）
- **依赖**：零，纯 JDK
- **测试**：自实现断言框架（无需 JUnit），9 个测试用例全部通过
