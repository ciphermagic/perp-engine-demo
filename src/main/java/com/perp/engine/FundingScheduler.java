package com.perp.engine;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 资金费率定时结算调度器
 *
 * 每 8 小时（00:00 / 08:00 / 16:00 UTC）自动触发一次资金费结算。
 * 启动时计算到下一个结算时间点的延迟，之后固定 8 小时周期执行。
 */
public class FundingScheduler {

    private static final Logger log = Logger.getLogger(FundingScheduler.class.getName());
    private static final long PERIOD_HOURS = 8;

    private final FundingEngine fundingEngine;
    private final String symbol;
    private final Supplier<BigDecimal> midPriceSupplier;
    private final Supplier<BigDecimal> indexPriceSupplier;
    private final Supplier<BigDecimal> markPriceSupplier;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;

    /**
     * @param fundingEngine      资金费率引擎
     * @param symbol             合约标的，如 "BTC-USDT"
     * @param midPriceSupplier   中间价来源（(bestBid + bestAsk) / 2）
     * @param indexPriceSupplier 现货指数价格来源
     * @param markPriceSupplier  标记价格来源
     */
    public FundingScheduler(FundingEngine fundingEngine,
                            String symbol,
                            Supplier<BigDecimal> midPriceSupplier,
                            Supplier<BigDecimal> indexPriceSupplier,
                            Supplier<BigDecimal> markPriceSupplier) {
        this.fundingEngine = fundingEngine;
        this.symbol = symbol;
        this.midPriceSupplier = midPriceSupplier;
        this.indexPriceSupplier = indexPriceSupplier;
        this.markPriceSupplier = markPriceSupplier;
    }

    /** 启动调度，对齐到下一个 UTC 8 小时整点 */
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "funding-scheduler");
            t.setDaemon(true);
            return t;
        });

        long delayMs = calcDelayToNextSlot();
        log.info(String.format("[FUNDING SCHEDULER] started, first settle in %d min",
                TimeUnit.MILLISECONDS.toMinutes(delayMs)));

        task = scheduler.scheduleAtFixedRate(
                this::executeSettle,
                delayMs,
                TimeUnit.HOURS.toMillis(PERIOD_HOURS),
                TimeUnit.MILLISECONDS);
    }

    /** 手动触发一次结算（测试用） */
    public void settleNow() {
        executeSettle();
    }

    /** 优雅关闭 */
    public void shutdown() {
        if (task != null) {
            task.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("[FUNDING SCHEDULER] shutdown");
    }

    private void executeSettle() {
        try {
            BigDecimal mid = midPriceSupplier.get();
            BigDecimal index = indexPriceSupplier.get();
            BigDecimal mark = markPriceSupplier.get();

            BigDecimal rate = fundingEngine.calcFundingRate(mid, index);
            fundingEngine.settle(symbol, mark, rate);

            log.info(String.format("[FUNDING SCHEDULER] settled symbol=%s rate=%s mark=%s",
                    symbol, rate, mark));
        } catch (Exception e) {
            log.severe("[FUNDING SCHEDULER] settle failed: " + e.getMessage());
        }
    }

    /**
     * 计算当前时刻到下一个结算点（00:00/08:00/16:00 UTC）的毫秒数
     */
    static long calcDelayToNextSlot() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        int hour = now.getHour();
        int nextSlotHour = ((hour / 8) + 1) * 8;  // 0→8, 7→8, 8→16, 15→16, 16→24(=0)

        ZonedDateTime next = now.truncatedTo(ChronoUnit.DAYS).plusHours(nextSlotHour);
        return Duration.between(now, next).toMillis();
    }
}
