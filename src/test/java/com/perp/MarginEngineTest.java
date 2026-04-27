package com.perp;

import com.perp.engine.MarginEngine;
import com.perp.model.Position;

import java.math.BigDecimal;

public class MarginEngineTest {

    private int pass = 0;
    private int fail = 0;

    private final MarginEngine engine = new MarginEngine(
            new BigDecimal("0.005"),
            new BigDecimal("0.0002"));

    public static void main(String[] args) {
        new MarginEngineTest().runAll();
    }

    void runAll() {
        System.out.println("\n=== Margin Engine Test Suite ===\n");
        run("Initial margin", this::testInitialMargin);
        run("Liquidation price long", this::testLiqPriceLong);
        run("Liquidation price short", this::testLiqPriceShort);
        run("Should liquidate long", this::testShouldLiquidateLong);
        run("Funding fee", this::testFundingFee);
        System.out.printf("%n  Result: %d passed, %d failed%n", pass, fail);
        if (fail > 0)
            System.exit(1);
    }

    void run(String name, Runnable test) {
        try {
            test.run();
            System.out.printf("  [PASS] %s%n", name);
            pass++;
        } catch (AssertionError e) {
            System.out.printf("  [FAIL] %s -> %s%n", name, e.getMessage());
            fail++;
        }
    }

    void assertEqualsInt(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }

    void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("expected true but was false");
        }
    }

    void assertFalse(boolean condition) {
        if (condition) {
            throw new AssertionError("expected false but was true");
        }
    }

    public void testInitialMargin() {
        // 0.1 BTC @ 94000, 10x → margin = 94000 × 0.1 / 10 = 940
        BigDecimal margin = engine.calcInitialMargin(
                new BigDecimal("94000"),
                new BigDecimal("0.1"),
                10);
        assertEqualsInt(0, new BigDecimal("940").compareTo(margin));
    }

    public void testLiqPriceLong() {
        // entryPrice=94000, 10x, mmr=0.5%
        // liqPrice = 94000 × (1 - 0.1 + 0.005) = 94000 × 0.905 = 85070
        BigDecimal liq = engine.calcLiquidationPrice(
                Position.Side.LONG,
                new BigDecimal("94000"), 10,
                new BigDecimal("940"),
                new BigDecimal("0.1"));
        // 允许 1 USDT 误差（除法精度）
        assertTrue(liq.subtract(new BigDecimal("85070")).abs().compareTo(BigDecimal.ONE) <= 0);
    }

    public void testLiqPriceShort() {
        // entryPrice=94000, 10x
        // liqPrice = 94000 × (1 + 0.1 - 0.005) = 94000 × 1.095 = 102930
        BigDecimal liq = engine.calcLiquidationPrice(
                Position.Side.SHORT,
                new BigDecimal("94000"), 10,
                new BigDecimal("940"),
                new BigDecimal("0.1"));
        assertTrue(liq.subtract(new BigDecimal("102930")).abs().compareTo(BigDecimal.ONE) <= 0);
    }

    public void testShouldLiquidateLong() {
        // 构造一个多头，价格跌过强平价时应触发
        Position pos = new Position("test", "BTC-USDT", Position.Side.LONG,
                10, new BigDecimal("0.1"), new BigDecimal("94000"),
                new BigDecimal("940"), new BigDecimal("85070"));

        assertFalse(engine.shouldLiquidate(pos, new BigDecimal("90000"))); // 高于强平价，不触发
        assertTrue(engine.shouldLiquidate(pos, new BigDecimal("84000"))); // 低于强平价，触发
    }

    public void testFundingFee() {
        Position pos = new Position("test", "BTC-USDT", Position.Side.LONG,
                10, new BigDecimal("0.1"), new BigDecimal("94000"),
                new BigDecimal("940"), new BigDecimal("85070"));

        // fee = 0.1 × 94000 × 0.0001 = 0.94 USDT（多头付）
        BigDecimal fee = engine.calcFundingFee(pos,
                new BigDecimal("94000"),
                new BigDecimal("0.0001"));
        assertTrue(fee.compareTo(BigDecimal.ZERO) > 0); // 正值 = 多头扣款
        assertEqualsInt(0, new BigDecimal("0.94000000").compareTo(fee));
    }
}
