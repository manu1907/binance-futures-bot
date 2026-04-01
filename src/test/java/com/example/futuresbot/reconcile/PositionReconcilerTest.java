package com.example.futuresbot.reconcile;

import com.example.futuresbot.domain.ManagedPosition;
import com.example.futuresbot.domain.PositionSide;
import com.example.futuresbot.domain.PositionSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionReconcilerTest {

    private final PositionReconciler reconciler = new PositionReconciler();

    @Test
    void adoptsExchangePositionWhenInternalMissing() {
        PositionSnapshot snapshot = new PositionSnapshot(
                "BTCUSDT",
                PositionSide.LONG,
                new BigDecimal("0.001"),
                new BigDecimal("70000"),
                new BigDecimal("70010"),
                BigDecimal.ZERO,
                new BigDecimal("62000"),
                false,
                false,
                Instant.now()
        );

        ReconcileDecision decision = reconciler.reconcile(Optional.empty(), Optional.of(snapshot), true);

        assertEquals(ReconcileDecision.Action.ADOPT_EXCHANGE_POSITION, decision.action());
    }

    @Test
    void updatesWhenExchangePositionChanges() {
        ManagedPosition internal = new ManagedPosition(
                new PositionSnapshot(
                        "BTCUSDT",
                        PositionSide.LONG,
                        new BigDecimal("0.001"),
                        new BigDecimal("70000"),
                        new BigDecimal("70010"),
                        BigDecimal.ZERO,
                        new BigDecimal("62000"),
                        true,
                        false,
                        Instant.now()
                ),
                false,
                Instant.now(),
                "existing"
        );

        PositionSnapshot exchange = new PositionSnapshot(
                "BTCUSDT",
                PositionSide.LONG,
                new BigDecimal("0.002"),
                new BigDecimal("70100"),
                new BigDecimal("70105"),
                BigDecimal.ZERO,
                new BigDecimal("62100"),
                true,
                false,
                Instant.now()
        );

        ReconcileDecision decision = reconciler.reconcile(Optional.of(internal), Optional.of(exchange), true);

        assertEquals(ReconcileDecision.Action.UPDATE_EXISTING, decision.action());
    }

    @Test
    void clearsInternalWhenExchangeIsFlat() {
        ManagedPosition internal = new ManagedPosition(
                new PositionSnapshot(
                        "BTCUSDT",
                        PositionSide.LONG,
                        new BigDecimal("0.001"),
                        new BigDecimal("70000"),
                        new BigDecimal("70010"),
                        BigDecimal.ZERO,
                        new BigDecimal("62000"),
                        true,
                        false,
                        Instant.now()
                ),
                false,
                Instant.now(),
                "existing"
        );

        ReconcileDecision decision = reconciler.reconcile(Optional.of(internal), Optional.empty(), false);

        assertEquals(ReconcileDecision.Action.CLEAR_INTERNAL_POSITION, decision.action());
    }
}