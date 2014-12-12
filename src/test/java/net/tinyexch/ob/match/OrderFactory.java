package net.tinyexch.ob.match;

import net.tinyexch.order.Order;
import net.tinyexch.order.OrderType;
import net.tinyexch.order.Side;

import java.time.Instant;

/**
 * Create simple orders for test purpose.
 *
 * @author ratzlow@gmail.com
 * @since 2014-10-29
 */
public class OrderFactory {
    public static final String timePattern = "HH:mm:ss";

    private static int clientOrderIdSequence = 0;

    public static Order buyL(double price, int qty) {
        return newOrder( Side.BUY, price, qty, OrderType.LIMIT );
    }

    /**
     * @param timestamp #timePattern
     */
    public static Order buyL(double price, int qty, Instant timestamp ) {
        return newOrder( Side.BUY, price, qty, OrderType.LIMIT ).setTimestamp(timestamp);
    }

    public static Order sellL(double price, int qty) {
        return newOrder( Side.SELL, price, qty, OrderType.LIMIT );
    }

    public static Order buyM(int qty) {
        return newOrder( Side.BUY, 0, qty, OrderType.MARKET );
    }

    public static Order buyH(double price, int qty) {
        return newOrder(Side.BUY, price, qty, OrderType.HIDDEN);
    }

    public static Order sellM(int qty) {
        return newOrder( Side.SELL, 0, qty, OrderType.MARKET );
    }

    public static Order newOrder(Side side, double price, int qty, OrderType type ) {
        return Order.of( Integer.toString(++clientOrderIdSequence), side ).setPrice(price)
                .setOrderQty(qty).setOrderType(type);
    }
}
