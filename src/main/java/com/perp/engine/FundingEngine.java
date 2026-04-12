package com.perp.engine;

import com.perp.model.Position;
import com.perp.service.PositionService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.logging.Logger;

/**
 * 资金费率引擎
 *
 * 永续合约通过资金费率锚定合约价格与现货指数价格的差距。
 * 每8小时结算一次（00:00 / 08:00 / 16:00 UTC）。
 *
 * 费率公式（简化版）：
 *   fundingRate = clamp(premium + interestRate, -cap, +cap)
 *
 *   premium      = (midPrice - indexPrice) / indexPrice
 *   interestRate = 0.01% / 3  （固定利率，日利率 0.03%）
 *   cap          = 0.75%（交易所设定上限，防止极端行情）
 *
 * 结算逻辑：
 *   多头（LONG）：fundingFee = size × markPrice × fundingRate
 *     fundingRate > 0：多头付给空头（多头亏损）
 *     fundingRate < 0：空头付给多头（多头盈利）
 */
public class FundingEngine {

    private static final Logger log = Logger.getLogger(FundingEngine.class.getName());

    /** 固定利率（每8小时） */
    private static final BigDecimal INTEREST_RATE = new BigDecimal("0.0001");

    /** 资金费率上限 */
    private static final BigDecimal FUNDING_CAP = new BigDecimal("0.0075");

    private final MarginEngine marginEngine;
    private final PositionService positionService;

    public FundingEngine(MarginEngine marginEngine, PositionService positionService) {
        this.marginEngine = marginEngine;
        this.positionService = positionService;
    }

    /**
     * 计算当期资金费率
     *
     * @param midPrice   合约中间价（(bestBid + bestAsk) / 2）
     * @param indexPrice 现货指数价格
     * @return 本期资金费率（正数 = 多头付空头）
     */
    public BigDecimal calcFundingRate(BigDecimal midPrice, BigDecimal indexPrice) {
        // premium = (midPrice - indexPrice) / indexPrice
        BigDecimal premium = midPrice.subtract(indexPrice)
                .divide(indexPrice, 8, RoundingMode.HALF_UP);

        BigDecimal rawRate = premium.add(INTEREST_RATE);

        // clamp 至 [-cap, +cap]
        rawRate = rawRate.min(FUNDING_CAP).max(FUNDING_CAP.negate());

        return rawRate.setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 执行资金费结算
     * 遍历所有持仓，按费率扣/入资金费
     *
     * @param symbol      合约标的
     * @param markPrice   结算时标记价格
     * @param fundingRate 本期资金费率
     */
    public void settle(String symbol, BigDecimal markPrice, BigDecimal fundingRate) {
        List<Position> positions = positionService.getOpenPositions(symbol);

        log.info(String.format("[FUNDING SETTLE] symbol=%s rate=%s positions=%d",
                symbol, fundingRate, positions.size()));

        for (Position pos : positions) {
            BigDecimal fee = marginEngine.calcFundingFee(pos, markPrice, fundingRate);
            pos.addFundingCharge(fee);

            log.info(String.format("  posId=%s side=%s fee=%s (+ = deducted from long)",
                    pos.getId(), pos.getSide(), fee));
        }
    }
}
