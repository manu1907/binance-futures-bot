package com.example.futuresbot.risk;

import com.example.futuresbot.domain.PositionSide;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeJournalRecord(
        Instant openedAt,
        Instant closedAt,
        String symbol,
        PositionSide side,
        BigDecimal grossRealizedPnlUsd,
        BigDecimal commissionUsd,
        BigDecimal fundingFeeUsd,
        BigDecimal netPnlUsd,
        String outcome,
        String openSource,
        String closeReason) {
    public String toCsvRow() {
        return String.join(",",
                openedAt.toString(),
                closedAt.toString(),
                symbol,
                side.name(),
                grossRealizedPnlUsd.toPlainString(),
                commissionUsd.toPlainString(),
                fundingFeeUsd.toPlainString(),
                netPnlUsd.toPlainString(),
                outcome,
                openSource,
                closeReason.replace(",", ";"));
    }

    public static String csvHeader() {
        return "openedAt,closedAt,symbol,side,grossRealizedPnlUsd,commissionUsd,fundingFeeUsd,netPnlUsd,outcome,openSource,closeReason";
    }
}