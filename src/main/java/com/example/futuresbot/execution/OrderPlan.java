package com.example.futuresbot.execution;

import com.example.futuresbot.strategy.SignalType;

import java.math.BigDecimal;

public record OrderPlan(
        String symbol,
        SignalType signalType,
        boolean accepted,
        BigDecimal entryPrice,
        BigDecimal stopPrice,
        BigDecimal takeProfitPrice,
        BigDecimal quantity,
        BigDecimal notionalUsd,
        BigDecimal sizingEquityUsd,
        BigDecimal riskAmountUsd,
        BigDecimal atrUsd,
        String rejectionReason) {
    public static OrderPlan accepted(
            String symbol,
            SignalType signalType,
            BigDecimal entryPrice,
            BigDecimal stopPrice,
            BigDecimal takeProfitPrice,
            BigDecimal quantity,
            BigDecimal notionalUsd,
            BigDecimal sizingEquityUsd,
            BigDecimal riskAmountUsd,
            BigDecimal atrUsd) {
        return new OrderPlan(
                symbol,
                signalType,
                true,
                entryPrice,
                stopPrice,
                takeProfitPrice,
                quantity,
                notionalUsd,
                sizingEquityUsd,
                riskAmountUsd,
                atrUsd,
                null);
    }

    public static OrderPlan rejected(String symbol, SignalType signalType, String reason) {
        return new OrderPlan(
                symbol,
                signalType,
                false,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                reason);
    }
}