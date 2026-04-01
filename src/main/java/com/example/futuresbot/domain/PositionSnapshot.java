package com.example.futuresbot.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record PositionSnapshot(
        String symbol,
        PositionSide side,
        BigDecimal quantity,
        BigDecimal entryPrice,
        BigDecimal breakEvenPrice,
        BigDecimal unrealizedPnl,
        BigDecimal liquidationPrice,
        boolean hasProtectiveStop,
        boolean hasTakeProfit,
        Instant updateTime) {
    public boolean isFlat() {
        return quantity == null || quantity.signum() == 0;
    }
}