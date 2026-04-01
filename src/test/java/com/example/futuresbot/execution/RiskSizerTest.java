package com.example.futuresbot.execution;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RiskSizerTest {

    private final RiskSizer riskSizer = new RiskSizer();

    @Test
    void computesQuantityFromRiskAndStopDistance() {
        BigDecimal quantity = riskSizer.quantityForRisk(
                new BigDecimal("1.00"),
                new BigDecimal("100.00"),
                new BigDecimal("98.00"));

        assertEquals(new BigDecimal("0.50000000"), quantity);
    }
}