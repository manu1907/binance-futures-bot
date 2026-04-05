package com.example.futuresbot.runtime;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.domain.PositionKey;
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
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BotRuntimeDryRunFlowTest {

    private static final String SYMBOL = "BTCUSDT";

    @TempDir
    Path tempDir;

    @Test
    void startBootstrapsAndProcessesDryRunSignalWithoutSendingLiveOrders() {
        FakeExchangeGateway exchangeGateway = new FakeExchangeGateway();
        FakeMarketDataService marketDataService = new FakeMarketDataService();

        marketDataService.putHistory(SYMBOL, CandleInterval.HOUR_4, bullish4hBars());
        marketDataService.putHistory(SYMBOL, CandleInterval.HOUR_1, bullishPullback1hBars());
        marketDataService.putHistory(SYMBOL, CandleInterval.MINUTES_15, seeded15mBarsWithoutBreakout());

        BotRuntime runtime = new BotRuntime(config(), exchangeGateway, marketDataService);
        try {
            runtime.start();

            assertEquals(1, exchangeGateway.connectUserStreamCalls);
            assertEquals(1, marketDataService.connectKlineStreamsCalls);
            assertEquals(3, marketDataService.loadHistoricalKlinesCalls);
            assertEquals(1, exchangeGateway.setLeverageCalls);
            assertNotNull(marketDataService.candleConsumer, "Expected kline consumer to be registered");

            marketDataService.emit(bullishBreakoutTriggerCandle());

            assertEquals(0, exchangeGateway.placeEntryMarketOrderCalls,
                    "Dry-run mode must not place live entry orders");
            assertEquals(0, exchangeGateway.placeProtectiveAlgoOrderCalls,
                    "Dry-run mode must not place protective algo orders");
            assertEquals(0, exchangeGateway.closePositionMarketCalls,
                    "Dry-run mode must not close positions on the exchange");
            assertEquals(0, exchangeGateway.cancelAllOpenOrdersCalls,
                    "Dry-run signal handling should not cancel regular orders");
            assertEquals(0, exchangeGateway.cancelAllOpenAlgoOrdersCalls,
                    "Dry-run signal handling should not cancel algo orders");
        } finally {
            runtime.stop();
        }
    }

    private AppConfig config() {
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
                        true,
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

    private static List<Candle> bullishPullback1hBars() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 38; i++) {
            closes.add(200.0 + i);
        }
        closes.add(235.0);
        closes.add(233.0);
        return candles(SYMBOL, CandleInterval.HOUR_1, closes, 100.0);
    }

    private static List<Candle> seeded15mBarsWithoutBreakout() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            closes.add(100.0 + (i * 0.5));
        }
        closes.add(109.0);
        closes.add(110.0);
        return candles(SYMBOL, CandleInterval.MINUTES_15, closes, 100.0);
    }

    private static Candle bullishBreakoutTriggerCandle() {
        Instant openTime = Instant.parse("2026-01-01T05:00:00Z");
        Instant closeTime = openTime.plusSeconds((15 * 60L) - 1);

        return new Candle(
                SYMBOL,
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
        private final ExchangeSnapshot emptySnapshot = new ExchangeSnapshot(List.of(), List.of(), List.of());
        private final AccountEquitySnapshot equity = new AccountEquitySnapshot(
                BigDecimal.valueOf(200.0),
                BigDecimal.valueOf(200.0),
                BigDecimal.ZERO);

        private int connectUserStreamCalls;
        private int setLeverageCalls;
        private int placeEntryMarketOrderCalls;
        private int placeProtectiveAlgoOrderCalls;
        private int cancelAllOpenOrdersCalls;
        private int cancelAllOpenAlgoOrdersCalls;
        private int closePositionMarketCalls;
        private int cancelAlgoOrderCalls;

        @SuppressWarnings("unused")
        private Consumer<UserStreamEvents.UserStreamEvent> userStreamConsumer;

        @Override
        public void cancelAlgoOrder(String clientAlgoId) {
            cancelAlgoOrderCalls++;
        }

        @Override
        public ExchangeSnapshot currentSnapshot() {
            return emptySnapshot;
        }

        @Override
        public ExchangeSnapshot currentSnapshot(String symbol) {
            return emptySnapshot;
        }

        @Override
        public boolean isHedgeModeEnabled() {
            return true;
        }

        @Override
        public AccountEquitySnapshot accountEquity() {
            return equity;
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
        public void setLeverage(String symbol, int leverage) {
            setLeverageCalls++;
        }

        @Override
        public String placeEntryMarketOrder(String symbol, SignalType signalType, BigDecimal quantity,
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
        public void cancelAllOpenOrders(String symbol) {
            cancelAllOpenOrdersCalls++;
        }

        @Override
        public void cancelAllOpenAlgoOrders(String symbol) {
            cancelAllOpenAlgoOrdersCalls++;
        }

        @Override
        public String closePositionMarket(PositionKey key, BigDecimal quantity, String clientOrderId) {
            closePositionMarketCalls++;
            return clientOrderId;
        }

        @Override
        public List<IncomeRecord> incomeHistory(String symbol, Instant startInclusive, Instant endInclusive) {
            return List.of();
        }

        @Override
        @SuppressWarnings("unchecked")
        public void connectUserStream(Consumer consumer) {
            connectUserStreamCalls++;
            this.userStreamConsumer = (Consumer<UserStreamEvents.UserStreamEvent>) consumer;
        }
    }

    private static final class FakeMarketDataService implements MarketDataService {
        private final Map<String, Map<CandleInterval, List<Candle>>> historical = new java.util.HashMap<>();

        private int loadHistoricalKlinesCalls;
        private int connectKlineStreamsCalls;

        @SuppressWarnings("unused")
        private List<String> connectedSymbols;

        @SuppressWarnings("unused")
        private List<CandleInterval> connectedIntervals;

        private Consumer<Candle> candleConsumer;

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
            loadHistoricalKlinesCalls++;
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
            connectKlineStreamsCalls++;
            this.connectedSymbols = (List<String>) symbols;
            this.connectedIntervals = (List<CandleInterval>) intervals;
            this.candleConsumer = (Consumer<Candle>) consumer;
        }
    }
}