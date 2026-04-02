package com.example.futuresbot.replay;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ReplaySummary {
    private final String name;

    private int trades;
    private int wins;
    private int losses;
    private int flats;

    private BigDecimal grossPnlUsd = BigDecimal.ZERO;
    private BigDecimal totalFeesUsd = BigDecimal.ZERO;
    private BigDecimal netPnlUsd = BigDecimal.ZERO;

    public ReplaySummary(String name) {
        this.name = name;
    }

    public void accept(ReplayTradeRecord record) {
        trades++;
        grossPnlUsd = grossPnlUsd.add(record.grossPnlUsd());
        totalFeesUsd = totalFeesUsd.add(record.totalFeesUsd());
        netPnlUsd = netPnlUsd.add(record.netPnlUsd());

        if (record.netPnlUsd().signum() > 0) {
            wins++;
        } else if (record.netPnlUsd().signum() < 0) {
            losses++;
        } else {
            flats++;
        }
    }

    public String formatLine(BigDecimal finalEquityUsd) {
        double winRate = trades == 0 ? 0.0d : (wins * 100.0d / trades);
        return String.format(
                java.util.Locale.US,
                "Replay summary name=%s trades=%d wins=%d losses=%d flats=%d winRate=%.2f%% grossPnl=%s fees=%s netPnl=%s finalEquity=%s",
                name,
                trades,
                wins,
                losses,
                flats,
                winRate,
                grossPnlUsd.setScale(8, RoundingMode.HALF_UP).toPlainString(),
                totalFeesUsd.setScale(8, RoundingMode.HALF_UP).toPlainString(),
                netPnlUsd.setScale(8, RoundingMode.HALF_UP).toPlainString(),
                finalEquityUsd.setScale(8, RoundingMode.HALF_UP).toPlainString()
        );
    }
}