package com.example.futuresbot.strategy;
import com.example.futuresbot.marketdata.Candle;

import java.util.List;

public final class IndicatorMath {
    private IndicatorMath() {}

    public static double ema(List<Candle> candles, int period, int offsetFromEnd) {
        int usableSize = candles.size() - offsetFromEnd;
        if (usableSize < period) {
            throw new IllegalArgumentException("Not enough candles for EMA period=" + period);
        }

        double multiplier = 2.0d / (period + 1.0d);
        double ema = candles.subList(0, period).stream()
                .mapToDouble(candle -> candle.close().doubleValue())
                .average()
                .orElseThrow();

        for (int index = period; index < usableSize; index++) {
            double close = candles.get(index).close().doubleValue();
            ema = ((close - ema) * multiplier) + ema;
        }
        return ema;
    }

    public static double rsi(List<Candle> candles, int period) {
        if (candles.size() <= period) {
            throw new IllegalArgumentException("Not enough candles for RSI period=" + period);
        }

        int start = candles.size() - period;
        double gains = 0.0d;
        double losses = 0.0d;

        for (int index = start; index < candles.size(); index++) {
            double previous = candles.get(index - 1).close().doubleValue();
            double current = candles.get(index).close().doubleValue();
            double change = current - previous;
            if (change >= 0) {
                gains += change;
            } else {
                losses += Math.abs(change);
            }
        }

        if (losses == 0.0d) {
            return 100.0d;
        }
        if (gains == 0.0d) {
            return 0.0d;
        }

        double averageGain = gains / period;
        double averageLoss = losses / period;
        double rs = averageGain / averageLoss;
        return 100.0d - (100.0d / (1.0d + rs));
    }

    public static double atr(List<Candle> candles, int period) {
        if (candles.size() <= period) {
            throw new IllegalArgumentException("Not enough candles for ATR period=" + period);
        }

        double sumTrueRange = 0.0d;
        for (int index = candles.size() - period; index < candles.size(); index++) {
            Candle current = candles.get(index);
            double high = current.high().doubleValue();
            double low = current.low().doubleValue();
            double previousClose = candles.get(index - 1).close().doubleValue();

            double trueRange = Math.max(high - low,
                    Math.max(Math.abs(high - previousClose), Math.abs(low - previousClose)));
            sumTrueRange += trueRange;
        }
        return sumTrueRange / period;
    }
}