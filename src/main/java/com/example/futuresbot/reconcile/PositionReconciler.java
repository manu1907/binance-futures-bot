package com.example.futuresbot.reconcile;

import com.example.futuresbot.domain.ManagedPosition;
import com.example.futuresbot.domain.PositionSnapshot;

import java.time.Instant;
import java.util.Optional;

public final class PositionReconciler {

        public ReconcileDecision reconcile(
                        Optional<ManagedPosition> internalPosition,
                        Optional<PositionSnapshot> exchangePosition,
                        boolean manualInterventionDetected) {
                if (exchangePosition.isEmpty()) {
                        return internalPosition
                                        .map(p -> new ReconcileDecision(
                                                        ReconcileDecision.Action.CLEAR_INTERNAL_POSITION, null,
                                                        "Exchange is flat; clear internal state"))
                                        .orElse(new ReconcileDecision(ReconcileDecision.Action.NO_CHANGE, null,
                                                        "Both bot and exchange are flat"));
                }

                PositionSnapshot exchange = exchangePosition.get();

                if (internalPosition.isEmpty()) {
                        return new ReconcileDecision(
                                        ReconcileDecision.Action.ADOPT_EXCHANGE_POSITION,
                                        new ManagedPosition(exchange, manualInterventionDetected, Instant.now(),
                                                        manualInterventionDetected ? "Adopted manual exchange position"
                                                                        : "Adopted exchange position"),
                                        "No internal position existed");
                }

                ManagedPosition current = internalPosition.get();
                PositionSnapshot currentSnapshot = current.snapshot();

                boolean materiallyChanged = currentSnapshot.quantity().compareTo(exchange.quantity()) != 0 ||
                                currentSnapshot.entryPrice().compareTo(exchange.entryPrice()) != 0 ||
                                currentSnapshot.side() != exchange.side();

                if (!materiallyChanged) {
                        return new ReconcileDecision(ReconcileDecision.Action.NO_CHANGE, current,
                                        "Internal position already matches exchange");
                }

                return new ReconcileDecision(
                                ReconcileDecision.Action.UPDATE_EXISTING,
                                new ManagedPosition(exchange, manualInterventionDetected, Instant.now(),
                                                manualInterventionDetected ? "Updated from manual intervention"
                                                                : "Updated from exchange reconciliation"),
                                "Exchange position differs from internal state");
        }
}