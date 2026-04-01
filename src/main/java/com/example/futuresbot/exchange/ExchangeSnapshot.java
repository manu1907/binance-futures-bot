package com.example.futuresbot.exchange;

import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.domain.PositionSnapshot;

import java.util.List;
import java.util.Optional;

public record ExchangeSnapshot(
        List<PositionSnapshot> positions,
        List<OpenOrderSnapshot> openOrders,
        List<AlgoOrderSnapshot> openAlgoOrders) {
    public Optional<PositionSnapshot> findPosition(PositionKey key) {
        return positions.stream()
                .filter(p -> p.symbol().equals(key.symbol()) && p.side() == key.side() && !p.isFlat())
                .findFirst();
    }

    public boolean hasAnyOpenRegularOrder(PositionKey key) {
        return openOrders.stream()
                .anyMatch(order -> order.symbol().equals(key.symbol()) && order.positionSide() == key.side());
    }

    public boolean hasAnyOpenAlgoOrder(PositionKey key) {
        return openAlgoOrders.stream()
                .anyMatch(order -> order.symbol().equals(key.symbol()) && order.positionSide() == key.side());
    }

    public boolean hasProtectiveStop(PositionKey key) {
        return openOrders.stream().anyMatch(order -> order.symbol().equals(key.symbol())
                && order.positionSide() == key.side()
                && isStopType(order.type()))
                || openAlgoOrders.stream().anyMatch(order -> order.symbol().equals(key.symbol())
                        && order.positionSide() == key.side()
                        && isStopType(order.orderType()));
    }

    public boolean hasTakeProfit(PositionKey key) {
        return openOrders.stream().anyMatch(order -> order.symbol().equals(key.symbol())
                && order.positionSide() == key.side()
                && isTakeProfitType(order.type()))
                || openAlgoOrders.stream().anyMatch(order -> order.symbol().equals(key.symbol())
                        && order.positionSide() == key.side()
                        && isTakeProfitType(order.orderType()));
    }

    private boolean isStopType(String value) {
        return "STOP".equalsIgnoreCase(value)
                || "STOP_MARKET".equalsIgnoreCase(value)
                || "TRAILING_STOP_MARKET".equalsIgnoreCase(value);
    }

    private boolean isTakeProfitType(String value) {
        return "TAKE_PROFIT".equalsIgnoreCase(value)
                || "TAKE_PROFIT_MARKET".equalsIgnoreCase(value);
    }
}