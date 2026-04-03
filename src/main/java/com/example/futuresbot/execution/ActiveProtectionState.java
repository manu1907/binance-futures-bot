package com.example.futuresbot.execution;

import java.math.BigDecimal;
import java.time.Instant;

public record ActiveProtectionState(
        BigDecimal stopTriggerPrice,
        BigDecimal takeProfitTriggerPrice,
        String stopClientAlgoId,
        String takeProfitClientAlgoId,
        Instant updatedAt) {
}