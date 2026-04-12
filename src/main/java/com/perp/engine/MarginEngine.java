package com.perp.engine;

import com.perp.model.Position;
import com.perp.model.Position.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 保证金引擎
 *
 * 核心职责：
 *  1. 计算开仓所需保证金
 *  2. 计算强平价（Liquidation Price）
 *  3. 判断当前保证金率是否触及强平线
 *
 * ┌──────────────────────────────────────────────────────┐
 * │  逐仓模式公式（简化，不含阶梯保证金）                     │
 * │                                                      │
 * │  初始保证金  = 名义价值 / 杠杆                          │
 * │  名义价值    = size × price                           │
 * │                                                      │
 * │  多头强平价 = entryPrice × (1 - 1/lever + mmr)        │
 * │  空头强平价 = entryPrice × (1 + 1/lever - mmr)        │
 * │                                                      │
 * │  其中 mmr = 维持保证金率（Maintenance Margin Rate）     │
 * │  触发条件: 当前保证金率 ≤ mmr                           │
 * └──────────────────────────────────────────────────────┘
 */
public class MarginEngine {

    /** 维持保证金率，低于此值触发强平 */
    private final BigDecimal maintenanceMarginRate;

    /** 开仓手续费率 */
    private final BigDecimal takerFeeRate;

    private static final int SCALE = 8;

    public MarginEngine(BigDecimal maintenanceMarginRate, BigDecimal takerFeeRate) {
        this.maintenanceMarginRate = maintenanceMarginRate;
        this.takerFeeRate = takerFeeRate;
    }

    // ── 公开 API ─────────────────────────────────────────

    /**
     * 计算开仓所需初始保证金
     */
    public BigDecimal calcInitialMargin(BigDecimal price, BigDecimal size, int leverage) {
        BigDecimal notional = price.multiply(size);
        return notional.divide(BigDecimal.valueOf(leverage), SCALE, RoundingMode.UP);
    }

    /**
     * 计算开仓手续费
     */
    public BigDecimal calcOpenFee(BigDecimal price, BigDecimal size) {
        return price.multiply(size).multiply(takerFeeRate).setScale(SCALE, RoundingMode.UP);
    }

    /**
     * 计算强平价
     *
     * 推导（以多头为例）：
     *   当保证金余额 = 名义价值 × mmr 时触发强平
     *   margin + (liqPrice - entryPrice) × size = liqPrice × size × mmr
     *   → liqPrice × size × (1 - mmr) = margin - entryPrice × size × (-1)
     *   → liqPrice = entryPrice - (margin / size) + entryPrice × mmr
     *              = entryPrice × (1 - 1/lever + mmr)   [化简后]
     */
    public BigDecimal calcLiquidationPrice(Side side, BigDecimal entryPrice,
                                           int leverage, BigDecimal margin, BigDecimal size) {
        // margin/size = 每单位 BTC 对应的保证金
        BigDecimal marginPerUnit = margin.divide(size, SCALE, RoundingMode.DOWN);

        BigDecimal liqPrice;
        if (side == Side.LONG) {
            // liqPrice = entryPrice - marginPerUnit + entryPrice × mmr
            liqPrice = entryPrice
                    .subtract(marginPerUnit)
                    .add(entryPrice.multiply(maintenanceMarginRate));
        } else {
            // liqPrice = entryPrice + marginPerUnit - entryPrice × mmr
            liqPrice = entryPrice
                    .add(marginPerUnit)
                    .subtract(entryPrice.multiply(maintenanceMarginRate));
        }
        return liqPrice.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 判断是否应触发强平
     * 当 marginRate ≤ mmr 时返回 true
     */
    public boolean shouldLiquidate(Position pos, BigDecimal markPrice) {
        BigDecimal marginRate = pos.marginRate(markPrice);
        return marginRate.compareTo(maintenanceMarginRate) <= 0;
    }

    /**
     * 计算资金费用（单次结算）
     *
     * fundingFee = 名义价值 × fundingRate
     * 多头支付正费率，空头收取；负费率反之
     */
    public BigDecimal calcFundingFee(Position pos, BigDecimal markPrice, BigDecimal fundingRate) {
        BigDecimal notional = pos.getSize().multiply(markPrice);
        BigDecimal fee = notional.multiply(fundingRate).setScale(SCALE, RoundingMode.HALF_UP);
        // 多头：付费为正（扣款），收费为负（入账）
        return pos.getSide() == Side.LONG ? fee : fee.negate();
    }

    public BigDecimal getMaintenanceMarginRate() { return maintenanceMarginRate; }
}
