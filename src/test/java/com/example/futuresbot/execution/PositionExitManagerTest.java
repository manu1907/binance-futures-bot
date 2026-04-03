package com.example.futuresbot.execution;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.domain.PositionSide;
import com.example.futuresbot.domain.PositionSnapshot;
import com.example.futuresbot.marketdata.Candle;
import com.example.futuresbot.marketdata.CandleInterval;
import com.example.futuresbot.strategy.SignalType;
import com.example.futuresbot.strategy.StrategyContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PositionExitManagerTest {

    private static final String SYMBOL = "BTCUSDT";

    private final PositionExitManager manager = new PositionExitManager();

    @Test
    void returnsNoChangeBeforeBreakEvenThresholdForLong() {
        PositionManagementDecision decision = manager.evaluate(
                new PositionKey(SYMBOL, PositionSide.LONG),
                position(SYMBOL, PositionSide.LONG, 1.0, 100.0),
                longPlan(100.0, 95.0, 110.0),
                protection(95.0, 110.0, "STOP_1", "TP_1"),
                context(SYMBOL, bullish4hBars(), long15mBars(104.0)),
                enabledExitManagement());

        assertFalse(decision.updateRequired());
        assertEquals("No protection change", decision.reason());
    }

    @Test
    void movesLongStopToBreakevenAtOneR() {
        PositionManagementDecision decision = manager.evaluate(
                new PositionKey(SYMBOL, PositionSide.LONG),
                position(SYMBOL, PositionSide.LONG, 1.0, 100.0),
                longPlan(100.0, 95.0, 110.0),
                protection(95.0, 110.0, "STOP_1", "TP_1"),
                context(SYMBOL, bullish4hBars(), long15mBars(105.0)),
                enabledExitManagement());

        assertTrue(decision.updateRequired());
        assertEquals(0, decision.newStopTriggerPrice().compareTo(BigDecimal.valueOf(100.0)));
        assertEquals(0, decision.newTakeProfitTriggerPrice().compareTo(BigDecimal.valueOf(110.0)));
        assertTrue(decision.reason().contains("breakeven"));
    }

    @Test
    void trailsLongStopAfterActivationWithoutExtendingTakeProfitWhenTpIsFar() {
        PositionManagementDecision decision = manager.evaluate(
                new PositionKey(SYMBOL, PositionSide.LONG),
                position(SYMBOL, PositionSide.LONG, 1.0, 100.0),
                longPlan(100.0, 95.0, 110.0),
                protection(100.0, 120.0, "STOP_1", "TP_1"),
                context(SYMBOL, bullish4hBars(), long15mBars(109.0)),
                enabledExitManagement());

        assertTrue(decision.updateRequired());
        assertTrue(decision.newStopTriggerPrice().compareTo(BigDecimal.valueOf(100.0)) > 0);
        assertEquals(0, decision.newTakeProfitTriggerPrice().compareTo(BigDecimal.valueOf(120.0)));
        assertTrue(decision.reason().contains("trail-stop"));
        assertFalse(decision.reason().contains("extend-tp"));
    }

    @Test
    void extendsLongTakeProfitWhenNearTpAndHigherTimeframeTrendStillFavorable() {
        PositionManagementDecision decision = manager.evaluate(
                new PositionKey(SYMBOL, PositionSide.LONG),
                position(SYMBOL, PositionSide.LONG, 1.0, 100.0),
                longPlan(100.0, 95.0, 110.0),
                protection(100.0, 110.0, "STOP_1", "TP_1"),
                context(SYMBOL, bullish4hBars(), long15mBars(109.0)),
                enabledExitManagement());

        assertTrue(decision.updateRequired());
        assertTrue(decision.newStopTriggerPrice().compareTo(BigDecimal.valueOf(100.0)) >= 0);
        assertTrue(decision.newTakeProfitTriggerPrice().compareTo(BigDecimal.valueOf(110.0)) > 0);
        assertTrue(decision.reason().contains("extend-tp"));
    }

    @Test
    void movesShortStopToBreakevenAtOneR() {
        PositionManagementDecision decision = manager.evaluate(
                new PositionKey(SYMBOL, PositionSide.SHORT),
                position(SYMBOL, PositionSide.SHORT, 1.0, 200.0),
                shortPlan(200.0, 205.0, 190.0),
                protection(205.0, 190.0, "STOP_1", "TP_1"),
                context(SYMBOL, bearish4hBars(), short15mBars(195.0)),
                enabledExitManagement());

        assertTrue(decision.updateRequired());
        assertEquals(0, decision.newStopTriggerPrice().compareTo(BigDecimal.valueOf(200.0)));
        assertEquals(0, decision.newTakeProfitTriggerPrice().compareTo(BigDecimal.valueOf(190.0)));
        assertTrue(decision.reason().contains("breakeven"));
    }

    @Test
    void trailsShortStopAndExtendsTakeProfitWhenTrendStillFavorable() {
        PositionManagementDecision decision = manager.evaluate(
                new PositionKey(SYMBOL, PositionSide.SHORT),
                position(SYMBOL, PositionSide.SHORT, 1.0, 200.0),
                shortPlan(200.0, 205.0, 190.0),
                protection(200.0, 190.0, "STOP_1", "TP_1"),
                context(SYMBOL, bearish4hBars(), short15mBars(191.0)),
                enabledExitManagement());

        assertTrue(decision.updateRequired());
        assertTrue(decision.newStopTriggerPrice().compareTo(BigDecimal.valueOf(200.0)) < 0);
        assertTrue(decision.newTakeProfitTriggerPrice().compareTo(BigDecimal.valueOf(190.0)) < 0);
        assertTrue(decision.reason().contains("trail-stop"));
        assertTrue(decision.reason().contains("extend-tp"));
    }

    @Test
    void returnsNoChangeWhenExitManagementIsDisabled() {
        PositionManagementDecision decision = manager.evaluate(
                new PositionKey(SYMBOL, PositionSide.LONG),
                position(SYMBOL, PositionSide.LONG, 1.0, 100.0),
                longPlan(100.0, 95.0, 110.0),
                protection(95.0, 110.0, "STOP_1", "TP_1"),
                context(SYMBOL, bullish4hBars(), long15mBars(109.0)),
                disabledExitManagement());

        assertFalse(decision.updateRequired());
        assertEquals("Dynamic exits disabled", decision.reason());
    }

    private static AppConfig.ExitManagementConfig enabledExitManagement() {
        return new AppConfig.ExitManagementConfig(
                true,
                1.0,
                1.5,
                3,
                0.25,
                1.5,
                0.5,
                4.0
        );
    }

    private static AppConfig.ExitManagementConfig disabledExitManagement() {
        return new AppConfig.ExitManagementConfig(
                false,
                1.0,
                1.5,
                3,
                0.25,
                1.5,
                0.5,
                4.0
        );
    }

    private static ActiveProtectionState protection(
            double stop,
            double tp,
            String stopClientAlgoId,
            String takeProfitClientAlgoId) {

        return new ActiveProtectionState(
                BigDecimal.valueOf(stop),
                BigDecimal.valueOf(tp),
                stopClientAlgoId,
                takeProfitClientAlgoId,
                Instant.now());
    }

    private static PositionSnapshot position(
            String symbol,
            PositionSide side,
            double quantity,
            double entryPrice) {

        return new PositionSnapshot(
                symbol,
                side,
                BigDecimal.valueOf(quantity),
                BigDecimal.valueOf(entryPrice),
                BigDecimal.valueOf(entryPrice),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true,
                true,
                Instant.now());
    }

    private static OrderPlan longPlan(double entry, double stop, double tp) {
        return OrderPlan.accepted(
                SYMBOL,
                SignalType.LONG_ENTRY,
                BigDecimal.valueOf(entry),
                BigDecimal.valueOf(stop),
                BigDecimal.valueOf(tp),
                BigDecimal.ONE,
                BigDecimal.valueOf(entry),
                BigDecimal.valueOf(200.0),
                BigDecimal.valueOf(2.0),
                BigDecimal.valueOf(2.0));
    }

    private static OrderPlan shortPlan(double entry, double stop, double tp) {
        return OrderPlan.accepted(
                SYMBOL,
                SignalType.SHORT_ENTRY,
                BigDecimal.valueOf(entry),
                BigDecimal.valueOf(stop),
                BigDecimal.valueOf(tp),
                BigDecimal.ONE,
                BigDecimal.valueOf(entry),
                BigDecimal.valueOf(200.0),
                BigDecimal.valueOf(2.0),
                BigDecimal.valueOf(2.0));
    }

    private static StrategyContext context(
            String symbol,
            List<Candle> bars4h,
            List<Candle> bars15m) {

        Map<CandleInterval, List<Candle>> values = new EnumMap<>(CandleInterval.class);
        values.put(CandleInterval.HOUR_4, bars4h);
        values.put(CandleInterval.HOUR_1, List.of());
        values.put(CandleInterval.MINUTES_15, bars15m);
        return new StrategyContext(symbol, values);
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

    private static List<Candle> long15mBars(double latestClose) {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            closes.add(100.0 + (i * 0.4));
        }
        closes.add(latestClose);
        return candles(SYMBOL, CandleInterval.MINUTES_15, closes, 100.0);
    }

    private static List<Candle> short15mBars(double latestClose) {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            closes.add(200.0 - (i * 0.4));
        }
        closes.add(latestClose);
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