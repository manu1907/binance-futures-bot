package com.example.futuresbot.reconcile;

import com.example.futuresbot.domain.ManagedPosition;

public record ReconcileDecision(
        Action action,
        ManagedPosition managedPosition,
        String reason) {
    public enum Action {
        NO_CHANGE,
        ADOPT_EXCHANGE_POSITION,
        UPDATE_EXISTING,
        CLEAR_INTERNAL_POSITION
    }
}