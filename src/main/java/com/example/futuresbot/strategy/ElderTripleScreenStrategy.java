package com.example.futuresbot.strategy.elder;

import com.example.futuresbot.marketdata.Candle;
import com.example.futuresbot.marketdata.CandleInterval;
import com.example.futuresbot.strategy.IndicatorMath;
import com.example.futuresbot.strategy.SignalType;
import com.example.futuresbot.strategy.StrategyContext;
import com.example.futuresbot.strategy.TradeSignal;
import com.example.futuresbot.strategy.TradingStrategy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class ElderTripleScreenStrategy implements TradingStrategy {
    private static final int EMA_PERIOD = 34;
    private static final int RSI_PULLBACK_PERIOD = 2;
    private static final int ATR_PERIOD = 14;
    private static final int MIN_HIGHER_TIMEFRAME_BARS = 60;
    private static final int MIN_MIDDLE_TIMEFRAME_BARS = 30;
    private static final int MIN_TRIGGER_BARS = 20;

    @Override
    public Optional<TradeSignal> evaluate(StrategyContext context) {
        List<Candle> fourHourBars = context.bars(CandleInterval.HOUR_4);
        List<Candle> oneHourBars = context.bars(CandleInterval.HOUR_1);
        List<Candle> fifteenMinuteBars = context.bars(CandleInterval.MINUTES_15);

        if (fourHourBars.size() < MIN_HIGHER_TIMEFRAME_BARS
                || oneHourBars.size() < MIN_MIDDLE_TIMEFRAME_BARS
                || fifteenMinuteBars.size() < MIN_TRIGGER_BARS) {
            return Optional.empty();
        }

        Candle latest4h = fourHourBars.getLast();
        Candle latest1h = oneHourBars.getLast();
        Candle latest15m = fifteenMinuteBars.getLast();
        Candle previous15m = fifteenMinuteBars.get(fifteenMinuteBars.size() - 2);

        double currentEma34 = IndicatorMath.ema(fourHourBars, EMA_PERIOD, 0);
        double previousEma34 = IndicatorMath.ema(fourHourBars, EMA_PERIOD, 1);
        double rsi2 = IndicatorMath.rsi(oneHourBars, RSI_PULLBACK_PERIOD);
        double atr14 = IndicatorMath.atr(fifteenMinuteBars, ATR_PERIOD);

        boolean bullishRegime = latest4h.close().doubleValue() > currentEma34 && currentEma34 > previousEma34;
        boolean bearishRegime = latest4h.close().doubleValue() < currentEma34 && currentEma34 < previousEma34;

        boolean longTrigger = latest15m.close().doubleValue() > previous15m.high().doubleValue();
        boolean shortTrigger = latest15m.close().doubleValue() < previous15m.low().doubleValue();

        if (bullishRegime && rsi2 <= 10.0d && longTrigger) {
            return Optional.of(new TradeSignal(
                    context.symbol(),
                    SignalType.LONG_ENTRY,
                    latest15m.close(),
                    latest15m.closeTime(),
                    "4h bullish regime, 1h pullback RSI2=" + round(rsi2)
                            + ", 15m breakout confirmed, ATR14=" + round(atr14)
            ));
        }

        if (bearishRegime && rsi2 >= 90.0d && shortTrigger) {
            return Optional.of(new TradeSignal(
                    context.symbol(),
                    SignalType.SHORT_ENTRY,
                    latest15m.close(),
                    latest15m.closeTime(),
                    "4h bearish regime, 1h bounce RSI2=" + round(rsi2)
                            + ", 15m breakdown confirmed, ATR14=" + round(atr14)
            ));
        }

        return Optional.empty();
    }

    private String round(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }
}