package net.tinyexch.ob.match;

import net.tinyexch.ob.OrderbookSide;
import net.tinyexch.ob.RejectReason;
import net.tinyexch.ob.match.Match.State;
import net.tinyexch.ob.price.safeguard.VolatilityInterruption;
import net.tinyexch.ob.price.safeguard.VolatilityInterruptionGuard;
import net.tinyexch.order.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static net.tinyexch.ob.match.Match.State.ACCEPT;
import static net.tinyexch.ob.match.Match.State.REJECT;
import static net.tinyexch.order.ExecType.REJECTED;

/**
 * Strategy to match new incoming orders against given orderbook.
 *
 * Principle 1: Market orders are given the reference price as a "virtual" price. On this basis,
 * execution is carried out at the reference price provided that this does not violate price/time priority.
 *
 * Principle 2: If orders cannot be executed at the reference price, they are executed in accordance with price/time
 * priority by means of price determination above or below the reference price (non-executed bid market orders or
 * ask market orders) i.e. the price is determined by a limit within the order book or a limit of an incoming order.
 *
 * @author ratzlow@gmail.com
 * @since 2014-12-23
 */
// TODO (FRa) : (FRa) : consider TimeInForce for partial matching (e.g FOK, ...)
// TODO (FRa) : (FRa) : introduce match loop
public class ContinuousMatchEngine implements MatchEngine {
    private static final int NO_PRICE = -1;

    private final double referencePrice;
    private final VolatilityInterruptionGuard priceGuard;

    public ContinuousMatchEngine( double referencePrice, VolatilityInterruptionGuard guard ) {
        this.referencePrice = referencePrice;
        this.priceGuard = guard;
    }

    @Override
    public Match match(Order order, OrderbookSide otherSide, OrderbookSide thisSide) {
        Order remainingOrder = order;
        State state = ACCEPT;
        OrderType orderType = order.getOrderType();
        final MatchCollector matchCollector;
        if ( orderType == OrderType.LIMIT ) {
            matchCollector = matchLimit(order, otherSide, thisSide );

        } else if ( orderType == OrderType.MARKET ) {
            matchCollector = matchMarket( order, otherSide );

        } else if (orderType == OrderType.MARKET_TO_LIMIT ) {
            matchCollector = matchMarketToLimit( order, otherSide );
            List<Trade> trades = matchCollector.trades;
            if (order.getLeavesQty() > 0 && !matchCollector.volatilityInterruption.isPresent() && !trades.isEmpty()) {
                remainingOrder = createRemainingOrder(order, trades);
            }
            state = (trades.size() == 1 && trades.get(0).getExecType() == REJECTED) ? REJECT : ACCEPT;

        } else {
            throw new MatchException("Incoming order has unmatchable order type for continuous trading: " + order);
        }

        // update dyn reference price after incoming order was matched
        List<Trade> trades = matchCollector.trades;
        if ( !trades.isEmpty() ) {
            Trade lastTrade = trades.get(trades.size() - 1);
            priceGuard.updateDynamicRefPrice( lastTrade );
        }

        return new Match( remainingOrder, matchCollector.trades, state, matchCollector.volatilityInterruption );
    }


    private Order createRemainingOrder(Order unexecutedOrderPart, List<Trade> trades) {
        Trade firstTrade = trades.get(0);
        double newApplicableLimitPrice = firstTrade.getPrice();
        return unexecutedOrderPart.setOrderType(OrderType.LIMIT).setPrice(newApplicableLimitPrice);
    }

    private MatchCollector matchMarketToLimit(Order order, OrderbookSide otherSide) {
        final MatchCollector collector;
        boolean hasLimitOrdersOnly = !otherSide.getLimitOrders().isEmpty() && otherSide.getMarketOrders().isEmpty();
        if ( hasLimitOrdersOnly ) {
            collector = matchAgainstLimitOrders(order, otherSide );

        } else {
            final Order buy, sell;
            if (order.getSide() == Side.BUY) {
                buy = order;
                sell = null;
            } else {
                buy = null;
                sell = order;
            }

            Trade trade = Trade.of().setBuy(buy).setSell(sell).setExecType(REJECTED)
                                .setOrderRejectReason(RejectReason.INSUFFICIENT_OB_CONSTELLATION.getMsg());
            collector = new MatchCollector();
            collector.trades.add( trade );
        }

        return collector;
    }


    private MatchCollector matchAgainstLimitOrders( Order incomingOrder, OrderbookSide otherSide ) {
        final MatchCollector collector = new MatchCollector();

        int leavesQty = incomingOrder.getLeavesQty();
        boolean matchNext = true;
        while ( leavesQty > 0 && matchNext && !otherSide.getLimitOrders().isEmpty() ) {
            Side side = otherSide.getSide();
            OrderRetrievalResult retrievalResult = dequeueConditionally(otherSide.getLimitOrders(), leavesQty, Order::getPrice);
            int executedQty = addMatchResultToCollector(incomingOrder, collector, side, retrievalResult);
            leavesQty -= executedQty;
            matchNext = retrievalResult.isValidMatch();
        }

        return collector;
    }


    private MatchCollector matchLimit(Order incomingLimitOrder, OrderbookSide otherSide, OrderbookSide thisSide) {
        final MatchCollector collector = new MatchCollector();
        int leavesQty = incomingLimitOrder.getLeavesQty();
        boolean matchNext = true;
        while ( leavesQty > 0 && matchNext &&
                isLiquidityAvailable(otherSide, incomingLimitOrder.getPrice()) ) {

            Side side = otherSide.getSide();
            boolean hasMarketOrders = !otherSide.getMarketOrders().isEmpty();
            boolean hasLimitOrders = !otherSide.getLimitOrders().isEmpty();

            final OrderRetrievalResult retrievalResult;
            if ( hasMarketOrders && hasLimitOrders ) {
                Order bestLimitOtherSide = otherSide.getLimitOrders().peek();
                Order bestLimitThisSide = thisSide.getLimitOrders().peek();
                double lowestAsk = getBestAskPrice(bestLimitThisSide, bestLimitOtherSide, incomingLimitOrder);
                double highestBid = getBestBidPrice(bestLimitThisSide, bestLimitOtherSide, incomingLimitOrder);
                double executionPrice = calcExecutionPrice( otherSide.getSide(), highestBid, lowestAsk );
                retrievalResult = dequeueConditionally(otherSide.getMarketOrders(), leavesQty, order -> executionPrice);

            } else if ( !hasMarketOrders && hasLimitOrders ) {
                retrievalResult = dequeueConditionally(otherSide.getLimitOrders(), leavesQty, Order::getPrice);

            } else if ( hasMarketOrders && !hasLimitOrders ) {
                double executionPrice = calcExecutionPrice(side, incomingLimitOrder.getPrice());
                retrievalResult = dequeueConditionally(otherSide.getMarketOrders(), leavesQty, order -> executionPrice );

            } else {
                throw new MatchException("Matching not implemented! incomingLimitOrder to match: " + incomingLimitOrder);
            }

            int executedQty = addMatchResultToCollector(incomingLimitOrder, collector, side, retrievalResult);
            leavesQty -= executedQty;
            matchNext = retrievalResult.isValidMatch();
        }

        return collector;
    }


    private MatchCollector matchMarket( Order incomingOrder, OrderbookSide otherSide ) {
        final MatchCollector collector = new MatchCollector();
        int leavesQty = incomingOrder.getLeavesQty();
        boolean matchNext = true;
        while ( leavesQty > 0 && matchNext && isLiquidityAvailable(otherSide) ) {
            Side side = otherSide.getSide();
            boolean hasMarketOrders = !otherSide.getMarketOrders().isEmpty();
            boolean hasLimitOrders = !otherSide.getLimitOrders().isEmpty();
            final OrderRetrievalResult retrievalResult;
            if ( hasMarketOrders && !hasLimitOrders ) {
                retrievalResult = dequeueConditionally(otherSide.getMarketOrders(), leavesQty, order -> referencePrice);

            } else if ( !hasMarketOrders && hasLimitOrders ) {
                retrievalResult = dequeueConditionally(otherSide.getLimitOrders(), leavesQty, Order::getPrice);

            } else if ( hasMarketOrders && hasLimitOrders ) {
                double bestPriceOnOtherSide = otherSide.getLimitOrders().peek().getPrice();
                double executionPrice = calcExecutionPrice(side, bestPriceOnOtherSide);
                retrievalResult = dequeueConditionally(otherSide.getMarketOrders(), leavesQty, order -> executionPrice);

            } else {
                throw new UnsupportedOperationException("Matching not implemented! order to match: " + incomingOrder);
            }

            int executedQty = addMatchResultToCollector(incomingOrder, collector, side, retrievalResult);
            leavesQty -= executedQty;
            matchNext = retrievalResult.isValidMatch();
        }

        return collector;
    }


    /**
     * @param incomingOrder to be executed against other side
     * @param collector collecting all trades or interruption for this incoming order
     * @param otherSide of #incomingOrder
     * @param retrievalResult either matched order for incoming Order of other side or interruption
     * @return executed Qty
     */
    private int addMatchResultToCollector(Order incomingOrder, MatchCollector collector, Side otherSide,
                                          OrderRetrievalResult retrievalResult) {
        final int executionQty;
        if (retrievalResult.order.isPresent()) {
            Order otherSideOrder = retrievalResult.order.get();
            double executionPrice = retrievalResult.executionPrice.getAsDouble();
            Trade trade = createTrade( incomingOrder, otherSideOrder, otherSide, executionPrice);
            collector.trades.add( trade );
            executionQty = trade.getExecutionQty();
        } else {
            collector.volatilityInterruption = retrievalResult.volatilityInterruption;
            executionQty = 0;
        }

        return executionQty;
    }

    /**
     * Don't remove order from orderbook if it cannot be fully matched, just update it's open Qty.
     *
     * @param otherSideQueue the structure applicable for matching
     * @param incomingOrderLeaveQty the qty to fill for the incoming order
     * @return the order from other side, which will remain in the book with a reduced open size if it cannot be fully
     * matched otherwise it will be removed from the book OR a
     * {@link net.tinyexch.ob.price.safeguard.VolatilityInterruption} if the potential execution price left the predefined
     * price range
     */
    private OrderRetrievalResult dequeueConditionally( Queue<Order> otherSideQueue, int incomingOrderLeaveQty,
                                                       Function<Order, Double> getPotentialExecutionPrice ) {
        final OrderRetrievalResult result;
        if ( !otherSideQueue.isEmpty() ) {
            Order headOnQueue = otherSideQueue.peek();
            double potentialExecutionPrice = getPotentialExecutionPrice.apply(headOnQueue);
            Optional<VolatilityInterruption> volatilityInterruption =
                    priceGuard.checkIndicativePrice(potentialExecutionPrice);
            if ( !volatilityInterruption.isPresent() ) {
                Order tradedOrder;
                if ( headOnQueue.getLeavesQty() > incomingOrderLeaveQty ) {
                    tradedOrder = headOnQueue.mutableClone();
                    headOnQueue.setCumQty( headOnQueue.getCumQty() + incomingOrderLeaveQty );
                } else {
                    tradedOrder = otherSideQueue.poll();
                }
                result = new OrderRetrievalResult( tradedOrder, potentialExecutionPrice, null );

            } else {
                result = new OrderRetrievalResult( null, NO_PRICE, volatilityInterruption.get() );
            }

        } else {
            result = new OrderRetrievalResult(null, NO_PRICE, null );
        }

        return result;
    }


    private double calcExecutionPrice(Side otherSide, double bestPriceOnOtherSide ) {
        final double executionPrice;
        if (otherSide == Side.BUY) {
            executionPrice = referencePrice >= bestPriceOnOtherSide ? referencePrice : bestPriceOnOtherSide;
        } else {
            executionPrice = referencePrice <= bestPriceOnOtherSide ? referencePrice : bestPriceOnOtherSide;
        }

        return executionPrice;
    }


    private double calcExecutionPrice(Side otherSide, double highestBidLimit, double lowestAskLimit ) {
        double execPrice = NO_PRICE;
        if (otherSide == Side.BUY) {
            if (referencePrice >= highestBidLimit && referencePrice >= lowestAskLimit ) {
                execPrice = referencePrice;
            } else if ( highestBidLimit >= lowestAskLimit && highestBidLimit > referencePrice) {
                execPrice = highestBidLimit;
            } else if ( lowestAskLimit > highestBidLimit && lowestAskLimit > referencePrice ) {
                execPrice = lowestAskLimit;
            }
        } else {
            if (referencePrice <= highestBidLimit && referencePrice <= lowestAskLimit ) {
                execPrice = referencePrice;
            } else if (highestBidLimit <= lowestAskLimit && highestBidLimit <= referencePrice) {
                execPrice = highestBidLimit;
            } else if ( lowestAskLimit < highestBidLimit && lowestAskLimit < referencePrice) {
                execPrice = lowestAskLimit;
            }
        }

        if (execPrice == NO_PRICE) {
            String msg = String.format(
                    "Cannot define execution price for limit order. otherSide=%s, highestBidLimit=%f, lowestAskLimit=%f",
                    otherSide, highestBidLimit, lowestAskLimit);
            throw new MatchException(msg);
        }

        return execPrice;
    }


    private Trade createTrade(Order order, Order otherSideOrder, Side otherSide, double price ) {
        Order buy = otherSide == Side.BUY ? otherSideOrder.mutableClone() : order.mutableClone();
        Order sell = otherSide == Side.SELL ? otherSideOrder.mutableClone() : order.mutableClone();

        int takeQty = Math.min(buy.getLeavesQty(), sell.getLeavesQty());
        buy.setCumQty( buy.getCumQty() + takeQty );
        sell.setCumQty( sell.getCumQty() + takeQty );

        return Trade.of().setBuy(buy).setSell(sell).setExecutionQty(takeQty).setPrice(price).setExecType(ExecType.TRADE);
    }

    /**
     * @param otherSide all orders by their type on the other side
     * @return true ... there is open liquidity on the other side that might be used for matching
     */
    private boolean isLiquidityAvailable(OrderbookSide otherSide) {
        return !otherSide.getMarketOrders().isEmpty() ||
                !otherSide.getLimitOrders().isEmpty() ||
                !otherSide.getHiddenOrders().isEmpty() ||
                !otherSide.getHiddenOrders().isEmpty();
    }

    /**
     * @param otherSide orderbook side with orders to match against
     * @param limitPrice of this side order to check if we are in the market (against the other side best limit)
     * @return true ... order can be crossed with order on the other side
     */
    private boolean isLiquidityAvailable(OrderbookSide otherSide, double limitPrice ) {

        boolean hasExecutableLimitOrders = false;
        if (!otherSide.getLimitOrders().isEmpty()) {
            Order limitOnOtherSide = otherSide.getLimitOrders().peek();
            hasExecutableLimitOrders = otherSide.getSide() == Side.BUY ?
                                            isCrossedPrice(limitOnOtherSide.getPrice(), limitPrice) :
                                            isCrossedPrice(limitPrice, limitOnOtherSide.getPrice());
        }

        return !otherSide.getMarketOrders().isEmpty() ||
                hasExecutableLimitOrders ||
                !otherSide.getHiddenOrders().isEmpty() ||
                !otherSide.getHiddenOrders().isEmpty();
    }

    private boolean isCrossedPrice( double bid, double ask ) {
        return bid >= ask;
    }

    private double getBestAskPrice( Order bestThisSide, Order bestOtherSide, Order incoming ) {
        return getBestPrice(bestThisSide, bestOtherSide, incoming, Side.SELL, SELL_PRICE_ORDERING);
    }

    private double getBestBidPrice( Order bestThisSide, Order bestOtherSide, Order incoming ) {
        return getBestPrice(bestThisSide, bestOtherSide, incoming, Side.BUY, BUY_PRICE_ORDERING);
    }

    private double getBestPrice(Order bestThisSide, Order bestOtherSide, Order incoming, Side side, Comparator<Order> bestFirst ) {
        return Stream.of(bestThisSide, bestOtherSide, incoming)
                .filter( o -> o != null ).filter( o -> o.getSide() == side)
                .sorted(bestFirst).findFirst().get().getPrice();
    }

    private static class OrderRetrievalResult {
        final Optional<Order> order;
        final OptionalDouble executionPrice;
        final Optional<VolatilityInterruption> volatilityInterruption;

        public OrderRetrievalResult(Order order, double executionPrice, VolatilityInterruption volatilityInterruption) {
            this.order = Optional.ofNullable(order);
            this.volatilityInterruption = Optional.ofNullable(volatilityInterruption);
            this.executionPrice = executionPrice < 0 ? OptionalDouble.empty() : OptionalDouble.of(executionPrice);
        }

        boolean isValidMatch() {
            return order.isPresent() && executionPrice.isPresent() && !volatilityInterruption.isPresent();
        }
    }

    private static final class MatchCollector {
        final List<Trade> trades = new ArrayList<>();
        Optional<VolatilityInterruption> volatilityInterruption = Optional.empty();
    }
}