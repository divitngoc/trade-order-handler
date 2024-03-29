package com.iggroup.trade.consumer;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.NavigableSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.function.Consumer;

import com.iggroup.lock.OrdersLock;
import com.iggroup.model.Order;
import com.iggroup.model.Side;
import com.iggroup.provider.OrderBookProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class TradeOrderConsumer implements Runnable {

    private static final String SIDE_TRADE_EXECUTED_LOG_FORMAT = "OrderId [{}]: {} TRADE EXECUTED at price: [{}] and amount [{}] against OrderId [{}]";

    private final OrderBookProvider provider;
    private final BlockingQueue<Order> tradeQueue;
    private final Consumer<Order> removeConsumer;

    @Override
    public void run() {
        while (true) {
            Order order = null;
            try {
                order = tradeQueue.take();
                OrdersLock.acquireLock(order.getId()).lock();
                executeTradePlan(order);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                if (order != null) OrdersLock.unlock(order.getId());
            }
        }
    }

    /**
     * 
     * Checks if order price matches against the order (level 1 of order book). If
     * matches, execute trade. <br>
     * Scenario 1: Order fill the opposite side order but with quantity left,
     * continue to look for next level for price match and repeat <br>
     * Scenario 2: Order completely fill the opposite side order with all of the
     * quantity <br>
     * Scenario 3: Order unable to completely fill the opposite side order. <br>
     * 
     * @param order
     */
    public void executeTradePlan(final Order order) {
        if (!provider.checkIfOrderExists(order))
            return;

        final ConcurrentNavigableMap<BigDecimal, NavigableSet<Order>> orderMap = getOppositeSideOrderMap(order.getSymbol(), order.getSide());
        log.debug("ExecuteTradePlan for OrderId [{}]", order.getId());
        log.debug("Checking if price matches for trade...");
        for (Entry<BigDecimal, NavigableSet<Order>> entry : orderMap.entrySet()) {
            if (!priceMatch(entry.getKey(), order)) {
                log.debug("No further price match for trade.");
                return;
            }

            // Ask price is lower than/equal to bid price
            // Or the bid price is greater than/equal the ask price
            log.debug("Price matches...");
            int quantityLeft = executeTrade(order, entry);
            if (quantityLeft <= 0)
                return;
        }
    }

    private int executeTrade(final Order order, final Entry<BigDecimal, NavigableSet<Order>> entry) {
        for (final Order orderToTradeAgainst : entry.getValue()) {
            if (!isBeforeArrivalDateTime(order, orderToTradeAgainst))
                continue;

            log.debug("Price match against orderId: {}", orderToTradeAgainst.getId());
            OrdersLock.acquireLock(orderToTradeAgainst.getId()).lock();
            if (!provider.checkIfOrderExists(orderToTradeAgainst) || !checkIfPriceIsStillSameLevel(orderToTradeAgainst.getPrice().get(), entry.getKey())) {
                OrdersLock.unlock(orderToTradeAgainst.getId());
                continue;
            }

            log.debug("Trade executing for orderId [{}]... against orderId [{}]", order.getId(), orderToTradeAgainst.getId());
            int previousQuantTotal = order.getQuantity().getPlain();
            order.getQuantity().set(order.getQuantity().get() - orderToTradeAgainst.getQuantity().get());
            if (order.getQuantity().get() > 0) {
                // Order to trade is completely filled and order is partially filled
                orderCompletelyFilledAndOrderAgainstPartiallyFilled(order, orderToTradeAgainst, previousQuantTotal);
            } else if (order.getQuantity().get() == 0) {
                // Both orders is completely filled
                return orderAndOrderAgainstCompletelyFilled(order, orderToTradeAgainst);
            } else {
                // Order to trade partially filled and order is completely filled
                return orderPartiallyFilledAndOrderAgainstCompletelyFilled(order, orderToTradeAgainst, previousQuantTotal);
            }
        }
        return order.getQuantity().get();
    }

    private boolean checkIfPriceIsStillSameLevel(BigDecimal price, BigDecimal currentPriceLevel) {
        return price.compareTo(currentPriceLevel) == 0;
    }

    private void orderCompletelyFilledAndOrderAgainstPartiallyFilled(final Order order, final Order orderToTradeAgainst, int previousQuantTotal) {
        orderToTradeAgainst.getQuantity().set(0);
        removeConsumer.accept(orderToTradeAgainst);
        OrdersLock.unlock(orderToTradeAgainst.getId());
        log.debug(SIDE_TRADE_EXECUTED_LOG_FORMAT, order.getId(), order.getSide(), orderToTradeAgainst.getPrice().get(),
                  Math.abs(order.getQuantity().get() - previousQuantTotal), orderToTradeAgainst.getId());
    }

    private int orderAndOrderAgainstCompletelyFilled(final Order order, final Order orderToTradeAgainst) {
        order.getQuantity().set(0);
        orderToTradeAgainst.getQuantity().set(0);
        removeConsumer.accept(orderToTradeAgainst);
        removeConsumer.accept(order);
        OrdersLock.unlock(orderToTradeAgainst.getId());
        log.debug(SIDE_TRADE_EXECUTED_LOG_FORMAT, order.getId(), order.getSide(), orderToTradeAgainst.getPrice().get(), order.getQuantity(),
                  orderToTradeAgainst.getId());
        return order.getQuantity().get();
    }

    private int orderPartiallyFilledAndOrderAgainstCompletelyFilled(final Order order, final Order orderToTradeAgainst, int previousQuantTotal) {
        orderToTradeAgainst.getQuantity().set(Math.abs(order.getQuantity().get()));
        order.getQuantity().set(0);
        removeConsumer.accept(order);
        OrdersLock.unlock(orderToTradeAgainst.getId());
        log.debug(SIDE_TRADE_EXECUTED_LOG_FORMAT, order.getId(), order.getSide(), orderToTradeAgainst.getPrice().get(), previousQuantTotal,
                  orderToTradeAgainst.getId());
        return order.getQuantity().get();
    }

    private boolean isBeforeArrivalDateTime(Order order, Order orderToTradeAgainst) {
        return order.compareTo(orderToTradeAgainst) > 0;
    }

    /**
     * 
     * If Ask price is lower than/equal to bid price Or the bid price is
     * greater/equal than the ask price. The Price matches and will return true,
     * otherwise false
     * 
     * @param levelPrice
     * @param order
     * @return
     */
    private boolean priceMatch(BigDecimal levelPrice, Order order) {
        return levelPrice.compareTo(order.getPrice().get()) != (order.getSide() == Side.BUY ? 1 : -1);
    }

    private ConcurrentNavigableMap<BigDecimal, NavigableSet<Order>> getOppositeSideOrderMap(final String symbol, final Side side) {
        return provider.getOrderBookBySymbol(symbol).getOrders(side == Side.BUY ? Side.SELL : Side.BUY);
    }

}
