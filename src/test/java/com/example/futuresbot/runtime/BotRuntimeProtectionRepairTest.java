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
import com.example.futuresbot.execution.OppositeSignalPolicy;
import com.example.futuresbot.execution.RiskCapitalMode;
import com.example.futuresbot.marketdata.Candle;
import com.example.futuresbot.marketdata.CandleInterval;
import com.example.futuresbot.marketdata.MarketDataService;
import com.example.futuresbot.reconcile.AdoptionMode;
import com.example.futuresbot.strategy.SignalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

class BotRuntimeProtectionRepairTest {

    private static final String SYMBOL = "BTCUSDT";

    @TempDir
    Path tempDir;

    @Test
    void repairsMissingStopAndTakeProfitWhenCachedPlanMatchesLivePosition() {
        FakeExchangeGateway exchangeGateway = new FakeExchangeGateway();
        FakeMarketDataService marketDataService = new FakeMarketDataService();

        exchangeGateway.enqueueEquity(equity(5000));
        marketDataService.seedBullishScenario(SYMBOL);

        BotRuntime runtime = new BotRuntime(
                liveConfig(false),
                exchangeGateway,
                marketDataService);

        try {
            runtime.start();

            marketDataService.emit(bullishBreakoutTriggerCandle(SYMBOL));

            assertEquals(1, exchangeGateway.placeEntryMarketOrderCalls);
            assertEquals(2, exchangeGateway.placeProtectiveAlgoOrderCalls);

            exchangeGateway.resetExecutionCounters();

            exchangeGateway.setSymbolSnapshot(SYMBOL, snapshotOf(
                    position(SYMBOL, PositionSide.LONG, 0.50, 112.0, false, false)
            ));

            runtime.onAccountPositionUpdate(new UserStreamEvents.AccountPositionUpdateEvent(
                    SYMBOL,
                    PositionSide.LONG,
                    BigDecimal.valueOf(0.50),
                    BigDecimal.valueOf(112.0),
                    Instant.now()));

            assertEquals(0, exchangeGateway.placeEntryMarketOrderCalls);
            assertEquals(2, exchangeGateway.placeProtectiveAlgoOrderCalls);
            assertEquals(1, exchangeGateway.stopAlgoCalls);
            assertEquals(1, exchangeGateway.takeProfitAlgoCalls);
        } finally {
            runtime.stop();
        }
    }

    @Test
    void doesNotRepairWhenNoCachedPlanExists() {
        FakeExchangeGateway exchangeGateway = new FakeExchangeGateway();
        FakeMarketDataService marketDataService = new FakeMarketDataService();

        exchangeGateway.enqueueEquity(equity(5000));
        marketDataService.seedBullishScenario(SYMBOL);

        BotRuntime runtime = new BotRuntime(
                liveConfig(false),
                exchangeGateway,
                marketDataService);

        try {
            runtime.start();

            exchangeGateway.setSymbolSnapshot(SYMBOL, snapshotOf(
                    position(SYMBOL, PositionSide.LONG, 0.50, 112.0, false, false)
            ));

            runtime.onAccountPositionUpdate(new UserStreamEvents.AccountPositionUpdateEvent(
                    SYMBOL,
                    PositionSide.LONG,
                    BigDecimal.valueOf(0.50),
                    BigDecimal.valueOf(112.0),
                    Instant.now()));

            assertEquals(0, exchangeGateway.placeEntryMarketOrderCalls);
            assertEquals(0, exchangeGateway.placeProtectiveAlgoOrderCalls);
        } finally {
            runtime.stop();
        }
    }

    @Test
    void doesNotRepairWhenCachedPlanIsIncompatibleWithExchangePosition() {
        FakeExchangeGateway exchangeGateway = new FakeExchangeGateway();
        FakeMarketDataService marketDataService = new FakeMarketDataService();

        exchangeGateway.enqueueEquity(equity(5000));
        marketDataService.seedBullishScenario(SYMBOL);

        BotRuntime runtime = new BotRuntime(
                liveConfig(false),
                exchangeGateway,
                marketDataService);

        try {
            runtime.start();

            marketDataService.emit(bullishBreakoutTriggerCandle(SYMBOL));

            assertEquals(1, exchangeGateway.placeEntryMarketOrderCalls);
            assertEquals(2, exchangeGateway.placeProtectiveAlgoOrderCalls);

            exchangeGateway.resetExecutionCounters();

            exchangeGateway.setSymbolSnapshot(SYMBOL, snapshotOf(
                    position(SYMBOL, PositionSide.LONG, 0.50, 118.0, false, false) // >1% away from planned entry
            ));

            runtime.onAccountPositionUpdate(new UserStreamEvents.AccountPositionUpdateEvent(
                    SYMBOL,
                    PositionSide.LONG,
                    BigDecimal.valueOf(0.50),
                    BigDecimal.valueOf(118.0),
                    Instant.now()));

            assertEquals(0, exchangeGateway.placeEntryMarketOrderCalls);
            assertEquals(0, exchangeGateway.placeProtectiveAlgoOrderCalls);
        } finally {
            runtime.stop();
        }
    }

    @Test
    void doesNotRepairInDryRunMode() {
        FakeExchangeGateway exchangeGateway = new FakeExchangeGateway();
        FakeMarketDataService marketDataService = new FakeMarketDataService();

        exchangeGateway.enqueueEquity(equity(5000));
        marketDataService.seedBullishScenario(SYMBOL);

        BotRuntime runtime = new BotRuntime(
                liveConfig(true),
                exchangeGateway,
                marketDataService);

        try {
            runtime.start();

            exchangeGateway.setSymbolSnapshot(SYMBOL, snapshotOf(
                    position(SYMBOL, PositionSide.LONG, 0.50, 112.0, false, false)
            ));

            runtime.onAccountPositionUpdate(new UserStreamEvents.AccountPositionUpdateEvent(
                    SYMBOL,
                    PositionSide.LONG,
                    BigDecimal.valueOf(0.50),
                    BigDecimal.valueOf(112.0),
                    Instant.now()));

            assertEquals(0, exchangeGateway.placeEntryMarketOrderCalls);
            assertEquals(0, exchangeGateway.placeProtectiveAlgoOrderCalls);
        } finally {
            runtime.stop();
        }
    }

    private AppConfig liveConfig(boolean dryRun) {
        return new AppConfig(
                new AppConfig.ExchangeConfig(
                        "https://fapi.binance.com",
                        "wss://fstream.binance.com",
                        "key",
                        "secret",
                        false,
                        true),
                new AppConfig.TradingConfig(
                        List.of(SYMBOL),
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
                        disabledExitManagement()),
                new AppConfig.ReplayConfig(
                        List.of(SYMBOL),
                        "2025-01-01T00:00:00Z",
                        "2025-01-31T23:59:59Z",
                        200.0,
                        4.0,
                        tempDir.resolve("replay-trades.csv").toString()));
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

    private static AccountEquitySnapshot equity(double value) {
        BigDecimal usd = BigDecimal.valueOf(value);
        return new AccountEquitySnapshot(usd, usd, BigDecimal.ZERO);
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

    private static Candle bullishBreakoutTriggerCandle(String symbol) {
        Instant openTime = Instant.parse("2026-01-01T05:00:00Z");
        Instant closeTime = openTime.plusSeconds((15 * 60L) - 1);

        return new Candle(
                symbol,
                CandleInterval.MINUTES_15,
                openTime,
                closeTime,
                BigDecimal.valueOf(110.0),
                BigDecimal.valueOf(113.0),
                BigDecimal.valueOf(109.0),
                BigDecimal.valueOf(112.0),
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

    private static List<Candle> bullishPullback1hBars(String symbol) {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 38; i++) {
            closes.add(200.0 + i);
        }
        closes.add(235.0);
        closes.add(233.0);
        return candles(symbol, CandleInterval.HOUR_1, closes, 100.0);
    }

    private static List<Candle> seeded15mBarsWithoutBreakout(String symbol) {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            closes.add(100.0 + (i * 0.5));
        }
        closes.add(109.0);
        closes.add(110.0);
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
        private int stopAlgoCalls;
        private int takeProfitAlgoCalls;
        private int setLeverageCalls = 0;
        private int cancelAlgoOrderCalls;

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

        void resetExecutionCounters() {
            placeEntryMarketOrderCalls = 0;
            placeProtectiveAlgoOrderCalls = 0;
            stopAlgoCalls = 0;
            takeProfitAlgoCalls = 0;
        }

        @Override
        public void cancelAlgoOrder(String clientAlgoId) {
            cancelAlgoOrderCalls++;
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
            if (takeProfit) {
                takeProfitAlgoCalls++;
            } else {
                stopAlgoCalls++;
            }
            return clientAlgoId;
        }

        @Override
        public void cancelAllOpenOrders(String symbol) {
            fail("cancelAllOpenOrders not expected in protection repair tests");
        }

        @Override
        public void cancelAllOpenAlgoOrders(String symbol) {
            fail("cancelAllOpenAlgoOrders not expected in protection repair tests");
        }

        @Override
        public String closePositionMarket(PositionKey key, BigDecimal quantity, String clientOrderId) {
            fail("closePositionMarket not expected in protection repair tests");
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

        void seedBullishScenario(String symbol) {
            putHistory(symbol, CandleInterval.HOUR_4, bullish4hBars(symbol));
            putHistory(symbol, CandleInterval.HOUR_1, bullishPullback1hBars(symbol));
            putHistory(symbol, CandleInterval.MINUTES_15, seeded15mBarsWithoutBreakout(symbol));
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