package com.example.futuresbot.runtime;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.domain.PositionSide;
import com.example.futuresbot.domain.PositionSnapshot;
import com.example.futuresbot.exchange.AlgoOrderSnapshot;
import com.example.futuresbot.exchange.ExchangeGateway;
import com.example.futuresbot.exchange.ExchangeSnapshot;
import com.example.futuresbot.exchange.IncomeRecord;
import com.example.futuresbot.exchange.OpenOrderSnapshot;
import com.example.futuresbot.exchange.SymbolRules;
import com.example.futuresbot.exchange.UserStreamEvents;
import com.example.futuresbot.execution.AccountEquitySnapshot;
import com.example.futuresbot.execution.ActiveProtectionState;
import com.example.futuresbot.execution.OppositeSignalPolicy;
import com.example.futuresbot.execution.OrderPlan;
import com.example.futuresbot.execution.RiskCapitalMode;
import com.example.futuresbot.marketdata.Candle;
import com.example.futuresbot.marketdata.CandleInterval;
import com.example.futuresbot.marketdata.MarketDataService;
import com.example.futuresbot.reconcile.AdoptionMode;
import com.example.futuresbot.strategy.SignalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class BotRuntimeExitManagementTest {

    private static final String BTC = "BTCUSDT";

    @TempDir
    Path tempDir;

    @Test
    void updatesLongProtectionOnFavorableClosedBar() throws Exception {
        FakeExchangeGateway exchangeGateway = new FakeExchangeGateway();
        FakeMarketDataService marketDataService = new FakeMarketDataService();

        PositionKey key = new PositionKey(BTC, PositionSide.LONG);

        exchangeGateway.enqueueEquity(equity(5000));
        exchangeGateway.setSymbolSnapshot(BTC, snapshotOf(
                position(BTC, PositionSide.LONG, 1.0, 100.0, true, true)
        ));
        marketDataService.seedLongManagementScenario(BTC);

        BotRuntime runtime = new BotRuntime(
                config(false, true),
                exchangeGateway,
                marketDataService);

        try {
            runtime.start();

            runtime.onAccountPositionUpdate(new UserStreamEvents.AccountPositionUpdateEvent(
                    BTC,
                    PositionSide.LONG,
                    BigDecimal.ONE,
                    BigDecimal.valueOf(100.0),
                    Instant.now()));

            mapField(runtime, "latestAcceptedPlanByKey").put(key, longPlan());
            mapField(runtime, "activeProtectionByKey").put(key, protection(95.0, 110.0, "OLD_STOP", "OLD_TP"));

            marketDataService.emit(longManagementCandle(BTC));

            assertEquals(0, exchangeGateway.placeEntryMarketOrderCalls);
            assertEquals(2, exchangeGateway.cancelAlgoOrderCalls);
            assertEquals(2, exchangeGateway.placeProtectiveAlgoOrderCalls);
            assertTrue(exchangeGateway.canceledAlgoIds.contains("OLD_STOP"));
            assertTrue(exchangeGateway.canceledAlgoIds.contains("OLD_TP"));

            ActiveProtectionState updated = (ActiveProtectionState) mapField(runtime, "activeProtectionByKey").get(key);
            assertNotNull(updated);
            assertTrue(updated.stopTriggerPrice().compareTo(BigDecimal.valueOf(95.0)) > 0);
            assertTrue(updated.takeProfitTriggerPrice().compareTo(BigDecimal.valueOf(110.0)) > 0);
            assertNotEquals("OLD_STOP", updated.stopClientAlgoId());
            assertNotEquals("OLD_TP", updated.takeProfitClientAlgoId());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void updatesShortProtectionOnFavorableClosedBar() throws Exception {
        FakeExchangeGateway exchangeGateway = new FakeExchangeGateway();
        FakeMarketDataService marketDataService = new FakeMarketDataService();

        PositionKey key = new PositionKey(BTC, PositionSide.SHORT);

        exchangeGateway.enqueueEquity(equity(5000));
        exchangeGateway.setSymbolSnapshot(BTC, snapshotOf(
                position(BTC, PositionSide.SHORT, 1.0, 200.0, true, true)
        ));
        marketDataService.seedShortManagementScenario(BTC);

        BotRuntime runtime = new BotRuntime(
                config(false, true),
                exchangeGateway,
                marketDataService);

        try {
            runtime.start();

            runtime.onAccountPositionUpdate(new UserStreamEvents.AccountPositionUpdateEvent(
                    BTC,
                    PositionSide.SHORT,
                    BigDecimal.ONE,
                    BigDecimal.valueOf(200.0),
                    Instant.now()));

            mapField(runtime, "latestAcceptedPlanByKey").put(key, shortPlan());
            mapField(runtime, "activeProtectionByKey").put(key, protection(205.0, 190.0, "OLD_STOP", "OLD_TP"));

            marketDataService.emit(shortManagementCandle(BTC));

            assertEquals(0, exchangeGateway.placeEntryMarketOrderCalls);
            assertEquals(2, exchangeGateway.cancelAlgoOrderCalls);
            assertEquals(2, exchangeGateway.placeProtectiveAlgoOrderCalls);

            ActiveProtectionState updated = (ActiveProtectionState) mapField(runtime, "activeProtectionByKey").get(key);
            assertNotNull(updated);
            assertTrue(updated.stopTriggerPrice().compareTo(BigDecimal.valueOf(205.0)) < 0);
            assertTrue(updated.takeProfitTriggerPrice().compareTo(BigDecimal.valueOf(190.0)) < 0);
        } finally {
            runtime.stop();
        }
    }

    @Test
    void doesNotUpdateProtectionWhenDryRunIsTrue() throws Exception {
        FakeExchangeGateway exchangeGateway = new FakeExchangeGateway();
        FakeMarketDataService marketDataService = new FakeMarketDataService();

        PositionKey key = new PositionKey(BTC, PositionSide.LONG);

        exchangeGateway.enqueueEquity(equity(5000));
        exchangeGateway.setSymbolSnapshot(BTC, snapshotOf(
                position(BTC, PositionSide.LONG, 1.0, 100.0, true, true)
        ));
        marketDataService.seedLongManagementScenario(BTC);

        BotRuntime runtime = new BotRuntime(
                config(true, true),
                exchangeGateway,
                marketDataService);

        try {
            runtime.start();

            runtime.onAccountPositionUpdate(new UserStreamEvents.AccountPositionUpdateEvent(
                    BTC,
                    PositionSide.LONG,
                    BigDecimal.ONE,
                    BigDecimal.valueOf(100.0),
                    Instant.now()));

            mapField(runtime, "latestAcceptedPlanByKey").put(key, longPlan());
            mapField(runtime, "activeProtectionByKey").put(key, protection(95.0, 110.0, "OLD_STOP", "OLD_TP"));

            marketDataService.emit(longManagementCandle(BTC));

            assertEquals(0, exchangeGateway.cancelAlgoOrderCalls);
            assertEquals(0, exchangeGateway.placeProtectiveAlgoOrderCalls);

            ActiveProtectionState unchanged = (ActiveProtectionState) mapField(runtime, "activeProtectionByKey").get(key);
            assertEquals("OLD_STOP", unchanged.stopClientAlgoId());
            assertEquals("OLD_TP", unchanged.takeProfitClientAlgoId());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void doesNotUpdateProtectionWithoutCachedPlan() throws Exception {
        FakeExchangeGateway exchangeGateway = new FakeExchangeGateway();
        FakeMarketDataService marketDataService = new FakeMarketDataService();

        PositionKey key = new PositionKey(BTC, PositionSide.LONG);

        exchangeGateway.enqueueEquity(equity(5000));
        exchangeGateway.setSymbolSnapshot(BTC, snapshotOf(
                position(BTC, PositionSide.LONG, 1.0, 100.0, true, true)
        ));
        marketDataService.seedLongManagementScenario(BTC);

        BotRuntime runtime = new BotRuntime(
                config(false, true),
                exchangeGateway,
                marketDataService);

        try {
            runtime.start();

            runtime.onAccountPositionUpdate(new UserStreamEvents.AccountPositionUpdateEvent(
                    BTC,
                    PositionSide.LONG,
                    BigDecimal.ONE,
                    BigDecimal.valueOf(100.0),
                    Instant.now()));

            mapField(runtime, "activeProtectionByKey").put(key, protection(95.0, 110.0, "OLD_STOP", "OLD_TP"));

            marketDataService.emit(longManagementCandle(BTC));

            assertEquals(0, exchangeGateway.cancelAlgoOrderCalls);
            assertEquals(0, exchangeGateway.placeProtectiveAlgoOrderCalls);
        } finally {
            runtime.stop();
        }
    }

    @Test
    void doesNotUpdateProtectionWithoutTrackedProtectionState() throws Exception {
        FakeExchangeGateway exchangeGateway = new FakeExchangeGateway();
        FakeMarketDataService marketDataService = new FakeMarketDataService();

        PositionKey key = new PositionKey(BTC, PositionSide.LONG);

        exchangeGateway.enqueueEquity(equity(5000));
        exchangeGateway.setSymbolSnapshot(BTC, snapshotOf(
                position(BTC, PositionSide.LONG, 1.0, 100.0, true, true)
        ));
        marketDataService.seedLongManagementScenario(BTC);

        BotRuntime runtime = new BotRuntime(
                config(false, true),
                exchangeGateway,
                marketDataService);

        try {
            runtime.start();

            runtime.onAccountPositionUpdate(new UserStreamEvents.AccountPositionUpdateEvent(
                    BTC,
                    PositionSide.LONG,
                    BigDecimal.ONE,
                    BigDecimal.valueOf(100.0),
                    Instant.now()));

            mapField(runtime, "latestAcceptedPlanByKey").put(key, longPlan());

            marketDataService.emit(longManagementCandle(BTC));

            assertEquals(0, exchangeGateway.cancelAlgoOrderCalls);
            assertEquals(0, exchangeGateway.placeProtectiveAlgoOrderCalls);
            assertFalse(mapField(runtime, "activeProtectionByKey").containsKey(key));
        } finally {
            runtime.stop();
        }
    }

    private AppConfig config(boolean dryRun, boolean exitManagementEnabled) {
        return new AppConfig(
                new AppConfig.ExchangeConfig(
                        "https://fapi.binance.com",
                        "wss://fstream.binance.com",
                        "key",
                        "secret",
                        false,
                        true),
                new AppConfig.TradingConfig(
                        List.of(BTC),
                        dryRun,
                        AdoptionMode.ADOPT_AND_CONTINUE,
                        2,
                        1.0,
                        10.0,
                        1,
                        200.0,
                        RiskCapitalMode.CAPPED_EQUITY,
                        5.0,
                        1.5,
                        2.0,
                        4.0,
                        0,
                        OppositeSignalPolicy.IGNORE,
                        3,
                        300,
                        tempDir.resolve("trade-journal.csv").toString(),
                        new AppConfig.ExitManagementConfig(
                                exitManagementEnabled,
                                1.0,
                                1.5,
                                3,
                                0.25,
                                1.5,
                                0.5,
                                4.0)),
                new AppConfig.ReplayConfig(
                        List.of(BTC),
                        "2025-01-01T00:00:00Z",
                        "2025-01-31T23:59:59Z",
                        200.0,
                        4.0,
                        tempDir.resolve("replay-trades.csv").toString()));
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> mapField(BotRuntime runtime, String fieldName) throws Exception {
        Field field = BotRuntime.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(runtime);
    }

    private static AccountEquitySnapshot equity(double value) {
        BigDecimal usd = BigDecimal.valueOf(value);
        return new AccountEquitySnapshot(usd, usd, BigDecimal.ZERO);
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

    private static OrderPlan longPlan() {
        return OrderPlan.accepted(
                BTC,
                SignalType.LONG_ENTRY,
                BigDecimal.valueOf(100.0),
                BigDecimal.valueOf(95.0),
                BigDecimal.valueOf(110.0),
                BigDecimal.ONE,
                BigDecimal.valueOf(100.0),
                BigDecimal.valueOf(200.0),
                BigDecimal.valueOf(2.0),
                BigDecimal.valueOf(2.0));
    }

    private static OrderPlan shortPlan() {
        return OrderPlan.accepted(
                BTC,
                SignalType.SHORT_ENTRY,
                BigDecimal.valueOf(200.0),
                BigDecimal.valueOf(205.0),
                BigDecimal.valueOf(190.0),
                BigDecimal.ONE,
                BigDecimal.valueOf(200.0),
                BigDecimal.valueOf(200.0),
                BigDecimal.valueOf(2.0),
                BigDecimal.valueOf(2.0));
    }

    private static ExchangeSnapshot snapshotOf(PositionSnapshot... positions) {
        return new ExchangeSnapshot(List.of(positions), List.of(), List.of());
    }

    private static PositionSnapshot position(
            String symbol,
            PositionSide side,
            double quantity,
            double entryPrice,
            boolean hasProtectiveStop,
            boolean hasTakeProfit) {

        return new PositionSnapshot(
                symbol,
                side,
                BigDecimal.valueOf(quantity),
                BigDecimal.valueOf(entryPrice),
                BigDecimal.valueOf(entryPrice),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                hasProtectiveStop,
                hasTakeProfit,
                Instant.now());
    }

    private static Candle longManagementCandle(String symbol) {
        Instant openTime = Instant.parse("2026-01-01T05:00:00Z");
        Instant closeTime = openTime.plusSeconds((15 * 60L) - 1);
        return new Candle(
                symbol,
                CandleInterval.MINUTES_15,
                openTime,
                closeTime,
                BigDecimal.valueOf(108.0),
                BigDecimal.valueOf(109.5),
                BigDecimal.valueOf(107.8),
                BigDecimal.valueOf(109.0),
                BigDecimal.valueOf(100.0),
                true);
    }

    private static Candle shortManagementCandle(String symbol) {
        Instant openTime = Instant.parse("2026-01-01T05:00:00Z");
        Instant closeTime = openTime.plusSeconds((15 * 60L) - 1);
        return new Candle(
                symbol,
                CandleInterval.MINUTES_15,
                openTime,
                closeTime,
                BigDecimal.valueOf(192.0),
                BigDecimal.valueOf(192.5),
                BigDecimal.valueOf(190.8),
                BigDecimal.valueOf(191.0),
                BigDecimal.valueOf(100.0),
                true);
    }

    private static List<Candle> bullish4hBars(String symbol) {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            closes.add(100.0 + i);
        }
        double value = closes.getLast();
        for (int i = 0; i < 10; i++) {
            value += 3.0;
            closes.add(value);
        }
        return candles(symbol, CandleInterval.HOUR_4, closes, 100.0);
    }

    private static List<Candle> bearish4hBars(String symbol) {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            closes.add(300.0 - i);
        }
        double value = closes.getLast();
        for (int i = 0; i < 10; i++) {
            value -= 3.0;
            closes.add(value);
        }
        return candles(symbol, CandleInterval.HOUR_4, closes, 100.0);
    }

    private static List<Candle> long15mSeedBars(String symbol) {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            closes.add(100.0 + (i * 0.4));
        }
        closes.add(108.0);
        return candles(symbol, CandleInterval.MINUTES_15, closes, 100.0);
    }

    private static List<Candle> short15mSeedBars(String symbol) {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            closes.add(200.0 - (i * 0.4));
        }
        closes.add(192.0);
        return candles(symbol, CandleInterval.MINUTES_15, closes, 100.0);
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

    private static final class FakeExchangeGateway implements ExchangeGateway {
        private final Map<String, ExchangeSnapshot> symbolSnapshots = new HashMap<>();
        private final ArrayDeque<AccountEquitySnapshot> queuedEquities = new ArrayDeque<>();
        private final Map<String, Integer> leverageBySymbol = new HashMap<>();
        private AccountEquitySnapshot lastEquity = equity(5000);

        private int placeEntryMarketOrderCalls;
        private int placeProtectiveAlgoOrderCalls;
        private int cancelAlgoOrderCalls;
        private int setLeverageCalls = 0;

        private final List<String> canceledAlgoIds = new ArrayList<>();

        @SuppressWarnings("unused")
        private Consumer<UserStreamEvents.UserStreamEvent> userStreamConsumer;

        void setSymbolSnapshot(String symbol, ExchangeSnapshot snapshot) {
            symbolSnapshots.put(symbol, snapshot);
        }

        void enqueueEquity(AccountEquitySnapshot... snapshots) {
            queuedEquities.addAll(List.of(snapshots));
            if (snapshots.length > 0) {
                lastEquity = snapshots[snapshots.length - 1];
            }
        }

        @Override
        public ExchangeSnapshot currentSnapshot() {
            List<PositionSnapshot> positions = new ArrayList<>();
            List<OpenOrderSnapshot> openOrders = new ArrayList<>();
            List<AlgoOrderSnapshot> openAlgoOrders = new ArrayList<>();

            for (ExchangeSnapshot snapshot : symbolSnapshots.values()) {
                positions.addAll(snapshot.positions());
                openOrders.addAll(snapshot.openOrders());
                openAlgoOrders.addAll(snapshot.openAlgoOrders());
            }
            return new ExchangeSnapshot(positions, openOrders, openAlgoOrders);
        }

        @Override
        public ExchangeSnapshot currentSnapshot(String symbol) {
            return symbolSnapshots.getOrDefault(symbol, new ExchangeSnapshot(List.of(), List.of(), List.of()));
        }

        @Override
        public boolean isHedgeModeEnabled() {
            return true;
        }

        @Override
        public AccountEquitySnapshot accountEquity() {
            AccountEquitySnapshot next = queuedEquities.pollFirst();
            if (next != null) {
                lastEquity = next;
            }
            return lastEquity;
        }

        @Override
        public SymbolRules symbolRules(String symbol) {
            return new SymbolRules(
                    symbol,
                    BigDecimal.valueOf(0.1),
                    BigDecimal.valueOf(1_000_000),
                    BigDecimal.valueOf(0.1),
                    BigDecimal.valueOf(0.001),
                    BigDecimal.valueOf(10_000),
                    BigDecimal.valueOf(0.001),
                    BigDecimal.valueOf(0.001),
                    BigDecimal.valueOf(10_000),
                    BigDecimal.valueOf(0.001),
                    BigDecimal.valueOf(5.0),
                    BigDecimal.ZERO);
        }

        @Override
        public String placeEntryMarketOrder(
                String symbol,
                SignalType signalType,
                BigDecimal quantity,
                String clientOrderId) {

            placeEntryMarketOrderCalls++;
            return clientOrderId;
        }

        @Override
        public String placeProtectiveAlgoOrder(
                String symbol,
                SignalType signalType,
                BigDecimal triggerPrice,
                boolean takeProfit,
                String clientAlgoId) {

            placeProtectiveAlgoOrderCalls++;
            return clientAlgoId;
        }

        @Override
        public void cancelAlgoOrder(String clientAlgoId) {
            cancelAlgoOrderCalls++;
            canceledAlgoIds.add(clientAlgoId);
        }

        @Override
        public void cancelAllOpenOrders(String symbol) {
            fail("cancelAllOpenOrders not expected in exit-management tests");
        }

        @Override
        public void cancelAllOpenAlgoOrders(String symbol) {
            fail("cancelAllOpenAlgoOrders not expected in exit-management tests");
        }

        @Override
        public String closePositionMarket(PositionKey key, BigDecimal quantity, String clientOrderId) {
            fail("closePositionMarket not expected in exit-management tests");
            return clientOrderId;
        }

        @Override
        public List<IncomeRecord> incomeHistory(String symbol, Instant startInclusive, Instant endInclusive) {
            return List.of();
        }

        @Override
        public void connectUserStream(Consumer<UserStreamEvents.UserStreamEvent> consumer) {
            this.userStreamConsumer = consumer;
        }

        @Override
        public void setLeverage(String symbol, int leverage) {
            setLeverageCalls++;
            leverageBySymbol.put(symbol, leverage);
        }
    }

    private static final class FakeMarketDataService implements MarketDataService {
        private final Map<String, Map<CandleInterval, List<Candle>>> historical = new HashMap<>();
        private Consumer<Candle> candleConsumer;

        void seedLongManagementScenario(String symbol) {
            putHistory(symbol, CandleInterval.HOUR_4, bullish4hBars(symbol));
            putHistory(symbol, CandleInterval.HOUR_1, List.of());
            putHistory(symbol, CandleInterval.MINUTES_15, long15mSeedBars(symbol));
        }

        void seedShortManagementScenario(String symbol) {
            putHistory(symbol, CandleInterval.HOUR_4, bearish4hBars(symbol));
            putHistory(symbol, CandleInterval.HOUR_1, List.of());
            putHistory(symbol, CandleInterval.MINUTES_15, short15mSeedBars(symbol));
        }

        void putHistory(String symbol, CandleInterval interval, List<Candle> candles) {
            historical.computeIfAbsent(symbol, ignored -> new EnumMap<>(CandleInterval.class))
                    .put(interval, candles);
        }

        void emit(Candle candle) {
            assertNotNull(candleConsumer, "No candle consumer registered");
            candleConsumer.accept(candle);
        }

        @Override
        public List<Candle> loadHistoricalKlines(String symbol, CandleInterval interval, int limit) {
            return historical.getOrDefault(symbol, Map.of()).getOrDefault(interval, List.of());
        }

        @Override
        public List<Candle> loadHistoricalKlines(
                String symbol,
                CandleInterval interval,
                Instant startInclusive,
                Instant endInclusive) {

            return historical.getOrDefault(symbol, Map.of())
                    .getOrDefault(interval, List.of())
                    .stream()
                    .filter(candle -> startInclusive == null || !candle.openTime().isBefore(startInclusive))
                    .filter(candle -> endInclusive == null || !candle.closeTime().isAfter(endInclusive))
                    .toList();
        }

        @Override
        @SuppressWarnings("unchecked")
        public void connectKlineStreams(List symbols, List intervals, Consumer consumer) {
            this.candleConsumer = (Consumer<Candle>) consumer;
        }
    }
}