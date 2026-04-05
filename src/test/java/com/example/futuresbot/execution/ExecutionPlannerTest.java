package com.example.futuresbot.execution;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.marketdata.Candle;
import com.example.futuresbot.marketdata.CandleInterval;
import com.example.futuresbot.reconcile.AdoptionMode;
import com.example.futuresbot.strategy.SignalType;
import com.example.futuresbot.strategy.StrategyContext;
import com.example.futuresbot.strategy.TradeSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionPlannerTest {

    @Test
    void caps_quantity_by_available_margin_and_still_accepts_trade() {
        ExecutionPlanner planner = new ExecutionPlanner();

        TradeSignal signal = new TradeSignal(
                "BTCUSDT",
                SignalType.SHORT_ENTRY,
                BigDecimal.valueOf(100.0),
                Instant.parse("2026-04-05T14:15:00Z"),
                "test");

        StrategyContext context = context("BTCUSDT", fifteenMinuteBars("BTCUSDT", 20, 100.0));

        AccountEquitySnapshot equity = new AccountEquitySnapshot(
                BigDecimal.valueOf(5000.0),
                BigDecimal.valueOf(100.0),
                BigDecimal.ZERO);

        OrderPlan plan = planner.plan(signal, context, equity, tradingConfig());

        assertTrue(plan.accepted(), plan.rejectionReason());
        assertEquals(new BigDecimal("1.90000000"), plan.quantity());
        assertEquals(new BigDecimal("190.00000000"), plan.notionalUsd());
        assertEquals(new BigDecimal("5000.00000000"), plan.sizingEquityUsd());
    }

    @Test
    void rejects_when_margin_affordable_size_falls_below_minimum_notional() {
        ExecutionPlanner planner = new ExecutionPlanner();

        TradeSignal signal = new TradeSignal(
                "BTCUSDT",
                SignalType.SHORT_ENTRY,
                BigDecimal.valueOf(100.0),
                Instant.parse("2026-04-05T14:15:00Z"),
                "test");

        StrategyContext context = context("BTCUSDT", fifteenMinuteBars("BTCUSDT", 20, 100.0));

        AccountEquitySnapshot equity = new AccountEquitySnapshot(
                BigDecimal.valueOf(5000.0),
                BigDecimal.valueOf(1.0),
                BigDecimal.ZERO);

        OrderPlan plan = planner.plan(signal, context, equity, tradingConfig());

        assertFalse(plan.accepted());
        assertNotNull(plan.rejectionReason());
        assertTrue(plan.rejectionReason().contains("Trade notional"));
        assertTrue(plan.rejectionReason().contains("below minimum"));
    }

    private static AppConfig.TradingConfig tradingConfig() {
        return new AppConfig.TradingConfig(
                List.of("BTCUSDT"),
                false,
                AdoptionMode.ADOPT_AND_CONTINUE,
                2,
                0.5,
                2.0,
                1,
                200.0,
                RiskCapitalMode.LIVE_EQUITY,
                5.0,
                1.5,
                2.0,
                4.0,
                120,
                OppositeSignalPolicy.IGNORE,
                3,
                90,
                "var/trade-journal.csv",
                new AppConfig.ExitManagementConfig(
                        false,
                        1.0,
                        1.5,
                        3,
                        0.25,
                        1.5,
                        0.5,
                        4.0));
    }

    private static StrategyContext context(String symbol, List<Candle> bars15m) {
        Map<CandleInterval, List<Candle>> values = new EnumMap<>(CandleInterval.class);
        values.put(CandleInterval.HOUR_4, List.of());
        values.put(CandleInterval.HOUR_1, List.of());
        values.put(CandleInterval.MINUTES_15, bars15m);
        return new StrategyContext(symbol, values);
    }

    private static List<Candle> fifteenMinuteBars(String symbol, int count, double startClose) {
        Instant start = Instant.parse("2026-04-05T00:00:00Z");
        java.util.ArrayList<Candle> bars = new java.util.ArrayList<>();

        for (int i = 0; i < count; i++) {
            double close = startClose + (i * 0.10);
            double open = close - 0.20;
            double high = close + 1.00;
            double low = close - 1.00;

            Instant openTime = start.plusSeconds(i * 15L * 60L);
            Instant closeTime = openTime.plusSeconds(15L * 60L);

            bars.add(new Candle(
                    symbol,
                    CandleInterval.MINUTES_15,
                    openTime,
                    closeTime,
                    BigDecimal.valueOf(open),
                    BigDecimal.valueOf(high),
                    BigDecimal.valueOf(low),
                    BigDecimal.valueOf(close),
                    BigDecimal.valueOf(100.0),
                    true));
        }

        return bars;
    }
}