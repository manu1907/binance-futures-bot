package com.example.futuresbot.execution;

import com.example.futuresbot.domain.ManagedPosition;
import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.domain.PositionSide;
import com.example.futuresbot.domain.PositionSnapshot;
import com.example.futuresbot.exchange.ExchangeSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeGuardrailsTest {

    private final TradeGuardrails guardrails = new TradeGuardrails();

    @Test
    void blocksWhenPositionAlreadyExists() {
        PositionKey key = new PositionKey("BTCUSDT", PositionSide.LONG);
        ExchangeSnapshot snapshot = new ExchangeSnapshot(
                List.of(new PositionSnapshot(
                        "BTCUSDT",
                        PositionSide.LONG,
                        new BigDecimal("0.001"),
                        new BigDecimal("70000"),
                        new BigDecimal("70000"),
                        BigDecimal.ZERO,
                        new BigDecimal("62000"),
                        true,
                        true,
                        Instant.now())),
                List.of(),
                List.of());

        GuardrailDecision decision = guardrails.evaluate(
                key, snapshot, Map.of(), null, 1, 120, Instant.now());

        assertFalse(decision.allowed());
    }

    @Test
    void blocksDuringCooldown() {
        PositionKey key = new PositionKey("BTCUSDT", PositionSide.LONG);
        ExchangeSnapshot snapshot = new ExchangeSnapshot(List.of(), List.of(), List.of());

        GuardrailDecision decision = guardrails.evaluate(
                key,
                snapshot,
                Map.of(),
                Instant.now().minusSeconds(30),
                1,
                120,
                Instant.now());

        assertFalse(decision.allowed());
    }

    @Test
    void allowsCleanEntry() {
        PositionKey key = new PositionKey("BTCUSDT", PositionSide.LONG);
        ExchangeSnapshot snapshot = new ExchangeSnapshot(List.of(), List.of(), List.of());

        GuardrailDecision decision = guardrails.evaluate(
                key,
                snapshot,
                Map.of(),
                Instant.now().minusSeconds(300),
                1,
                120,
                Instant.now());

        assertTrue(decision.allowed());
    }

    @Test
    void blocksWhenAnotherManagedPositionIsOpenAndLimitReached() {
        PositionKey existingKey = new PositionKey("ETHUSDT", PositionSide.SHORT);
        ManagedPosition existing = new ManagedPosition(
                new PositionSnapshot(
                        "ETHUSDT",
                        PositionSide.SHORT,
                        new BigDecimal("0.01"),
                        new BigDecimal("3500"),
                        new BigDecimal("3500"),
                        BigDecimal.ZERO,
                        new BigDecimal("4500"),
                        true,
                        true,
                        Instant.now()),
                false,
                Instant.now(),
                "existing");

        GuardrailDecision decision = guardrails.evaluate(
                new PositionKey("BTCUSDT", PositionSide.LONG),
                new ExchangeSnapshot(List.of(), List.of(), List.of()),
                Map.of(existingKey, existing),
                Instant.now().minusSeconds(300),
                1,
                120,
                Instant.now());

        assertFalse(decision.allowed());
    }
}