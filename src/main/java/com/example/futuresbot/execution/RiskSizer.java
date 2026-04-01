package com.example.futuresbot.execution;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class RiskSizer {

    public BigDecimal quantityForRisk(BigDecimal riskAmountUsd, BigDecimal entryPrice, BigDecimal stopPrice) {
        BigDecimal riskPerUnit = entryPrice.subtract(stopPrice).abs();
        if (riskAmountUsd == null || riskAmountUsd.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (riskPerUnit.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return riskAmountUsd.divide(riskPerUnit, 8, RoundingMode.DOWN);
    }
}