package com.example.futuresbot.strategy.elder;

import com.example.futuresbot.marketdata.Candle;
import com.example.futuresbot.marketdata.CandleInterval;
import com.example.futuresbot.strategy.SignalType;
import com.example.futuresbot.strategy.StrategyContext;
import com.example.futuresbot.strategy.TradeSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ElderTripleScreenStrategyTest {

    private static final String SYMBOL = "BTCUSDT";

    private final ElderTripleScreenStrategy strategy = new ElderTripleScreenStrategy();

    @Test
    void returnsLongEntryWhenAllThreeScreensAlignBullishly() {
        StrategyContext context = new StrategyContext(
                SYMBOL,
                candlesByInterval(
                        bullish4hBars(),
                        bullishPullback1hBars(),
                        bullishBreakout15mBars()));

        Optional<TradeSignal> result = strategy.evaluate(context);

        assertTrue(result.isPresent(), "Expected a bullish Elder signal");
        TradeSignal signal = result.orElseThrow();

        assertEquals(SYMBOL, signal.symbol());
        assertEquals(SignalType.LONG_ENTRY, signal.type());
        assertEquals(0, signal.referencePrice().compareTo(BigDecimal.valueOf(112.0)));
        assertTrue(signal.reason().contains("Strict Elder long"));
        assertTrue(signal.reason().contains("Force Index(2)<0"));
        assertTrue(signal.reason().contains("15m breakout confirmed"));
    }

    @Test
    void returnsShortEntryWhenAllThreeScreensAlignBearishly() {
        StrategyContext context = new StrategyContext(
                SYMBOL,
                candlesByInterval(
                        bearish4hBars(),
                        bearishBounce1hBars(),
                        bearishBreakdown15mBars()));

        Optional<TradeSignal> result = strategy.evaluate(context);

        assertTrue(result.isPresent(), "Expected a bearish Elder signal");
        TradeSignal signal = result.orElseThrow();

        assertEquals(SYMBOL, signal.symbol());
        assertEquals(SignalType.SHORT_ENTRY, signal.type());
        assertEquals(0, signal.referencePrice().compareTo(BigDecimal.valueOf(188.0)));
        assertTrue(signal.reason().contains("Strict Elder short"));
        assertTrue(signal.reason().contains("Force Index(2)>0"));
        assertTrue(signal.reason().contains("15m breakdown confirmed"));
    }

    @Test
    void returnsEmptyWhenAnyScreenHasInsufficientHistory() {
        StrategyContext context = new StrategyContext(
                SYMBOL,
                candlesByInterval(
                        bullish4hBars().subList(0, 79),
                        bullishPullback1hBars(),
                        bullishBreakout15mBars()));

        Optional<TradeSignal> result = strategy.evaluate(context);

        assertTrue(result.isEmpty(), "Expected no signal when 4h history is too short");
    }

    private static Map<CandleInterval, List<Candle>> candlesByInterval(
            List<Candle> bars4h,
            List<Candle> bars1h,
            List<Candle> bars15m) {

        Map<CandleInterval, List<Candle>> values = new EnumMap<>(CandleInterval.class);
        values.put(CandleInterval.HOUR_4, bars4h);
        values.put(CandleInterval.HOUR_1, bars1h);
        values.put(CandleInterval.MINUTES_15, bars15m);
        return values;
    }

    private static List<Candle> bullish4hBars() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            closes.add(100.0 + i);
        }
        double value = closes.getLast();
        for (int i = 0; i < 10; i++) {
            value += 3.0;
            closes.add(value);
        }
        return candles(SYMBOL, CandleInterval.HOUR_4, closes, 100.0);
    }

    private static List<Candle> bearish4hBars() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            closes.add(300.0 - i);
        }
        double value = closes.getLast();
        for (int i = 0; i < 10; i++) {
            value -= 3.0;
            closes.add(value);
        }
        return candles(SYMBOL, CandleInterval.HOUR_4, closes, 100.0);
    }

    private static List<Candle> bullishPullback1hBars() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 38; i++) {
            closes.add(200.0 + i);
        }
        closes.add(235.0);
        closes.add(233.0);
        return candles(SYMBOL, CandleInterval.HOUR_1, closes, 100.0);
    }

    private static List<Candle> bearishBounce1hBars() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 38; i++) {
            closes.add(300.0 - i);
        }
        closes.add(265.0);
        closes.add(267.0);
        return candles(SYMBOL, CandleInterval.HOUR_1, closes, 100.0);
    }

    private static List<Candle> bullishBreakout15mBars() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            closes.add(100.0 + (i * 0.5));
        }
        closes.add(110.0);
        closes.add(112.0);
        return candles(SYMBOL, CandleInterval.MINUTES_15, closes, 100.0);
    }

    private static List<Candle> bearishBreakdown15mBars() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            closes.add(200.0 - (i * 0.5));
        }
        closes.add(190.0);
        closes.add(188.0);
        return candles(SYMBOL, CandleInterval.MINUTES_15, closes, 100.0);
    }

    private static List<Candle> candles(
            String symbol,
            CandleInterval interval,
            List<Double> closes,
            double volume) {

        List<Candle> result = new ArrayList<>();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        long stepSeconds = switch (interval) {
            case MINUTES_15 -> 15 * 60L;
            case HOUR_1 -> 60 * 60L;
            case HOUR_4 -> 4 * 60 * 60L;
        };

        for (int i = 0; i < closes.size(); i++) {
            double close = closes.get(i);
            double open = i == 0 ? close : closes.get(i - 1);
            double high = Math.max(open, close) + 1.0;
            double low = Math.min(open, close) - 1.0;

            Instant openTime = start.plusSeconds(stepSeconds * i);
            Instant closeTime = openTime.plusSeconds(stepSeconds - 1);

            result.add(new Candle(
                    symbol,
                    interval,
                    openTime,
                    closeTime,
                    BigDecimal.valueOf(open),
                    BigDecimal.valueOf(high),
                    BigDecimal.valueOf(low),
                    BigDecimal.valueOf(close),
                    BigDecimal.valueOf(volume),
                    true));
        }

        return result;
    }
}