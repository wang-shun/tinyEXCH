package net.tinyexch.ob.match;

import net.tinyexch.exchange.trading.form.auction.DefaultPriceDeterminationPhase;
import net.tinyexch.exchange.trading.form.auction.PriceDeterminationPhase;
import net.tinyexch.exchange.trading.form.auction.PriceDeterminationResult;
import net.tinyexch.ob.Orderbook;
import net.tinyexch.order.Execution;
import net.tinyexch.order.Order;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static net.tinyexch.ob.TestConstants.ROUNDING_DELTA;
import static net.tinyexch.ob.match.OrderFactory.*;

/**
 * Test various matching strategies depending on a given orderbook situation.
 *
 * @author ratzlow@gmail.com
 * @since 2014-10-12
 */
public class AuctionMatchTest {

    /**
     * There is exactly one limit at which the highest order volume can be executed and which has the lowest surplus.
     * Corresponding to this limit, the auction price is fixed at € 200.
     */
    @Test
    public void testAuctionPriceEqLimit_Ex1() {
        Orderbook book = new Orderbook( new Order[]{ buyL(202, 200), buyL(201, 200), buyL(200, 300) },
                                        new Order[]{ sellL(200, 100), sellL(198, 200), sellL(197, 400) } );

        PriceDeterminationResult result = determinePrice(book);
        Assert.assertEquals(200D, getBidPrice(result), ROUNDING_DELTA);
        Assert.assertEquals(200D, getAskPrice(result), ROUNDING_DELTA);
        Assert.assertEquals( 0, result.getAskSurplus() );
        Assert.assertEquals( 0, result.getBidSurplus() );
        Assert.assertEquals( 200D, getAuctionPrice(result), ROUNDING_DELTA );
    }


    /**
     * There are several possible limits and there is a surplus on the bid.
     * Corresponding to the highest limit, the auction price is fixed at € 201.
     */
    @Test
    public void testAuctionPriceEqHighestLimit_Ex2() {
        Orderbook book = new Orderbook( new Order[]{ buyL(202, 400), buyL(201, 200) },
                                        new Order[]{ sellL(199, 300), sellL(198, 200) } );

        PriceDeterminationResult result = determinePrice(book);
        Assert.assertEquals( 201D, getBidPrice(result), ROUNDING_DELTA);
        Assert.assertEquals( 199D, getAskPrice(result), ROUNDING_DELTA);
        Assert.assertEquals( 0, result.getAskSurplus() );
        Assert.assertEquals( 100, result.getBidSurplus() );
        Assert.assertEquals( 201D, getAuctionPrice(result), ROUNDING_DELTA );
    }


    /**
     * There are several possible limits and there is a surplus on the ask.
     * Corresponding to the lowest limit, the auction price is fixed at € 199.
     */
    @Test
    public void testAuctionPriceEqLowestLimit_Ex3() {
        Orderbook book = new Orderbook( new Order[]{ buyL(202, 300), buyL(201, 200) },
                                        new Order[]{ sellL(199, 400), sellL(198, 200) } );

        PriceDeterminationResult result = determinePrice(book);
        Assert.assertEquals( 201D, getBidPrice(result), ROUNDING_DELTA);
        Assert.assertEquals( 199D, getAskPrice(result), ROUNDING_DELTA);
        Assert.assertEquals( 100, result.getAskSurplus() );
        Assert.assertEquals( 0, result.getBidSurplus() );
        Assert.assertEquals( 199D, getAuctionPrice(result), ROUNDING_DELTA );
    }


    /**
     * There are several possible limits and there is both an ask surplus and a bid surplus.
     *
     * The auction price either equals the reference price or is fixed according to the limit nearest to the reference
     * price.
     */
    @Test
    public void testAuctionPriceEqualsReferencePrice_Ex4() {
        Orderbook book = new Orderbook( new Order[]{ buyM(100), buyL(199, 100) },
                                        new Order[]{ sellM(100), sellL(202, 100) } );

        assertAuction("If the reference price is € 199, the auction price will be € 199.", book, 199D, 199D, 0, 0);
        assertAuction("If the reference price is € 200, the auction price will be € 199.", book, 200D, 199D, 0, 0);
    }

    /**
     * The auction price either equals the reference price or is fixed according to the limit
     * nearest to the reference price:
     */
    @Test
    public void testSeveralPossibleLimitsAndNoSurplus_Ex5() {
        Orderbook book = new Orderbook( new Order[]{ buyL(202, 300), buyL(201, 200) },
                                        new Order[]{ sellL(198, 200), sellL(199, 300) } );

        assertAuction("If the reference price is € 200, the auction price will be € 201.", book, 200D, 201D, 0, 0);
        assertAuction("If the reference price is € 202, the auction price will be € 201.", book, 202D, 201D, 0, 0);
        assertAuction("If the reference price is € 198, the auction price will be € 199.", book, 198D, 199D, 0, 0);
    }

    /**
     * Only market orders are executable in the order book.
     */
    @Test
    public void testOnlyMarketOrdersExecutableInOrderbook_Ex6() {
        Orderbook book = new Orderbook( new Order[]{ buyM(900)}, new Order[]{sellM(800)});

        assertAuction("The auction price is equal to the reference price=250", book, 250D, 250D, 0, 100);
        assertAuction("The auction price is equal to the reference price=99", book, 99D, 99D, 0, 100);
    }

    /**
     * There is no eligible limit as there are only orders in the order book which are not executable.
     */
    @Test
    public void testNoLimitNoExecutableOrders_Ex7() {
        Orderbook book = new Orderbook( new Order[]{ buyH(200, 80), buyL(199, 80)}, new Order[]{sellL(201, 80)} );

        PriceDeterminationResult result = determinePrice(book);
        Assert.assertEquals( "No matching ask qty expected!", 0, result.getMatchableAskQty(), ROUNDING_DELTA);
        Assert.assertEquals( "No matching bid qty expected!", 0, result.getMatchableBidQty(), ROUNDING_DELTA);
    }

    /**
     * If all prices are equal than match by time prio.
     */
    @Test
    public void testPartialExecutionInAcutionWithTimePrio_Ex7() {
        Order buy_1 = buyL(200, 300, time(9, 0, 0));
        Order buy_2 = buyL(200, 300, time(9, 1, 0));
        Orderbook book = new Orderbook( new Order[] {buy_1, buy_2},
                                        new Order[] { sellL(200, 400)} );

        PriceDeterminationResult result = determinePrice(book);
        Assert.assertEquals( "BidSurplus", 200, result.getBidSurplus(), ROUNDING_DELTA);
        Assert.assertEquals( "AskSurplus", 0, result.getAskSurplus(), ROUNDING_DELTA);
        Assert.assertEquals( "AuctionPrice", 200, result.getAuctionPrice().get(), ROUNDING_DELTA);

        List<Execution> executions = result.getExecutions();
        Assert.assertEquals("Executions", 2, executions.size());

        Execution firstExec = executions.get(0);
        Assert.assertEquals( 300, firstExec.getExecutionQty() );
        Assert.assertEquals( buy_1.getClientOrderID(), firstExec.getBuy().getClientOrderID() );
        Assert.assertEquals( 300, firstExec.getBuy().getOrderQty() );
        Assert.assertEquals( 300, firstExec.getBuy().getCumQty() );
        Assert.assertEquals( 400, firstExec.getSell().getOrderQty() );
        Assert.assertEquals( 300, firstExec.getSell().getCumQty() );

        Execution secondExec = executions.get(1);
        Assert.assertEquals( 100, secondExec.getExecutionQty() );
        Assert.assertEquals( buy_2.getClientOrderID(), secondExec.getBuy().getClientOrderID() );
        Assert.assertEquals( 300, secondExec.getBuy().getOrderQty() );
        Assert.assertEquals( 100, secondExec.getBuy().getCumQty() );
        Assert.assertEquals( 400, secondExec.getSell().getOrderQty() );
        Assert.assertEquals( 400, secondExec.getSell().getCumQty() );
    }


    private void assertAuction(String msg, Orderbook ob, double referencePrice,
                               double expectedAuctionPrice, int expectedAskSurplus, int expectedBidSurplus) {
        PriceDeterminationResult result = determinePrice(ob, referencePrice);
        Assert.assertEquals(msg, expectedAuctionPrice, getAuctionPrice(result), ROUNDING_DELTA);
        Assert.assertEquals("AskSurplus", expectedAskSurplus, result.getAskSurplus());
        Assert.assertEquals("BidSurplus", expectedBidSurplus, result.getBidSurplus());
    }

    private PriceDeterminationResult determinePrice( Orderbook orderbook ) {
        PriceDeterminationPhase phase = new DefaultPriceDeterminationPhase(orderbook);
        return phase.determinePrice();
    }

    private PriceDeterminationResult determinePrice( Orderbook orderbook, double referencePrice  ) {
        PriceDeterminationPhase phase = new DefaultPriceDeterminationPhase(orderbook, referencePrice );
        return phase.determinePrice();
    }

    private Double getAuctionPrice(PriceDeterminationResult result) {
        return result.getAuctionPrice().get();
    }

    private Double getAskPrice(PriceDeterminationResult result) {
        return result.getAskPrice().get();
    }

    private Double getBidPrice(PriceDeterminationResult result) {
        return result.getBidPrice().get();
    }

    private Instant time(int hour, int min, int sec) {
        return LocalDateTime.now().withHour(hour).withMinute(min).withSecond(sec).toInstant(ZoneOffset.UTC);
    }
}
