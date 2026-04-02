package com.example.futuresbot.strategy;

import com.example.futuresbot.marketdata.Candle;

import java.util.ArrayList;
import java.util.List;

public final class IndicatorMath {
    private IndicatorMath() {
    }

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

    public static double macdHistogram(List<Candle> candles, int fastPeriod, int slowPeriod, int signalPeriod,
            int offsetFromEnd) {
        int usableSize = candles.size() - offsetFromEnd;
        if (usableSize < slowPeriod + signalPeriod) {
            throw new IllegalArgumentException("Not enough candles for MACD histogram");
        }

        List<Double> closes = candles.subList(0, usableSize).stream()
                .map(candle -> candle.close().doubleValue())
                .toList();

        List<Double> fastEmaSeries = emaSeries(closes, fastPeriod);
        List<Double> slowEmaSeries = emaSeries(closes, slowPeriod);

        List<Double> macdLine = new ArrayList<>();
        int startIndex = slowPeriod - 1;
        for (int i = startIndex; i < closes.size(); i++) {
            macdLine.add(fastEmaSeries.get(i) - slowEmaSeries.get(i));
        }

        List<Double> signalLine = emaSeries(macdLine, signalPeriod);
        double latestMacd = macdLine.getLast();
        double latestSignal = signalLine.getLast();
        return latestMacd - latestSignal;
    }

    public static double forceIndexEma(List<Candle> candles, int emaPeriod, int offsetFromEnd) {
        int usableSize = candles.size() - offsetFromEnd;
        if (usableSize < emaPeriod + 2) {
            throw new IllegalArgumentException("Not enough candles for Force Index EMA");
        }

        List<Double> forceIndexValues = new ArrayList<>();
        for (int i = 1; i < usableSize; i++) {
            double previousClose = candles.get(i - 1).close().doubleValue();
            double currentClose = candles.get(i).close().doubleValue();
            double volume = candles.get(i).volume().doubleValue();
            forceIndexValues.add((currentClose - previousClose) * volume);
        }

        List<Double> smoothed = emaSeries(forceIndexValues, emaPeriod);
        return smoothed.getLast();
    }

    private static List<Double> emaSeries(List<Double> values, int period) {
        if (values.size() < period) {
            throw new IllegalArgumentException("Not enough values for EMA series");
        }

        List<Double> result = new ArrayList<>();
        double multiplier = 2.0d / (period + 1.0d);
        double ema = values.subList(0, period).stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElseThrow();

        for (int i = 0; i < period - 1; i++) {
            result.add(Double.NaN);
        }
        result.add(ema);

        for (int i = period; i < values.size(); i++) {
            double value = values.get(i);
            ema = ((value - ema) * multiplier) + ema;
            result.add(ema);
        }

        return result;
    }
}