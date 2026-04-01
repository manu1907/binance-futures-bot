package com.example.futuresbot.strategy.elder;

import com.example.futuresbot.marketdata.Candle;
import com.example.futuresbot.marketdata.CandleInterval;
import com.example.futuresbot.strategy.IndicatorMath;
import com.example.futuresbot.strategy.SignalType;
import com.example.futuresbot.strategy.StrategyContext;
import com.example.futuresbot.strategy.TradeSignal;
import com.example.futuresbot.strategy.TradingStrategy;

import java.util.List;
import java.util.Optional;

public final class ElderTripleScreenStrategy implements TradingStrategy {
    private static final int EMA_TREND_PERIOD = 13;
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;
    private static final int FORCE_INDEX_EMA = 2;
    private static final int ATR_PERIOD = 14;

    private static final int MIN_FIRST_SCREEN_BARS = 80;
    private static final int MIN_SECOND_SCREEN_BARS = 40;
    private static final int MIN_THIRD_SCREEN_BARS = 20;

    @Override
    public Optional<TradeSignal> evaluate(StrategyContext context) {
        List<Candle> firstScreenBars = context.bars(CandleInterval.HOUR_4);
        List<Candle> secondScreenBars = context.bars(CandleInterval.HOUR_1);
        List<Candle> thirdScreenBars = context.bars(CandleInterval.MINUTES_15);

        if (firstScreenBars.size() < MIN_FIRST_SCREEN_BARS
                || secondScreenBars.size() < MIN_SECOND_SCREEN_BARS
                || thirdScreenBars.size() < MIN_THIRD_SCREEN_BARS) {
            return Optional.empty();
        }

        Candle latest4h = firstScreenBars.getLast();
        Candle latest15m = thirdScreenBars.getLast();
        Candle previous15m = thirdScreenBars.get(thirdScreenBars.size() - 2);

        double ema13Current = IndicatorMath.ema(firstScreenBars, EMA_TREND_PERIOD, 0);
        double ema13Previous = IndicatorMath.ema(firstScreenBars, EMA_TREND_PERIOD, 1);

        double macdHistogramCurrent = IndicatorMath.macdHistogram(firstScreenBars, MACD_FAST, MACD_SLOW, MACD_SIGNAL,
                0);
        double macdHistogramPrevious = IndicatorMath.macdHistogram(firstScreenBars, MACD_FAST, MACD_SLOW, MACD_SIGNAL,
                1);

        double forceIndexCurrent = IndicatorMath.forceIndexEma(secondScreenBars, FORCE_INDEX_EMA, 0);
        double atr14 = IndicatorMath.atr(thirdScreenBars, ATR_PERIOD);

        boolean bullishTide = latest4h.close().doubleValue() > ema13Current
                && ema13Current >= ema13Previous
                && macdHistogramCurrent > macdHistogramPrevious;

        boolean bearishTide = latest4h.close().doubleValue() < ema13Current
                && ema13Current <= ema13Previous
                && macdHistogramCurrent < macdHistogramPrevious;

        boolean bullishSecondScreen = forceIndexCurrent < 0.0d;
        boolean bearishSecondScreen = forceIndexCurrent > 0.0d;

        boolean bullishThirdScreen = latest15m.close().doubleValue() > previous15m.high().doubleValue();
        boolean bearishThirdScreen = latest15m.close().doubleValue() < previous15m.low().doubleValue();

        if (bullishTide && bullishSecondScreen && bullishThirdScreen) {
            return Optional.of(new TradeSignal(
                    context.symbol(),
                    SignalType.LONG_ENTRY,
                    latest15m.close(),
                    latest15m.closeTime(),
                    "Strict Elder long: 4h bullish tide via EMA13 + rising MACD histogram, "
                            + "1h pullback via Force Index(2)<0, 15m breakout confirmed, ATR14=" + round(atr14)));
        }

        if (bearishTide && bearishSecondScreen && bearishThirdScreen) {
            return Optional.of(new TradeSignal(
                    context.symbol(),
                    SignalType.SHORT_ENTRY,
                    latest15m.close(),
                    latest15m.closeTime(),
                    "Strict Elder short: 4h bearish tide via EMA13 + falling MACD histogram, "
                            + "1h bounce via Force Index(2)>0, 15m breakdown confirmed, ATR14=" + round(atr14)));
        }

        return Optional.empty();
    }

    private String round(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }
}