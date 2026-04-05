package com.example.futuresbot.reconcile;

import com.example.futuresbot.domain.PositionSide;
import com.example.futuresbot.exchange.UserStreamEvents;
import com.example.futuresbot.exchange.UserStreamEvents.OrderTradeUpdateEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualInterventionDetectorTest {

    private final ManualInterventionDetector detector = new ManualInterventionDetector();

    @Test
    void flagsUnknownClientOrderIdAsManual() {
        OrderTradeUpdateEvent event = new OrderTradeUpdateEvent(
                "BTCUSDT",
                PositionSide.LONG,
                "manual-123",
                "FILLED",
                "TRADE",
                Instant.now());

        assertTrue(detector.isManual(event, Set.of("bot-abc")));
    }

    @Test
    void acceptsKnownClientOrderIdAsBotManaged() {
        OrderTradeUpdateEvent event = new OrderTradeUpdateEvent(
                "BTCUSDT",
                PositionSide.LONG,
                "bot-abc",
                "FILLED",
                "TRADE",
                Instant.now());

        assertFalse(detector.isManual(event, Set.of("bot-abc")));
    }

    @Test
    void botPrefixedOrderIdIsNotManualEvenIfNotYetRecorded() {
        ManualInterventionDetector detector = new ManualInterventionDetector();

        UserStreamEvents.OrderTradeUpdateEvent event =
                new UserStreamEvents.OrderTradeUpdateEvent(
                        "BTCUSDT",
                        PositionSide.LONG,
                        "BOT_ENTRY_abc123",
                        "NEW",
                        "NEW",
                        Instant.now());

        assertFalse(detector.isManual(event, Set.of()));
    }

    @Test
    void botPrefixedAlgoIdIsNotManualEvenIfNotYetRecorded() {
        ManualInterventionDetector detector = new ManualInterventionDetector();

        assertFalse(detector.isManualAlgo("BOT_STOP_abc123", Set.of()));
    }
}