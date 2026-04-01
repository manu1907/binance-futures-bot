package com.example.futuresbot.execution;

import com.example.futuresbot.domain.ManagedPosition;
import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.exchange.ExchangeSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public final class TradeGuardrails {

    public GuardrailDecision evaluate(
            PositionKey key,
            ExchangeSnapshot snapshot,
            Map<PositionKey, ManagedPosition> managedPositions,
            Instant lastTradeActivity,
            int maxOpenPositions,
            int entryCooldownSeconds,
            Instant now) {
        if (snapshot.findPosition(key).isPresent()) {
            return GuardrailDecision.block("Exchange already has an open position on this symbol/side");
        }

        if (snapshot.hasAnyOpenRegularOrder(key)) {
            return GuardrailDecision.block("Exchange already has an open regular order on this symbol/side");
        }

        if (snapshot.hasAnyOpenAlgoOrder(key)) {
            return GuardrailDecision.block("Exchange already has an open algo order on this symbol/side");
        }

        long activeManagedPositions = managedPositions.values().stream()
                .filter(position -> position.snapshot() != null && !position.snapshot().isFlat())
                .count();

        if (activeManagedPositions >= maxOpenPositions && !managedPositions.containsKey(key)) {
            return GuardrailDecision.block("Max open positions reached");
        }

        if (lastTradeActivity != null && entryCooldownSeconds > 0) {
            long elapsed = Duration.between(lastTradeActivity, now).getSeconds();
            if (elapsed < entryCooldownSeconds) {
                return GuardrailDecision.block(
                        "Entry cooldown active: " + (entryCooldownSeconds - elapsed) + "s remaining");
            }
        }

        return GuardrailDecision.allow();
    }
}