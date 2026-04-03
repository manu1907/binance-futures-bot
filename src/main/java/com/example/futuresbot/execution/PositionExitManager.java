package com.example.futuresbot.execution;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.domain.PositionSide;
import com.example.futuresbot.domain.PositionSnapshot;
import com.example.futuresbot.marketdata.Candle;
import com.example.futuresbot.marketdata.CandleInterval;
import com.example.futuresbot.strategy.IndicatorMath;
import com.example.futuresbot.strategy.StrategyContext;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

public final class PositionExitManager {
    private static final int EMA_TREND_PERIOD = 13;
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;
    private static final int ATR_PERIOD = 14;
    private static final double TP_EXTENSION_BUFFER_R = 0.25d;

    public PositionManagementDecision evaluate(
            PositionKey key,
            PositionSnapshot snapshot,
            OrderPlan entryPlan,
            ActiveProtectionState currentProtection,
            StrategyContext context,
            AppConfig.ExitManagementConfig cfg) {

        if (!cfg.enabled()) {
            return PositionManagementDecision.noChange("Dynamic exits disabled");
        }
        if (snapshot == null || !snapshot.isOpen()) {
            return PositionManagementDecision.noChange("No live position");
        }
        if (entryPlan == null || !entryPlan.accepted()) {
            return PositionManagementDecision.noChange("No accepted entry plan");
        }
        if (currentProtection == null) {
            return PositionManagementDecision.noChange("No tracked protection state");
        }

        List<Candle> bars15 = context.bars(CandleInterval.MINUTES_15);
        List<Candle> bars4h = context.bars(CandleInterval.HOUR_4);
        if (bars15.size() < Math.max(ATR_PERIOD + 2, cfg.trailingSwingLookbackBars())
                || bars4h.size() < 80) {
            return PositionManagementDecision.noChange("Insufficient bars for exit management");
        }

        Candle latest15 = bars15.getLast();
        BigDecimal entry = entryPlan.entryPrice();
        BigDecimal initialStop = entryPlan.stopPrice();
        BigDecimal riskPerUnit = entry.subtract(initialStop).abs();

        if (riskPerUnit.signum() <= 0) {
            return PositionManagementDecision.noChange("Initial risk per unit is zero");
        }

        BigDecimal currentClose = latest15.close();
        double currentR = unrealizedR(snapshot.side(), entry, currentClose, riskPerUnit);

        BigDecimal newStop = currentProtection.stopTriggerPrice();
        BigDecimal newTakeProfit = currentProtection.takeProfitTriggerPrice();

        boolean changed = false;
        StringBuilder reason = new StringBuilder();

        if (currentR >= cfg.breakEvenAfterR()) {
            BigDecimal candidate = betterStop(snapshot.side(), newStop, entry);
            if (candidate.compareTo(newStop) != 0) {
                newStop = candidate;
                changed = true;
                reason.append("breakeven;");
            }
        }

        if (currentR >= cfg.trailingStopActivationR()) {
            BigDecimal trailingCandidate = trailingStopCandidate(snapshot.side(), bars15, cfg.trailingSwingLookbackBars(),
                    BigDecimal.valueOf(IndicatorMath.atr(bars15, ATR_PERIOD)),
                    BigDecimal.valueOf(cfg.trailingStopAtrBuffer()));

            BigDecimal candidate = betterStop(snapshot.side(), newStop, trailingCandidate);
            if (candidate.compareTo(newStop) != 0) {
                newStop = candidate;
                changed = true;
                reason.append("trail-stop;");
            }
        }

        if (currentR >= cfg.extendTakeProfitActivationR()
                && isNearCurrentTakeProfit(snapshot.side(), currentClose, newTakeProfit, riskPerUnit)
                && higherTimeframeTrendStillFavorable(snapshot.side(), bars4h)) {

            BigDecimal tpStep = riskPerUnit.multiply(BigDecimal.valueOf(cfg.extendTakeProfitStepR()));
            BigDecimal maxTp = cappedTakeProfit(snapshot.side(), entry, riskPerUnit, cfg.maxTakeProfitRiskReward());
            BigDecimal candidate = extendedTakeProfit(snapshot.side(), newTakeProfit, tpStep, maxTp);

            if (candidate.compareTo(newTakeProfit) != 0) {
                newTakeProfit = candidate;
                changed = true;
                reason.append("extend-tp;");
            }
        }

        if (!changed) {
            return PositionManagementDecision.noChange("No protection change");
        }

        return PositionManagementDecision.update(newStop, newTakeProfit, reason.toString());
    }

    private double unrealizedR(
            PositionSide side,
            BigDecimal entry,
            BigDecimal currentClose,
            BigDecimal riskPerUnit) {

        BigDecimal pnlPerUnit = side == PositionSide.LONG
                ? currentClose.subtract(entry)
                : entry.subtract(currentClose);

        return pnlPerUnit.divide(riskPerUnit, 8, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private BigDecimal betterStop(PositionSide side, BigDecimal currentStop, BigDecimal candidate) {
        return side == PositionSide.LONG
                ? candidate.max(currentStop)
                : candidate.min(currentStop);
    }

    private BigDecimal trailingStopCandidate(
            PositionSide side,
            List<Candle> bars15,
            int lookbackBars,
            BigDecimal atr15,
            BigDecimal atrBufferMultiple) {

        int fromIndex = Math.max(0, bars15.size() - lookbackBars);
        List<Candle> window = bars15.subList(fromIndex, bars15.size());
        BigDecimal atrBuffer = atr15.multiply(atrBufferMultiple);

        if (side == PositionSide.LONG) {
            BigDecimal swingLow = window.stream()
                    .map(Candle::low)
                    .min(Comparator.naturalOrder())
                    .orElseThrow();
            return swingLow.subtract(atrBuffer);
        }

        BigDecimal swingHigh = window.stream()
                .map(Candle::high)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        return swingHigh.add(atrBuffer);
    }

    private boolean isNearCurrentTakeProfit(
            PositionSide side,
            BigDecimal currentClose,
            BigDecimal currentTakeProfit,
            BigDecimal riskPerUnit) {

        BigDecimal remaining = side == PositionSide.LONG
                ? currentTakeProfit.subtract(currentClose)
                : currentClose.subtract(currentTakeProfit);

        double remainingR = remaining.divide(riskPerUnit, 8, java.math.RoundingMode.HALF_UP).doubleValue();
        return remainingR <= TP_EXTENSION_BUFFER_R;
    }

    private BigDecimal cappedTakeProfit(
            PositionSide side,
            BigDecimal entry,
            BigDecimal riskPerUnit,
            double maxRiskReward) {

        BigDecimal extension = riskPerUnit.multiply(BigDecimal.valueOf(maxRiskReward));
        return side == PositionSide.LONG
                ? entry.add(extension)
                : entry.subtract(extension);
    }

    private BigDecimal extendedTakeProfit(
            PositionSide side,
            BigDecimal currentTakeProfit,
            BigDecimal tpStep,
            BigDecimal maxTakeProfit) {

        if (side == PositionSide.LONG) {
            return currentTakeProfit.add(tpStep).min(maxTakeProfit);
        }
        return currentTakeProfit.subtract(tpStep).max(maxTakeProfit);
    }

    private boolean higherTimeframeTrendStillFavorable(PositionSide side, List<Candle> bars4h) {
        Candle latest4h = bars4h.getLast();

        double ema13Current = IndicatorMath.ema(bars4h, EMA_TREND_PERIOD, 0);
        double ema13Previous = IndicatorMath.ema(bars4h, EMA_TREND_PERIOD, 1);

        double macdHistogramCurrent = IndicatorMath.macdHistogram(
                bars4h, MACD_FAST, MACD_SLOW, MACD_SIGNAL, 0);
        double macdHistogramPrevious = IndicatorMath.macdHistogram(
                bars4h, MACD_FAST, MACD_SLOW, MACD_SIGNAL, 1);

        boolean bullish = latest4h.close().doubleValue() > ema13Current
                && ema13Current >= ema13Previous
                && macdHistogramCurrent > macdHistogramPrevious;

        boolean bearish = latest4h.close().doubleValue() < ema13Current
                && ema13Current <= ema13Previous
                && macdHistogramCurrent < macdHistogramPrevious;

        return side == PositionSide.LONG ? bullish : bearish;
    }
}