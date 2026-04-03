package com.example.futuresbot.risk;

import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.domain.PositionSide;
import com.example.futuresbot.exchange.ExchangeGateway;
import com.example.futuresbot.exchange.ExchangeSnapshot;
import com.example.futuresbot.exchange.IncomeRecord;
import com.example.futuresbot.exchange.SymbolRules;
import com.example.futuresbot.exchange.UserStreamEvents;
import com.example.futuresbot.execution.AccountEquitySnapshot;
import com.example.futuresbot.strategy.SignalType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyRiskManagerTest {

    @Test
    void blocksAfterDailyDrawdownLimit() throws Exception {
        Path journal = Files.createTempFile("trade-journal-", ".csv");
        DailyRiskManager manager = new DailyRiskManager(
                2.0,
                3,
                new TradeJournalCsvWriter(journal.toString()));

        Instant now = Instant.parse("2026-03-31T08:00:00Z");
        manager.initialize(new BigDecimal("200.00"), now);

        RiskGateDecision decision = manager.evaluateCanTrade(new BigDecimal("195.99"), now.plusSeconds(60));

        assertFalse(decision.allowed());
    }

    @Test
    void blocksAfterConsecutiveLosses() throws Exception {
        Path journal = Files.createTempFile("trade-journal-", ".csv");
        DailyRiskManager manager = new DailyRiskManager(
                10.0,
                2,
                new TradeJournalCsvWriter(journal.toString()));

        Instant now = Instant.parse("2026-03-31T08:00:00Z");
        manager.initialize(new BigDecimal("200.00"), now);

        ExchangeGateway firstLossGateway = new TestExchangeGateway(List.of(
                new IncomeRecord("BTCUSDT", "REALIZED_PNL", new BigDecimal("-0.80"), "USDT", now.plusSeconds(10), ""),
                new IncomeRecord("BTCUSDT", "COMMISSION", new BigDecimal("-0.10"), "USDT", now.plusSeconds(10), ""),
                new IncomeRecord("BTCUSDT", "FUNDING_FEE", new BigDecimal("0.00"), "USDT", now.plusSeconds(10), "")));

        ExchangeGateway secondLossGateway = new TestExchangeGateway(List.of(
                new IncomeRecord("ETHUSDT", "REALIZED_PNL", new BigDecimal("-0.70"), "USDT", now.plusSeconds(30), ""),
                new IncomeRecord("ETHUSDT", "COMMISSION", new BigDecimal("-0.10"), "USDT", now.plusSeconds(30), ""),
                new IncomeRecord("ETHUSDT", "FUNDING_FEE", new BigDecimal("0.00"), "USDT", now.plusSeconds(30), "")));

        PositionKey key1 = new PositionKey("BTCUSDT", PositionSide.LONG);
        manager.trackOpenPositionIfAbsent(key1, new BigDecimal("200.00"), now, "test");
        manager.onPositionClosed(key1, new BigDecimal("199.00"), now.plusSeconds(10), "test-close", firstLossGateway);

        PositionKey key2 = new PositionKey("ETHUSDT", PositionSide.SHORT);
        manager.trackOpenPositionIfAbsent(key2, new BigDecimal("199.00"), now.plusSeconds(20), "test");
        manager.onPositionClosed(key2, new BigDecimal("198.00"), now.plusSeconds(30), "test-close", secondLossGateway);

        RiskGateDecision decision = manager.evaluateCanTrade(new BigDecimal("198.00"), now.plusSeconds(40));

        assertFalse(decision.allowed());
    }

    @Test
    void allowsWhenWithinLimits() throws Exception {
        Path journal = Files.createTempFile("trade-journal-", ".csv");
        DailyRiskManager manager = new DailyRiskManager(
                5.0,
                3,
                new TradeJournalCsvWriter(journal.toString()));

        Instant now = Instant.parse("2026-03-31T08:00:00Z");
        manager.initialize(new BigDecimal("200.00"), now);

        RiskGateDecision decision = manager.evaluateCanTrade(new BigDecimal("199.50"), now.plusSeconds(60));

        assertTrue(decision.allowed());
    }

    private static final class TestExchangeGateway implements ExchangeGateway {
        private int cancelAlgoOrderCalls;

        private final List<IncomeRecord> incomeRecords;

        private TestExchangeGateway(List<IncomeRecord> incomeRecords) {
            this.incomeRecords = incomeRecords;
        }

        @Override
        public ExchangeSnapshot currentSnapshot() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExchangeSnapshot currentSnapshot(String symbol) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isHedgeModeEnabled() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountEquitySnapshot accountEquity() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SymbolRules symbolRules(String symbol) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String placeEntryMarketOrder(String symbol, SignalType signalType, BigDecimal quantity,
                                            String clientOrderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String placeProtectiveAlgoOrder(
                String symbol,
                SignalType signalType,
                BigDecimal triggerPrice,
                boolean takeProfit,
                String clientAlgoId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelAllOpenOrders(String symbol) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelAllOpenAlgoOrders(String symbol) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String closePositionMarket(PositionKey key, BigDecimal quantity, String clientOrderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IncomeRecord> incomeHistory(String symbol, Instant startInclusive, Instant endInclusive) {
            return incomeRecords.stream()
                    .filter(record -> record.symbol().equals(symbol))
                    .filter(record -> !record.time().isBefore(startInclusive))
                    .filter(record -> !record.time().isAfter(endInclusive))
                    .toList();
        }

        @Override
        public void connectUserStream(Consumer<UserStreamEvents.UserStreamEvent> consumer) {
            throw new UnsupportedOperationException();
        }

        public void setLeverage(String symbol, int leverage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelAlgoOrder(String clientAlgoId) {
            cancelAlgoOrderCalls++;
        }
    }
}