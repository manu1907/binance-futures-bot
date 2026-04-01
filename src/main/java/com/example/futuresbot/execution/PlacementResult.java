package com.example.futuresbot.execution;

import com.example.futuresbot.strategy.SignalType;

import java.math.BigDecimal;

public record PlacementResult(
        String symbol,
        SignalType signalType,
        boolean accepted,
        BigDecimal executedQuantity,
        BigDecimal stopTriggerPrice,
        BigDecimal takeProfitTriggerPrice,
        String entryClientOrderId,
        String stopClientAlgoId,
        String takeProfitClientAlgoId,
        String rejectionReason) {
    public static PlacementResult accepted(
            String symbol,
            SignalType signalType,
            BigDecimal executedQuantity,
            BigDecimal stopTriggerPrice,
            BigDecimal takeProfitTriggerPrice,
            String entryClientOrderId,
            String stopClientAlgoId,
            String takeProfitClientAlgoId) {
        return new PlacementResult(
                symbol,
                signalType,
                true,
                executedQuantity,
                stopTriggerPrice,
                takeProfitTriggerPrice,
                entryClientOrderId,
                stopClientAlgoId,
                takeProfitClientAlgoId,
                null);
    }

    public static PlacementResult rejected(String symbol, SignalType signalType, String reason) {
        return new PlacementResult(
                symbol,
                signalType,
                false,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null,
                null,
                reason);
    }
}