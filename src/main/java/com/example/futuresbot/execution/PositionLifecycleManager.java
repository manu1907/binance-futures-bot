package com.example.futuresbot.execution;

import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.domain.PositionSide;
import com.example.futuresbot.exchange.ExchangeSnapshot;

public final class PositionLifecycleManager {

    public LifecycleDecision evaluate(
            PositionKey desiredKey,
            ExchangeSnapshot snapshot,
            OppositeSignalPolicy oppositeSignalPolicy) {
        if (snapshot.findPosition(desiredKey).isPresent()) {
            return LifecycleDecision.ignore("Same-side position already open");
        }

        PositionKey oppositeKey = new PositionKey(
                desiredKey.symbol(),
                desiredKey.side() == PositionSide.LONG ? PositionSide.SHORT : PositionSide.LONG);

        if (snapshot.findPosition(oppositeKey).isPresent()) {
            return switch (oppositeSignalPolicy) {
                case IGNORE -> LifecycleDecision.ignore("Opposite-side position already open");
                case FLATTEN_AND_WAIT -> LifecycleDecision.flattenAndWait("Flatten opposite-side position first");
            };
        }

        return LifecycleDecision.proceed();
    }
}