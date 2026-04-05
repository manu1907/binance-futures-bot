package com.example.futuresbot.runtime;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.domain.PositionSide;
import com.example.futuresbot.domain.PositionSnapshot;
import com.example.futuresbot.exchange.AlgoOrderSnapshot;
import com.example.futuresbot.exchange.ExchangeGateway;
import com.example.futuresbot.exchange.ExchangeSnapshot;
import com.example.futuresbot.exchange.IncomeRecord;
import com.example.futuresbot.exchange.SymbolRules;
import com.example.futuresbot.exchange.UserStreamEvents;
import com.example.futuresbot.execution.AccountEquitySnapshot;
import com.example.futuresbot.execution.OppositeSignalPolicy;
import com.example.futuresbot.execution.RiskCapitalMode;
import com.example.futuresbot.marketdata.Candle;
import com.example.futuresbot.marketdata.CandleInterval;
import com.example.futuresbot.marketdata.MarketDataService;
import com.example.futuresbot.reconcile.AdoptionMode;
import com.example.futuresbot.strategy.SignalType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotRuntimeStartupProtectionAuditTest {

    @Test
    void haltsTradingAtStartupWhenLiveLongIsMissingStop() {
        ExchangeSnapshot startup = snapshotWithPosition("BTCUSDT", PositionSide.LONG, false, true);
        BotRuntime runtime = new BotRuntime(
                config(false),
                new FakeExchangeGateway(startup, Map.of("BTCUSDT", startup)),
                new NoopMarketDataService());

        try {
            runtime.start();

            assertTrue(runtime.getTradingHaltReason().isPresent());
            assertTrue(runtime.getTradingHaltReason().get().contains("hasStop=false"));
            assertTrue(runtime.getTradingHaltReason().get().contains("hasTakeProfit=true"));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void haltsTradingAtStartupWhenLiveShortIsMissingTakeProfit() {
        ExchangeSnapshot startup = snapshotWithPosition("BTCUSDT", PositionSide.SHORT, true, false);
        BotRuntime runtime = new BotRuntime(
                config(false),
                new FakeExchangeGateway(startup, Map.of("BTCUSDT", startup)),
                new NoopMarketDataService());

        try {
            runtime.start();

            assertTrue(runtime.getTradingHaltReason().isPresent());
            assertTrue(runtime.getTradingHaltReason().get().contains("hasStop=true"));
            assertTrue(runtime.getTradingHaltReason().get().contains("hasTakeProfit=false"));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void doesNotHaltWhenLivePositionHasCompleteProtection() {
        ExchangeSnapshot startup = snapshotWithPosition("BTCUSDT", PositionSide.LONG, true, true);
        BotRuntime runtime = new BotRuntime(
                config(false),
                new FakeExchangeGateway(startup, Map.of("BTCUSDT", startup)),
                new NoopMarketDataService());

        try {
            runtime.start();

            assertTrue(runtime.getManagedPosition(new PositionKey("BTCUSDT", PositionSide.LONG)).isPresent());
            assertTrue(runtime.getTradingHaltReason().isEmpty());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void doesNotHaltForDanglingAlgoOrdersWithoutOpenPosition() {
        ExchangeSnapshot startup = new ExchangeSnapshot(
                List.of(),
                List.of(),
                List.of(new AlgoOrderSnapshot(
                        "BTCUSDT",
                        PositionSide.LONG,
                        "BOT_STOP_orphan",
                        "STOP",
                        "STOP_MARKET")));

        BotRuntime runtime = new BotRuntime(
                config(false),
                new FakeExchangeGateway(startup, Map.of("BTCUSDT", startup)),
                new NoopMarketDataService());

        try {
            runtime.start();

            assertTrue(runtime.getTradingHaltReason().isEmpty());
            assertFalse(runtime.getManagedPosition(new PositionKey("BTCUSDT", PositionSide.LONG)).isPresent());
        } finally {
            runtime.stop();
        }
    }

    private static AppConfig config(boolean dryRun) {
        return new AppConfig(
                new AppConfig.ExchangeConfig(
                        "https://test",
                        "wss://test",
                        "key",
                        "secret",
                        true,
                        true),
                new AppConfig.TradingConfig(
                        List.of("BTCUSDT"),
                        dryRun,
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
                                true,
                                1.0,
                                1.5,
                                3,
                                0.25,
                                1.5,
                                0.5,
                                4.0)),
                new AppConfig.ReplayConfig(
                        List.of("BTCUSDT"),
                        "2025-01-01T00:00:00Z",
                        "2025-01-02T00:00:00Z",
                        200.0,
                        4.0,
                        "var/replay-trades.csv"));
    }

    private static ExchangeSnapshot snapshotWithPosition(
            String symbol,
            PositionSide side,
            boolean hasProtectiveStop,
            boolean hasTakeProfit) {

        List<AlgoOrderSnapshot> algoOrders = new ArrayList<>();

        if (hasProtectiveStop) {
            algoOrders.add(new AlgoOrderSnapshot(
                    symbol,
                    side,
                    "BOT_STOP_test",
                    "STOP",
                    "STOP_MARKET"));
        }

        if (hasTakeProfit) {
            algoOrders.add(new AlgoOrderSnapshot(
                    symbol,
                    side,
                    "BOT_TP_test",
                    "TAKE_PROFIT",
                    "TAKE_PROFIT_MARKET"));
        }

        return new ExchangeSnapshot(
                List.of(new PositionSnapshot(
                        symbol,
                        side,
                        bd("1.000"),
                        bd("100.00"),
                        bd("101.00"),
                        BigDecimal.ZERO,
                        bd("100.00"),
                        hasProtectiveStop,
                        hasTakeProfit,
                        Instant.parse("2026-04-05T16:00:00Z"))),
                List.of(),
                algoOrders);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static final class FakeExchangeGateway implements ExchangeGateway {
        private final ExchangeSnapshot startupSnapshot;
        private final Map<String, ExchangeSnapshot> symbolSnapshots;

        private FakeExchangeGateway(
                ExchangeSnapshot startupSnapshot,
                Map<String, ExchangeSnapshot> symbolSnapshots) {
            this.startupSnapshot = startupSnapshot;
            this.symbolSnapshots = symbolSnapshots;
        }

        @Override
        public ExchangeSnapshot currentSnapshot() {
            return this.startupSnapshot;
        }

        @Override
        public ExchangeSnapshot currentSnapshot(String symbol) {
            return this.symbolSnapshots.getOrDefault(symbol, new ExchangeSnapshot(List.of(), List.of(), List.of()));
        }

        @Override
        public boolean isHedgeModeEnabled() {
            return true;
        }

        @Override
        public AccountEquitySnapshot accountEquity() {
            return new AccountEquitySnapshot(bd("5000.00"), bd("5000.00"), BigDecimal.ZERO);
        }

        @Override
        public SymbolRules symbolRules(String symbol) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String placeEntryMarketOrder(String symbol, SignalType signalType, BigDecimal quantity, String clientOrderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String placeProtectiveAlgoOrder(
                String symbol,
                SignalType signalType,
                BigDecimal triggerPrice,
                boolean takeProfit,
                String clientAlgoId) {
            return clientAlgoId;
        }

        @Override
        public void cancelAllOpenOrders(String symbol) {
            // no-op
        }

        @Override
        public void cancelAllOpenAlgoOrders(String symbol) {
            // no-op
        }

        @Override
        public String closePositionMarket(PositionKey key, BigDecimal quantity, String clientOrderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IncomeRecord> incomeHistory(String symbol, Instant startInclusive, Instant endInclusive) {
            return List.of();
        }

        @Override
        public void connectUserStream(Consumer<UserStreamEvents.UserStreamEvent> consumer) {
            // no-op
        }

        @Override
        public void setLeverage(String symbol, int leverage) {
            // no-op
        }

        @Override
        public void cancelAlgoOrder(String clientAlgoId) {
            // no-op
        }
    }

    private static final class NoopMarketDataService implements MarketDataService {
        @Override
        public List<Candle> loadHistoricalKlines(String symbol, CandleInterval interval, int limit) {
            return List.of();
        }

        @Override
        public List<Candle> loadHistoricalKlines(
                String symbol,
                CandleInterval interval,
                Instant startInclusive,
                Instant endExclusive) {
            return List.of();
        }

        @Override
        public void connectKlineStreams(
                List<String> symbols,
                List<CandleInterval> intervals,
                Consumer<Candle> consumer) {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }
    }
}