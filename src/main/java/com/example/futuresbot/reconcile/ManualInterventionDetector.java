package com.example.futuresbot.reconcile;

import com.example.futuresbot.exchange.UserStreamEvents;

import java.util.Set;

public final class ManualInterventionDetector {

    public boolean isManual(UserStreamEvents.OrderTradeUpdateEvent event, Set<String> knownBotClientOrderIds) {
        String clientOrderId = event.clientOrderId();
        if (clientOrderId == null || clientOrderId.isBlank()) {
            return false;
        }
        if (clientOrderId.startsWith("BOT_")) {
            return false;
        }
        return !knownBotClientOrderIds.contains(clientOrderId);
    }

    public boolean isManualAlgo(String clientAlgoId, Set<String> knownBotClientAlgoIds) {
        if (clientAlgoId == null || clientAlgoId.isBlank()) {
            return false;
        }
        if (clientAlgoId.startsWith("BOT_")) {
            return false;
        }
        return !knownBotClientAlgoIds.contains(clientAlgoId);
    }
}