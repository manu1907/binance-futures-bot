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
    private static final int SCALE = 8;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal BPS_DIVISOR = BigDecimal.valueOf(10_000);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal AVAILABLE_MARGIN_BUFFER = BigDecimal.valueOf(0.95);

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
                .setScale(SCALE, RoundingMode.HALF_UP);
        if (atrUsd.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "ATR is zero");
        }

        BigDecimal entryPrice = signal.referencePrice().setScale(SCALE, RoundingMode.HALF_UP);
        if (entryPrice.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Entry price is zero");
        }

        BigDecimal stopDistance = atrUsd.multiply(BigDecimal.valueOf(tradingConfig.stopAtrMultiple()))
                .setScale(SCALE, RoundingMode.HALF_UP);
        if (stopDistance.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Stop distance is zero");
        }

        BigDecimal stopPrice;
        if (signal.type() == SignalType.LONG_ENTRY) {
            stopPrice = entryPrice.subtract(stopDistance);
        } else {
            stopPrice = entryPrice.add(stopDistance);
        }

        stopPrice = stopPrice.setScale(SCALE, RoundingMode.HALF_UP);
        if (stopPrice.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Invalid stop price");
        }

        BigDecimal feeRate = BigDecimal.valueOf(tradingConfig.takerFeeBps())
                .divide(BPS_DIVISOR, SCALE, RoundingMode.HALF_UP);

        BigDecimal stopFeePerUnit = roundTripFeePerUnit(entryPrice, stopPrice, feeRate);

        BigDecimal effectiveRiskPerUnit = entryPrice.subtract(stopPrice).abs()
                .add(stopFeePerUnit)
                .setScale(SCALE, RoundingMode.HALF_UP);

        if (effectiveRiskPerUnit.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Effective risk per unit is zero");
        }

        BigDecimal riskAmountUsd = sizingEquityUsd
                .multiply(BigDecimal.valueOf(tradingConfig.maxRiskPerTradePct()))
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP);

        if (riskAmountUsd.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Risk amount is zero");
        }

        BigDecimal riskSizedQuantity = riskAmountUsd.divide(effectiveRiskPerUnit, SCALE, RoundingMode.DOWN);
        if (riskSizedQuantity.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Calculated quantity is zero");
        }

        BigDecimal leverage = BigDecimal.valueOf(tradingConfig.defaultLeverage());
        if (leverage.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Configured leverage is zero");
        }

        BigDecimal availableBalanceUsd = positive(equity.availableBalanceUsd());
        BigDecimal maxAffordableNotionalUsd = availableBalanceUsd
                .multiply(leverage)
                .multiply(AVAILABLE_MARGIN_BUFFER)
                .setScale(SCALE, RoundingMode.DOWN);

        if (maxAffordableNotionalUsd.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Available margin is zero");
        }

        BigDecimal marginAffordableQuantity = maxAffordableNotionalUsd
                .divide(entryPrice, SCALE, RoundingMode.DOWN);

        if (marginAffordableQuantity.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Margin-affordable quantity is zero");
        }

        BigDecimal quantity = riskSizedQuantity.min(marginAffordableQuantity);
        if (quantity.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Calculated quantity is zero");
        }

        BigDecimal desiredNetRewardPerUnit = effectiveRiskPerUnit
                .multiply(BigDecimal.valueOf(tradingConfig.takeProfitRiskReward()))
                .setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal takeProfitPrice = solveFeeAwareTakeProfitPrice(
                signal.type(),
                entryPrice,
                desiredNetRewardPerUnit,
                feeRate);

        if (takeProfitPrice.signum() <= 0) {
            return OrderPlan.rejected(signal.symbol(), signal.type(), "Invalid take-profit price");
        }

        BigDecimal notionalUsd = quantity.multiply(entryPrice).setScale(SCALE, RoundingMode.HALF_UP);
        if (notionalUsd.compareTo(BigDecimal.valueOf(tradingConfig.minimumTradeNotionalUsd())) < 0) {
            return OrderPlan.rejected(
                    signal.symbol(),
                    signal.type(),
                    "Trade notional " + notionalUsd + " is below minimum " + tradingConfig.minimumTradeNotionalUsd());
        }

        return OrderPlan.accepted(
                signal.symbol(),
                signal.type(),
                entryPrice,
                stopPrice,
                takeProfitPrice.setScale(SCALE, RoundingMode.HALF_UP),
                quantity,
                notionalUsd,
                sizingEquityUsd.setScale(SCALE, RoundingMode.HALF_UP),
                riskAmountUsd,
                atrUsd);
    }

    private BigDecimal positive(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private BigDecimal roundTripFeePerUnit(
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            BigDecimal feeRate) {

        return entryPrice.multiply(feeRate)
                .add(exitPrice.multiply(feeRate))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal solveFeeAwareTakeProfitPrice(
            SignalType signalType,
            BigDecimal entryPrice,
            BigDecimal desiredNetRewardPerUnit,
            BigDecimal feeRate) {

        if (signalType == SignalType.LONG_ENTRY) {
            BigDecimal numerator = entryPrice.multiply(ONE.add(feeRate)).add(desiredNetRewardPerUnit);
            BigDecimal denominator = ONE.subtract(feeRate);
            return numerator.divide(denominator, SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal numerator = entryPrice.multiply(ONE.subtract(feeRate)).subtract(desiredNetRewardPerUnit);
        BigDecimal denominator = ONE.add(feeRate);
        return numerator.divide(denominator, SCALE, RoundingMode.HALF_UP);
    }
}