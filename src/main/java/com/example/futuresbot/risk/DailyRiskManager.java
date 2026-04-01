package com.example.futuresbot.risk;

import com.example.futuresbot.domain.PositionKey;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class DailyRiskManager {
    private static final ZoneId RISK_ZONE = ZoneId.of("Europe/Paris");

    private final double maxDailyDrawdownPct;
    private final int maxConsecutiveLosses;
    private final TradeJournalCsvWriter journalWriter;
    private final Map<PositionKey, TrackedOpenPosition> trackedPositions = new ConcurrentHashMap<>();

    private LocalDate currentDay;
    private BigDecimal dayStartEquityUsd = BigDecimal.ZERO;
    private int consecutiveLosses;
    private HaltType haltType = HaltType.NONE;
    private String haltReason;

    public DailyRiskManager(double maxDailyDrawdownPct, int maxConsecutiveLosses, TradeJournalCsvWriter journalWriter) {
        this.maxDailyDrawdownPct = maxDailyDrawdownPct;
        this.maxConsecutiveLosses = maxConsecutiveLosses;
        this.journalWriter = journalWriter;
    }

    public synchronized void initialize(BigDecimal currentEquityUsd, Instant now) {
        currentDay = LocalDate.ofInstant(now, RISK_ZONE);
        dayStartEquityUsd = sanitizeEquity(currentEquityUsd);
        consecutiveLosses = 0;
        if (haltType == HaltType.RISK_LIMIT) {
            haltType = HaltType.NONE;
            haltReason = null;
        }
    }

    public synchronized RiskGateDecision evaluateCanTrade(BigDecimal currentEquityUsd, Instant now) {
        rollDayIfNeeded(currentEquityUsd, now);

        if (haltType != HaltType.NONE) {
            return RiskGateDecision.block(haltReason == null ? "Trading halted" : haltReason);
        }

        BigDecimal equity = sanitizeEquity(currentEquityUsd);
        if (dayStartEquityUsd.signum() > 0 && maxDailyDrawdownPct > 0.0d) {
            BigDecimal drawdownPct = dayStartEquityUsd.subtract(equity)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(dayStartEquityUsd, 8, RoundingMode.HALF_UP);

            if (drawdownPct.compareTo(BigDecimal.valueOf(maxDailyDrawdownPct)) >= 0) {
                haltType = HaltType.RISK_LIMIT;
                haltReason = "Daily drawdown limit breached: " + drawdownPct + "%";
                return RiskGateDecision.block(haltReason);
            }
        }

        if (maxConsecutiveLosses > 0 && consecutiveLosses >= maxConsecutiveLosses) {
            haltType = HaltType.RISK_LIMIT;
            haltReason = "Max consecutive losses reached: " + consecutiveLosses;
            return RiskGateDecision.block(haltReason);
        }

        return RiskGateDecision.allow();
    }

    public synchronized boolean isTracked(PositionKey key) {
        return trackedPositions.containsKey(key);
    }

    public synchronized void trackOpenPositionIfAbsent(
            PositionKey key,
            BigDecimal currentEquityUsd,
            Instant now,
            String openSource) {
        trackedPositions.computeIfAbsent(
                key,
                ignored -> new TrackedOpenPosition(now, sanitizeEquity(currentEquityUsd), openSource));
    }

    public synchronized Optional<TradeJournalRecord> onPositionClosed(
            PositionKey key,
            BigDecimal currentEquityUsd,
            Instant now,
            String closeReason) {
        TrackedOpenPosition openPosition = trackedPositions.remove(key);
        if (openPosition == null) {
            return Optional.empty();
        }

        BigDecimal exitEquity = sanitizeEquity(currentEquityUsd);
        BigDecimal pnlUsd = exitEquity.subtract(openPosition.entryEquityUsd());
        String outcome = pnlUsd.signum() < 0 ? "LOSS" : pnlUsd.signum() > 0 ? "WIN" : "FLAT";

        if ("LOSS".equals(outcome)) {
            consecutiveLosses += 1;
        } else {
            consecutiveLosses = 0;
        }

        TradeJournalRecord record = new TradeJournalRecord(
                openPosition.openedAt(),
                now,
                key.symbol(),
                key.side(),
                openPosition.entryEquityUsd(),
                exitEquity,
                pnlUsd.setScale(8, RoundingMode.HALF_UP),
                outcome,
                openPosition.openSource(),
                closeReason);
        journalWriter.append(record);
        return Optional.of(record);
    }

    private void rollDayIfNeeded(BigDecimal currentEquityUsd, Instant now) {
        LocalDate date = LocalDate.ofInstant(now, RISK_ZONE);
        if (currentDay == null || !currentDay.equals(date)) {
            currentDay = date;
            dayStartEquityUsd = sanitizeEquity(currentEquityUsd);
            consecutiveLosses = 0;
            if (haltType == HaltType.RISK_LIMIT) {
                haltType = HaltType.NONE;
                haltReason = null;
            }
        }
    }

    private BigDecimal sanitizeEquity(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private enum HaltType {
        NONE,
        RISK_LIMIT
    }

    private record TrackedOpenPosition(
            Instant openedAt,
            BigDecimal entryEquityUsd,
            String openSource) {
    }
}