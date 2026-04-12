package com.perp.matching;
import java.math.BigDecimal;
import java.util.*;
public class MatchingEngineTest {
    private MatchingEngine engine;
    private List<Trade> captured;
    private int pass=0,fail=0;
    public static void main(String[] a){new MatchingEngineTest().runAll();}
    void setUp(){engine=new MatchingEngine();engine.registerSymbol("BTC-USDT",new BigDecimal("94000"));captured=new ArrayList<>();engine.setTradeListener(captured::add);}
    void runAll(){System.out.println("\n=== Matching Engine Test Suite ===\n");run("Full match limit vs limit",this::t1);run("Partial match taker larger",this::t2);run("Multi-level sweep",this::t3);run("Market order full fill",this::t4);run("Market order insufficient liquidity",this::t5);run("Cancel order",this::t6);run("Price priority + FIFO",this::t7);run("No match price gap",this::t8);run("Depth snapshot",this::t9);System.out.printf("%n  Result: %d passed, %d failed%n",pass,fail);if(fail>0)System.exit(1);}
    void run(String n,Runnable r){setUp();try{r.run();System.out.printf("  [PASS] %s%n",n);pass++;}catch(AssertionError e){System.out.printf("  [FAIL] %s -> %s%n",n,e.getMessage());fail++;}}
    void eq(Object e,Object a,String m){if(!e.equals(a))throw new AssertionError(m+": expected="+e+" actual="+a);}
    void yes(boolean c,String m){if(!c)throw new AssertionError(m);}
    void nil(Object o,String m){if(o!=null)throw new AssertionError(m+" expected null but was "+o);}
    Order lb(String p,String q){return new Order("t","BTC-USDT",Order.Type.LIMIT,Order.Side.BUY,new BigDecimal(p),new BigDecimal(q));}
    Order ls(String p,String q){return new Order("t","BTC-USDT",Order.Type.LIMIT,Order.Side.SELL,new BigDecimal(p),new BigDecimal(q));}
    Order mb(String q){return new Order("t","BTC-USDT",Order.Type.MARKET,Order.Side.BUY,null,new BigDecimal(q));}
    void t1(){Order s=ls("94000","1");engine.submitOrder(s);eq(0,engine.submitOrder(s).size(),"no self-trade");Order b=lb("94000","1");List<Trade>t=engine.submitOrder(b);eq(1,t.size(),"1 trade");eq(new BigDecimal("94000"),t.get(0).price(),"price");eq(Order.Status.FILLED,b.getStatus(),"buy filled");eq(Order.Status.FILLED,s.getStatus(),"sell filled");}
    void t2(){engine.submitOrder(ls("94000","0.5"));Order b=lb("94000","1");engine.submitOrder(b);eq(Order.Status.PARTIAL,b.getStatus(),"partial");eq(new BigDecimal("0.5"),b.getFilledQty(),"filled");eq(new BigDecimal("0.5"),b.remainingQty(),"remaining");}
    void t3(){engine.submitOrder(ls("94000","0.3"));engine.submitOrder(ls("94100","0.3"));engine.submitOrder(ls("94200","0.3"));Order b=lb("95000","0.8");List<Trade>t=engine.submitOrder(b);eq(3,t.size(),"3 trades");eq(new BigDecimal("94000"),t.get(0).price(),"lvl1");eq(new BigDecimal("94100"),t.get(1).price(),"lvl2");eq(new BigDecimal("94200"),t.get(2).price(),"lvl3");eq(new BigDecimal("94087.50000000"),b.getAvgFillPrice(),"avg");}
    void t4(){engine.submitOrder(ls("94000","1"));engine.submitOrder(ls("94100","1"));Order m=mb("1.5");engine.submitOrder(m);eq(Order.Status.FILLED,m.getStatus(),"filled");eq(new BigDecimal("94100"),engine.lastTradePrice("BTC-USDT"),"last price");}
    void t5(){engine.submitOrder(ls("94000","0.5"));Order m=mb("1");engine.submitOrder(m);eq(Order.Status.CANCELLED,m.getStatus(),"cancelled");eq(new BigDecimal("0.5"),m.getFilledQty(),"filled 0.5");}
    void t6(){Order s=ls("94000","1");engine.submitOrder(s);yes(engine.cancelOrder("BTC-USDT",s.getId()),"cancel ok");eq(Order.Status.CANCELLED,s.getStatus(),"cancelled");nil(engine.getBook("BTC-USDT").bestAsk(),"book empty");}
    void t7(){Order s1=ls("94000","0.5"),s2=ls("94000","0.5"),s3=ls("93900","0.3");engine.submitOrder(s1);engine.submitOrder(s2);engine.submitOrder(s3);List<Trade>t1=engine.submitOrder(lb("94000","0.3"));eq(s3.getId(),t1.get(0).makerOrderId(),"price priority: carol 93900");List<Trade>t2=engine.submitOrder(lb("94000","0.6"));eq(s1.getId(),t2.get(0).makerOrderId(),"FIFO: alice");eq(s2.getId(),t2.get(1).makerOrderId(),"FIFO: bob");}
    void t8(){engine.submitOrder(ls("95000","1"));engine.submitOrder(lb("94000","1"));eq(0,captured.size(),"no trades");eq(new BigDecimal("95000"),engine.getBook("BTC-USDT").bestAsk(),"bestAsk");eq(new BigDecimal("94000"),engine.getBook("BTC-USDT").bestBid(),"bestBid");}
    void t9(){engine.submitOrder(ls("94200","1"));engine.submitOrder(ls("94100","2"));engine.submitOrder(ls("94000","3"));engine.submitOrder(lb("93900","1.5"));engine.submitOrder(lb("93800","2.5"));var s=engine.getBook("BTC-USDT").snapshot(5);eq(3,s[0].size(),"3 ask levels");eq(2,s[1].size(),"2 bid levels");}
}
