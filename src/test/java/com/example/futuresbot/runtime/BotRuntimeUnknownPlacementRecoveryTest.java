package com.example.futuresbot.runtime;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.domain.PositionSide;
import com.example.futuresbot.domain.PositionSnapshot;
import com.example.futuresbot.exchange.ExchangeGateway;
import com.example.futuresbot.exchange.ExchangeSnapshot;
import com.example.futuresbot.exchange.IncomeRecord;
import com.example.futuresbot.exchange.SymbolRules;
import com.example.futuresbot.exchange.UserStreamEvents;
import com.example.futuresbot.execution.AccountEquitySnapshot;
import com.example.futuresbot.execution.OppositeSignalPolicy;
import com.example.futuresbot.execution.OrderPlan;
import com.example.futuresbot.execution.RiskCapitalMode;
import com.example.futuresbot.marketdata.Candle;
import com.example.futuresbot.marketdata.CandleInterval;
import com.example.futuresbot.marketdata.MarketDataService;
import com.example.futuresbot.reconcile.AdoptionMode;
import com.example.futuresbot.strategy.SignalType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BotRuntimeUnknownPlacementRecoveryTest {

    @Test
    void repairsProtectionWhenUnknownPlacementBecomesOpenPosition() {
        FakeExchangeGateway exchangeGateway = new FakeExchangeGateway();
        PositionKey key = new PositionKey("ETHUSDT", PositionSide.SHORT);

        exchangeGateway.enqueueSnapshot(emptySnapshot());
        exchangeGateway.enqueueSnapshot(snapshotWithOpenShortMissingProtection());

        BotRuntime runtime = new BotRuntime(config(false), exchangeGateway, new NoopMarketDataService());

        OrderPlan plan = acceptedShortPlan();

        boolean recovered = runtime.recoverUnknownPlacement(key, plan);

        assertTrue(recovered);
        assertTrue(runtime.getManagedPosition(key).isPresent());
        assertEquals(2, exchangeGateway.protectivePlacements.size());

        ProtectivePlacement stopPlacement = exchangeGateway.protectivePlacements.get(0);
        ProtectivePlacement tpPlacement = exchangeGateway.protectivePlacements.get(1);

        assertEquals("ETHUSDT", stopPlacement.symbol());
        assertFalse(stopPlacement.takeProfit());
        assertTrue(stopPlacement.clientAlgoId().startsWith("BOT_REPAIR_STOP_"));

        assertEquals("ETHUSDT", tpPlacement.symbol());
        assertTrue(tpPlacement.takeProfit());
        assertTrue(tpPlacement.clientAlgoId().startsWith("BOT_REPAIR_TP_"));
    }

    @Test
    void returnsFalseWhenUnknownPlacementCannotBeConfirmed() {
        FakeExchangeGateway exchangeGateway = new FakeExchangeGateway();
        PositionKey key = new PositionKey("ETHUSDT", PositionSide.SHORT);

        exchangeGateway.enqueueSnapshot(emptySnapshot());
        exchangeGateway.enqueueSnapshot(emptySnapshot());
        exchangeGateway.enqueueSnapshot(emptySnapshot());

        BotRuntime runtime = new BotRuntime(config(false), exchangeGateway, new NoopMarketDataService());

        boolean recovered = runtime.recoverUnknownPlacement(key, acceptedShortPlan());

        assertFalse(recovered);
        assertTrue(runtime.getManagedPosition(key).isEmpty());
        assertEquals(0, exchangeGateway.protectivePlacements.size());
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
                        List.of("ETHUSDT"),
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
                        List.of("ETHUSDT"),
                        "2025-01-01T00:00:00Z",
                        "2025-01-02T00:00:00Z",
                        200.0,
                        4.0,
                        "var/replay.csv"));
    }

    private static OrderPlan acceptedShortPlan() {
        return OrderPlan.accepted(
                "ETHUSDT",
                SignalType.SHORT_ENTRY,
                bd("2045.00"),
                bd("2063.34"),
                bd("2027.74"),
                bd("1.55000000"),
                bd("3169.75000000"),
                bd("4967.51349810"),
                bd("24.83756749"),
                bd("11.50000000"));
    }

    private static ExchangeSnapshot emptySnapshot() {
        return new ExchangeSnapshot(List.of(), List.of(), List.of());
    }

    private static ExchangeSnapshot snapshotWithOpenShortMissingProtection() {
        return new ExchangeSnapshot(
                List.of(new PositionSnapshot(
                        "ETHUSDT",
                        PositionSide.SHORT,
                        bd("1.550"),
                        bd("2045.00"),
                        bd("2044.80"),
                        BigDecimal.ZERO,
                        bd("3000.00"),
                        false,
                        false,
                        Instant.parse("2026-04-05T18:00:10Z"))),
                List.of(),
                List.of());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record ProtectivePlacement(
            String symbol,
            SignalType signalType,
            BigDecimal triggerPrice,
            boolean takeProfit,
            String clientAlgoId) {
    }

    private static final class FakeExchangeGateway implements ExchangeGateway {

        private final Deque<ExchangeSnapshot> snapshots = new ArrayDeque<>();
        private final List<ProtectivePlacement> protectivePlacements = new ArrayList<>();

        void enqueueSnapshot(ExchangeSnapshot snapshot) {
            this.snapshots.addLast(snapshot);
        }

        @Override
        public ExchangeSnapshot currentSnapshot() {
            return this.currentSnapshot("ETHUSDT");
        }

        @Override
        public ExchangeSnapshot currentSnapshot(String symbol) {
            if (this.snapshots.isEmpty()) {
                return emptySnapshot();
            }
            if (this.snapshots.size() == 1) {
                return this.snapshots.peekFirst();
            }
            return this.snapshots.removeFirst();
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

            this.protectivePlacements.add(
                    new ProtectivePlacement(symbol, signalType, triggerPrice, takeProfit, clientAlgoId));
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
    }
}