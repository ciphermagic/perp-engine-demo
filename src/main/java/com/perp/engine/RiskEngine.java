package com.perp.engine;

import com.perp.model.Position;
import com.perp.service.PositionService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 风控引擎（强平扫描器）
 *
 * 生产环境中此模块是独立微服务，以极低延迟订阅标记价格 feed，
 * 实时扫描所有仓位的保证金率，触发强平时走独立的强平撮合队列。
 *
 * 本 Demo 简化为：
 *  - 价格更新时同步扫描全部持仓
 *  - 触发强平时调用 PositionService.liquidate()
 *  - 生产场景需考虑：强平队列优先级、保险基金、ADL（自动减仓）
 */
public class RiskEngine {

    private static final Logger log = Logger.getLogger(RiskEngine.class.getName());

    private final MarginEngine marginEngine;
    private final PositionService positionService;

    public RiskEngine(MarginEngine marginEngine, PositionService positionService) {
        this.marginEngine = marginEngine;
        this.positionService = positionService;
    }

    /**
     * 标记价格更新时调用，扫描全部 OPEN 仓位
     *
     * @param symbol    合约标的，如 "BTC-USDT"
     * @param markPrice 最新标记价格
     * @return 本轮被强平的仓位列表
     */
    public List<Position> onPriceUpdate(String symbol, BigDecimal markPrice) {
        List<Position> triggered = new ArrayList<>();

        for (Position pos : positionService.getOpenPositions(symbol)) {
            if (marginEngine.shouldLiquidate(pos, markPrice)) {
                log.warning(String.format(
                        "[LIQUIDATION] posId=%s side=%s liqPrice=%s markPrice=%s marginRate=%s",
                        pos.getId(), pos.getSide(), pos.getLiqPrice(),
                        markPrice, pos.marginRate(markPrice)));
                positionService.liquidate(pos, markPrice);
                triggered.add(pos);
            }
        }
        return triggered;
    }
}
