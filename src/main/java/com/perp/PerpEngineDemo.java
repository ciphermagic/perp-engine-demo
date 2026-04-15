package com.perp;

import com.perp.engine.MarginEngine;
import com.perp.engine.RiskEngine;
import com.perp.matching.MatchingEngine;
import com.perp.matching.Order;
import com.perp.matching.Trade;
import com.perp.model.Position;
import com.perp.service.PositionService;
import java.math.BigDecimal;
import java.util.List;

public class PerpEngineDemo {

    public static void main(String[] args) {

        MarginEngine marginEngine = new MarginEngine(
                new BigDecimal("0.005"),
                new BigDecimal("0.0002"));
        PositionService posService = new PositionService(marginEngine);
        RiskEngine riskEngine = new RiskEngine(marginEngine, posService);
        MatchingEngine matchEngine = new MatchingEngine();

        matchEngine.registerSymbol("BTC-USDT", new BigDecimal("94000"));

        matchEngine.setTradeListener(trade -> {
            try {
                com.perp.model.OrderRequest req = new com.perp.model.OrderRequest(
                        trade.takerOrderId(),
                        trade.symbol(),
                        trade.takerSide() == Order.Side.BUY
                                ? Position.Side.LONG
                                : Position.Side.SHORT,
                        10,
                        trade.qty(),
                        trade.price());
                posService.openPosition(req, trade.price());
            } catch (Exception e) {
                // account not registered in demo - ignore
            }
        });

        // ── Part 1: Build order book depth ──────────────
        banner("Part 1: Build Order Book Depth");

        System.out.println("\n  Market makers posting initial depth...\n");
        String[][] askOrders = {
                { "mm1", "94050", "2.0" },
                { "mm1", "94100", "3.5" },
                { "mm1", "94200", "5.0" },
                { "mm1", "94500", "10.0" },
        };
        String[][] bidOrders = {
                { "mm1", "93950", "2.0" },
                { "mm1", "93900", "3.5" },
                { "mm1", "93800", "5.0" },
                { "mm1", "93500", "10.0" },
        };
        for (String[] o : askOrders)
            matchEngine.submitOrder(limitSell(o[0], o[1], o[2]));
        for (String[] o : bidOrders)
            matchEngine.submitOrder(limitBuy(o[0], o[1], o[2]));

        printDepth(matchEngine, "BTC-USDT", 4);

        // ── Part 2: Limit order match ───────────────────
        banner("Part 2: Limit Order Match (price priority + FIFO)");

        System.out.println("\n  Alice posts limit BUY @ 94050 (hits best ask)");
        Order aliceBuy = limitBuy("alice", "94050", "1.5");
        List<Trade> trades1 = matchEngine.submitOrder(aliceBuy);

        System.out.println("  Trades:");
        for (Trade t : trades1)
            printTrade(t);
        System.out.printf("  Alice order: status=%s filled=%s avg=%s%n",
                aliceBuy.getStatus(), aliceBuy.getFilledQty(), aliceBuy.getAvgFillPrice());

        // ── Part 3: Market order crossing multiple levels
        banner("Part 3: Market Order - Sweep Multiple Levels");

        System.out.println("\n  Bob market BUY 8.0 BTC (sweeps multiple ask levels)");
        Order bobMkt = marketBuy("bob", "8.0");
        List<Trade> trades2 = matchEngine.submitOrder(bobMkt);

        System.out.println("  Trades:");
        for (Trade t : trades2)
            printTrade(t);
        System.out.printf("  Bob order: status=%s filled=%s avg_price=%s%n",
                bobMkt.getStatus(), bobMkt.getFilledQty(), bobMkt.getAvgFillPrice());

        System.out.println("\n  Order book after market sweep:");
        printDepth(matchEngine, "BTC-USDT", 4);

        // ── Part 4: Partial fill then cancel ────────────
        banner("Part 4: Partial Fill -> Cancel");

        System.out.println("\n  Alice posts large SELL 20 BTC @ 94100");
        Order bigSell = limitSell("alice", "94100", "20.0");
        matchEngine.submitOrder(bigSell);
        System.out.printf("  Resting: status=%s remaining=%s BTC%n",
                bigSell.getStatus(), bigSell.remainingQty());

        System.out.println("\n  Bob market BUY 3 BTC -> partially fills Alice's order");
        List<Trade> trades3 = matchEngine.submitOrder(marketBuy("bob", "3.0"));
        for (Trade t : trades3)
            printTrade(t);
        System.out.printf("  Alice big sell: status=%s filled=%s remaining=%s%n",
                bigSell.getStatus(), bigSell.getFilledQty(), bigSell.remainingQty());

        System.out.println("\n  Alice cancels remaining...");
        boolean cancelled = matchEngine.cancelOrder("BTC-USDT", bigSell.getId());
        System.out.printf("  Cancel result: %s  status: %s%n", cancelled, bigSell.getStatus());

        // ── Part 5: Risk engine liquidation ─────────────
        banner("Part 5: Risk Engine - Liquidation Scan");

        posService.createAccount("trader1", new BigDecimal("10000"));
        var req = new com.perp.model.OrderRequest(
                "trader1", "BTC-USDT", Position.Side.LONG,
                50, new BigDecimal("0.1"), null);
        Position highLevPos = posService.openPosition(req, new BigDecimal("94000"));
        System.out.printf("%n  Trader1 opens 50x LONG, liqPrice = %s%n", highLevPos.getLiqPrice());

        BigDecimal dropPrice = new BigDecimal("92100");
        System.out.printf("  Price drops to %s (below liqPrice), scanning...%n", dropPrice);
        List<Position> liquidated = riskEngine.onPriceUpdate("BTC-USDT", dropPrice);
        System.out.printf("  Liquidations triggered: %d  position status: %s%n",
                liquidated.size(), highLevPos.getStatus());

        // ── Part 6: Final state ──────────────────────────
        banner("Part 6: Final Order Book State");
        printDepth(matchEngine, "BTC-USDT", 5);
        System.out.printf("%n  Last trade price : %s USDT%n", matchEngine.lastTradePrice("BTC-USDT"));
        System.out.printf("  Mid price        : %s USDT%n",
                matchEngine.getBook("BTC-USDT").midPrice()
                        .map(BigDecimal::toPlainString).orElse("N/A"));
    }

    static Order limitBuy(String acc, String price, String qty) {
        return new Order(acc, "BTC-USDT", Order.Type.LIMIT, Order.Side.BUY,
                new BigDecimal(price), new BigDecimal(qty));
    }

    static Order limitSell(String acc, String price, String qty) {
        return new Order(acc, "BTC-USDT", Order.Type.LIMIT, Order.Side.SELL,
                new BigDecimal(price), new BigDecimal(qty));
    }

    static Order marketBuy(String acc, String qty) {
        return new Order(acc, "BTC-USDT", Order.Type.MARKET, Order.Side.BUY,
                null, new BigDecimal(qty));
    }

    static void printTrade(Trade t) {
        System.out.printf("    [TRADE] %s side=%-4s price=%-10s qty=%s notional=%.2f%n",
                t.symbol(), t.takerSide(), t.price(), t.qty(), t.notional());
    }

    static void printDepth(MatchingEngine engine, String symbol, int depth) {
        var book = engine.getBook(symbol);
        var snap = book.snapshot(depth);
        System.out.printf("%n  +------ %s Order Book ------+%n", symbol);
        System.out.printf("  | %-10s  %-10s  ASK |%n", "Qty(BTC)", "Price");
        System.out.println("  +---------------------------------+");
        var asks = snap[0];
        for (int i = asks.size() - 1; i >= 0; i--) {
            var lvl = (com.perp.matching.PriceLevel) asks.get(i);
            System.out.printf("  | %-10s  %-10s      |%n",
                    lvl.getTotalQty().toPlainString(), lvl.getPrice().toPlainString());
        }
        book.midPrice().ifPresent(mid -> System.out.printf("  +-- mid: %-8s ---------------+%n", mid));
        System.out.printf("  | %-10s  %-10s  BID |%n", "Qty(BTC)", "Price");
        for (var lvl : snap[1]) {
            System.out.printf("  | %-10s  %-10s      |%n",
                    lvl.getTotalQty().toPlainString(), lvl.getPrice().toPlainString());
        }
        System.out.println("  +---------------------------------+");
    }

    static void banner(String s) {
        System.out.println("\n" + "=".repeat(55));
        System.out.println("  " + s);
        System.out.println("=".repeat(55));
    }
}
