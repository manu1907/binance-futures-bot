package com.example.futuresbot.risk;

import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.domain.PositionSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

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

        PositionKey key1 = new PositionKey("BTCUSDT", PositionSide.LONG);
        manager.trackOpenPositionIfAbsent(key1, new BigDecimal("200.00"), now, "test");
        manager.onPositionClosed(key1, new BigDecimal("199.00"), now.plusSeconds(10), "test-close");

        PositionKey key2 = new PositionKey("ETHUSDT", PositionSide.SHORT);
        manager.trackOpenPositionIfAbsent(key2, new BigDecimal("199.00"), now.plusSeconds(20), "test");
        manager.onPositionClosed(key2, new BigDecimal("198.00"), now.plusSeconds(30), "test-close");

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
}