package com.example.futuresbot.execution;

import java.math.BigDecimal;

public record PositionManagementDecision(
        boolean updateRequired,
        BigDecimal newStopTriggerPrice,
        BigDecimal newTakeProfitTriggerPrice,
        String reason) {

    public static PositionManagementDecision noChange(String reason) {
        return new PositionManagementDecision(false, BigDecimal.ZERO, BigDecimal.ZERO, reason);
    }

    public static PositionManagementDecision update(
            BigDecimal newStopTriggerPrice,
            BigDecimal newTakeProfitTriggerPrice,
            String reason) {
        return new PositionManagementDecision(true, newStopTriggerPrice, newTakeProfitTriggerPrice, reason);
    }
}