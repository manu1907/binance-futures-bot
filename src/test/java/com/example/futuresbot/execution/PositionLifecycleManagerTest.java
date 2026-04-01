package com.example.futuresbot.execution;

import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.domain.PositionSide;
import com.example.futuresbot.domain.PositionSnapshot;
import com.example.futuresbot.exchange.ExchangeSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionLifecycleManagerTest {

    private final PositionLifecycleManager manager = new PositionLifecycleManager();

    @Test
    void ignoresWhenSameSidePositionExists() {
        PositionKey desired = new PositionKey("BTCUSDT", PositionSide.LONG);
        ExchangeSnapshot snapshot = new ExchangeSnapshot(
                List.of(openPosition("BTCUSDT", PositionSide.LONG)),
                List.of(),
                List.of());

        LifecycleDecision decision = manager.evaluate(desired, snapshot, OppositeSignalPolicy.IGNORE);

        assertEquals(LifecycleDecision.Action.IGNORE_SIGNAL, decision.action());
    }

    @Test
    void ignoresOppositeSignalWhenPolicyIsIgnore() {
        PositionKey desired = new PositionKey("BTCUSDT", PositionSide.LONG);
        ExchangeSnapshot snapshot = new ExchangeSnapshot(
                List.of(openPosition("BTCUSDT", PositionSide.SHORT)),
                List.of(),
                List.of());

        LifecycleDecision decision = manager.evaluate(desired, snapshot, OppositeSignalPolicy.IGNORE);

        assertEquals(LifecycleDecision.Action.IGNORE_SIGNAL, decision.action());
    }

    @Test
    void flattensOppositeSignalWhenPolicySaysSo() {
        PositionKey desired = new PositionKey("BTCUSDT", PositionSide.LONG);
        ExchangeSnapshot snapshot = new ExchangeSnapshot(
                List.of(openPosition("BTCUSDT", PositionSide.SHORT)),
                List.of(),
                List.of());

        LifecycleDecision decision = manager.evaluate(desired, snapshot, OppositeSignalPolicy.FLATTEN_AND_WAIT);

        assertEquals(LifecycleDecision.Action.FLATTEN_AND_WAIT, decision.action());
    }

    @Test
    void proceedsWhenFlat() {
        PositionKey desired = new PositionKey("BTCUSDT", PositionSide.LONG);
        ExchangeSnapshot snapshot = new ExchangeSnapshot(List.of(), List.of(), List.of());

        LifecycleDecision decision = manager.evaluate(desired, snapshot, OppositeSignalPolicy.IGNORE);

        assertEquals(LifecycleDecision.Action.PROCEED, decision.action());
    }

    private PositionSnapshot openPosition(String symbol, PositionSide side) {
        return new PositionSnapshot(
                symbol,
                side,
                new BigDecimal("0.001"),
                new BigDecimal("70000"),
                new BigDecimal("70000"),
                BigDecimal.ZERO,
                new BigDecimal("62000"),
                true,
                true,
                Instant.now());
    }
}