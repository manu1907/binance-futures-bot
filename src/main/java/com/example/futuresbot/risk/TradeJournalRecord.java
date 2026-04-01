package com.example.futuresbot.risk;

import com.example.futuresbot.domain.PositionSide;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeJournalRecord(
        Instant openedAt,
        Instant closedAt,
        String symbol,
        PositionSide side,
        BigDecimal entryEquityUsd,
        BigDecimal exitEquityUsd,
        BigDecimal pnlUsd,
        String outcome,
        String openSource,
        String closeReason) {
    public String toCsvRow() {
        return String.join(",",
                openedAt.toString(),
                closedAt.toString(),
                symbol,
                side.name(),
                entryEquityUsd.toPlainString(),
                exitEquityUsd.toPlainString(),
                pnlUsd.toPlainString(),
                outcome,
                openSource,
                closeReason.replace(",", ";"));
    }

    public static String csvHeader() {
        return "openedAt,closedAt,symbol,side,entryEquityUsd,exitEquityUsd,pnlUsd,outcome,openSource,closeReason";
    }
}