package com.example.futuresbot.replay;

import com.example.futuresbot.strategy.SignalType;

import java.math.BigDecimal;
import java.time.Instant;

public record ReplayTradeRecord(
        String symbol,
        SignalType signalType,
        Instant entryTime,
        Instant exitTime,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        BigDecimal quantity,
        BigDecimal grossPnlUsd,
        BigDecimal totalFeesUsd,
        BigDecimal netPnlUsd,
        String exitReason,
        BigDecimal equityAfterUsd) {
    public String toCsvRow() {
        return String.join(",",
                symbol,
                signalType.name(),
                entryTime.toString(),
                exitTime.toString(),
                entryPrice.toPlainString(),
                exitPrice.toPlainString(),
                quantity.toPlainString(),
                grossPnlUsd.toPlainString(),
                totalFeesUsd.toPlainString(),
                netPnlUsd.toPlainString(),
                exitReason,
                equityAfterUsd.toPlainString());
    }

    public static String csvHeader() {
        return "symbol,signalType,entryTime,exitTime,entryPrice,exitPrice,quantity,grossPnlUsd,totalFeesUsd,netPnlUsd,exitReason,equityAfterUsd";
    }
}