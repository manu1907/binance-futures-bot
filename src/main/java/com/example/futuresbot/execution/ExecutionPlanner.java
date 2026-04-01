package com.example.futuresbot.execution;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.marketdata.Candle;
import com.example.futuresbot.marketdata.CandleInterval;
import com.example.futuresbot.strategy.IndicatorMath;
import com.example.futuresbot.strategy.SignalType;
import com.example.futuresbot.strategy.StrategyContext;
import com.example.futuresbot.strategy.TradeSignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class ExecutionPlanner {
    private static final int ATR_PERIOD = 14;

    private final RiskSizer riskSizer;

    public ExecutionPlanner(RiskSizer riskSizer) {
        this.riskSizer = riskSizer;
    }

    public OrderPlan plan(
            TradeSignal signal,
            StrategyContext context,
            AccountEquitySnapshot equity,
            AppConfig.TradingConfig tradingConfig) {
        List<Candle> bars = context.bars(CandleInterval.MINUTES_15);
        if (bars.size() <= ATR_PERIOD) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Not enough 15m bars for ATR");
        }

        BigDecimal sizingEquityUsd = equity.sizingEquity(
                tradingConfig.riskCapitalMode(),
                BigDecimal.valueOf(tradingConfig.effectiveCapitalUsd()));

        if (sizingEquityUsd.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Sizing equity is zero");
        }

        BigDecimal atrUsd = BigDecimal.valueOf(IndicatorMath.atr(bars, ATR_PERIOD))
                .setScale(8, RoundingMode.HALF_UP);

        if (atrUsd.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "ATR is zero");
        }

        BigDecimal entryPrice = signal.referencePrice();
        BigDecimal stopDistance = atrUsd.multiply(BigDecimal.valueOf(tradingConfig.stopAtrMultiple()))
                .setScale(8, RoundingMode.HALF_UP);

        if (stopDistance.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Stop distance is zero");
        }

        BigDecimal stopPrice;
        BigDecimal takeProfitPrice;

        if (signal.type() == SignalType.LONG_ENTRY) {
            stopPrice = entryPrice.subtract(stopDistance);
            takeProfitPrice = entryPrice.add(
                    stopDistance.multiply(BigDecimal.valueOf(tradingConfig.takeProfitRiskReward())));
        } else {
            stopPrice = entryPrice.add(stopDistance);
            takeProfitPrice = entryPrice.subtract(
                    stopDistance.multiply(BigDecimal.valueOf(tradingConfig.takeProfitRiskReward())));
        }

        if (stopPrice.signum() <= 0 || takeProfitPrice.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Invalid stop or take-profit price");
        }

        BigDecimal riskAmountUsd = sizingEquityUsd
                .multiply(BigDecimal.valueOf(tradingConfig.maxRiskPerTradePct()))
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

        if (riskAmountUsd.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Risk amount is zero");
        }

        BigDecimal quantity = riskSizer.quantityForRisk(riskAmountUsd, entryPrice, stopPrice);
        if (quantity.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Calculated quantity is zero");
        }

        BigDecimal notionalUsd = quantity.multiply(entryPrice).setScale(8, RoundingMode.HALF_UP);
        if (notionalUsd.compareTo(BigDecimal.valueOf(tradingConfig.minimumTradeNotionalUsd())) < 0) {
            return OrderPlan.rejected(
                    signal.symbol(),
                    signal.type(),
                    "Trade notional " + notionalUsd + " is below minimum " + tradingConfig.minimumTradeNotionalUsd());
        }

        return OrderPlan.accepted(
                signal.symbol(),
                signal.type(),
                entryPrice.setScale(8, RoundingMode.HALF_UP),
                stopPrice.setScale(8, RoundingMode.HALF_UP),
                takeProfitPrice.setScale(8, RoundingMode.HALF_UP),
                quantity,
                notionalUsd,
                sizingEquityUsd.setScale(8, RoundingMode.HALF_UP),
                riskAmountUsd,
                atrUsd);
    }
}