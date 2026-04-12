package com.perp.service;

import com.perp.engine.MarginEngine;
import com.perp.exception.InsufficientMarginException;
import com.perp.exception.InvalidOrderException;
import com.perp.model.Account;
import com.perp.model.OrderRequest;
import com.perp.model.Position;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 仓位服务
 *
 * 核心业务流程：
 *
 *  开仓：
 *    1. 参数校验
 *    2. 计算保证金 & 强平价
 *    3. 冻结账户余额
 *    4. 创建 Position 写入存储
 *
 *  平仓：
 *    1. 查仓位
 *    2. 计算实现盈亏（Realized PnL）
 *    3. 释放保证金 + 结算 PnL 回账户
 *    4. 更新仓位状态为 CLOSED
 *
 *  强平（由 RiskEngine 触发）：
 *    与平仓逻辑相同，但标记为 LIQUIDATED
 *    超出保证金的亏损由保险基金承担（此处略）
 */
public class PositionService {

    private static final Logger log = Logger.getLogger(PositionService.class.getName());

    // 模拟数据库（key = positionId）
    private final Map<String, Position> store = new ConcurrentHashMap<>();
    // 账户存储（key = accountId）
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    private final MarginEngine marginEngine;

    // 合约配置
    private static final int MIN_LEVERAGE = 1;
    private static final int MAX_LEVERAGE = 100;
    private static final BigDecimal MIN_SIZE = new BigDecimal("0.001");

    public PositionService(MarginEngine marginEngine) {
        this.marginEngine = marginEngine;
    }

    // ── 账户管理 ─────────────────────────────────────────

    public Account createAccount(String accountId, BigDecimal initialBalance) {
        Account acc = new Account(accountId, initialBalance);
        accounts.put(accountId, acc);
        return acc;
    }

    public Account getAccount(String accountId) {
        Account acc = accounts.get(accountId);
        if (acc == null) throw new IllegalArgumentException("Account not found: " + accountId);
        return acc;
    }

    // ── 开仓 ─────────────────────────────────────────────

    /**
     * 开仓
     *
     * @param req       下单请求
     * @param markPrice 当前标记价格（市价单使用；限价单用 req.price()）
     * @return 新建的仓位
     */
    public Position openPosition(OrderRequest req, BigDecimal markPrice) {
        // 1. 参数校验
        validate(req);

        // 2. 确定成交价
        BigDecimal execPrice = req.price() != null ? req.price() : markPrice;

        // 3. 计算保证金和手续费
        BigDecimal margin = marginEngine.calcInitialMargin(execPrice, req.size(), req.leverage());
        BigDecimal fee    = marginEngine.calcOpenFee(execPrice, req.size());
        BigDecimal totalCost = margin.add(fee);

        // 4. 冻结保证金
        Account account = getAccount(req.accountId());
        boolean frozen = account.freezeMargin(totalCost);
        if (!frozen) {
            throw new InsufficientMarginException(String.format(
                    "需要 %s USDT，可用 %s USDT", totalCost, account.getAvailableBalance()));
        }

        // 5. 计算强平价
        BigDecimal liqPrice = marginEngine.calcLiquidationPrice(
                req.side(), execPrice, req.leverage(), margin, req.size());

        // 6. 创建仓位
        Position pos = new Position(
                req.accountId(), req.symbol(), req.side(),
                req.leverage(), req.size(), execPrice, margin, liqPrice);
        store.put(pos.getId(), pos);

        log.info(String.format("[OPEN] %s margin=%s fee=%s liqPrice=%s",
                pos, margin, fee, liqPrice));
        return pos;
    }

    // ── 平仓 ─────────────────────────────────────────────

    /**
     * 主动平仓
     *
     * @param positionId 仓位 ID
     * @param markPrice  平仓时的标记价格
     * @return 已实现盈亏
     */
    public BigDecimal closePosition(String positionId, BigDecimal markPrice) {
        Position pos = getOpenPosition(positionId);
        BigDecimal pnl = settle(pos, markPrice, false);
        log.info(String.format("[CLOSE] posId=%s pnl=%s markPrice=%s", positionId, pnl, markPrice));
        return pnl;
    }

    /**
     * 强平（由 RiskEngine 调用）
     */
    public BigDecimal liquidate(Position pos, BigDecimal markPrice) {
        BigDecimal pnl = settle(pos, markPrice, true);
        log.warning(String.format("[LIQUIDATED] posId=%s pnl=%s", pos.getId(), pnl));
        return pnl;
    }

    // ── 查询 ─────────────────────────────────────────────

    public List<Position> getOpenPositions(String symbol) {
        return store.values().stream()
                .filter(p -> p.getStatus() == Position.Status.OPEN
                          && p.getSymbol().equals(symbol))
                .collect(Collectors.toList());
    }

    public Position getOpenPosition(String positionId) {
        Position pos = store.get(positionId);
        if (pos == null || pos.getStatus() != Position.Status.OPEN) {
            throw new IllegalArgumentException("Open position not found: " + positionId);
        }
        return pos;
    }

    // ── 内部 ─────────────────────────────────────────────

    private BigDecimal settle(Position pos, BigDecimal markPrice, boolean isLiquidation) {
        BigDecimal pnl = pos.unrealizedPnl(markPrice);
        Account account = getAccount(pos.getAccountId());
        account.releaseMargin(pos.getMargin(), pnl);

        if (isLiquidation) pos.liquidate();
        else pos.close();

        return pnl;
    }

    private void validate(OrderRequest req) {
        if (req.leverage() < MIN_LEVERAGE || req.leverage() > MAX_LEVERAGE) {
            throw new InvalidOrderException("杠杆倍数超出范围: " + req.leverage());
        }
        if (req.size().compareTo(MIN_SIZE) < 0) {
            throw new InvalidOrderException("下单数量低于最小值: " + req.size());
        }
        if (req.price() != null && req.price().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidOrderException("价格必须大于0");
        }
    }
}
