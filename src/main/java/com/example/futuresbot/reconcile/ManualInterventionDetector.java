package com.example.futuresbot.reconcile;

import com.example.futuresbot.exchange.UserStreamEvents.OrderTradeUpdateEvent;

import java.util.Set;

public final class ManualInterventionDetector {

    public boolean isManual(OrderTradeUpdateEvent event, Set<String> knownBotClientOrderIds) {
        if (event.clientOrderId() == null || event.clientOrderId().isBlank()) {
            return true;
        }
        return !knownBotClientOrderIds.contains(event.clientOrderId());
    }

    public boolean isManualAlgo(String clientAlgoId, Set<String> knownBotClientAlgoIds) {
        if (clientAlgoId == null || clientAlgoId.isBlank()) {
            return true;
        }
        return !knownBotClientAlgoIds.contains(clientAlgoId);
    }
}