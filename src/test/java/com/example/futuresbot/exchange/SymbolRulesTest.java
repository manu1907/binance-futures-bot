package com.example.futuresbot.exchange;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SymbolRulesTest {

    @Test
    void roundsMarketQuantityDownToStep() {
        SymbolRules rules = new SymbolRules(
                "BTCUSDT",
                new BigDecimal("0.10"),
                new BigDecimal("1000000"),
                new BigDecimal("0.10"),
                new BigDecimal("0.001"),
                new BigDecimal("1000"),
                new BigDecimal("0.001"),
                new BigDecimal("0.001"),
                new BigDecimal("1000"),
                new BigDecimal("0.001"),
                new BigDecimal("5"),
                new BigDecimal("0.05"));

        assertEquals(new BigDecimal("0.005"), rules.normalizeMarketQuantity(new BigDecimal("0.0059")));
    }

    @Test
    void roundsPriceDownToTick() {
        SymbolRules rules = new SymbolRules(
                "BTCUSDT",
                new BigDecimal("0.10"),
                new BigDecimal("1000000"),
                new BigDecimal("0.10"),
                new BigDecimal("0.001"),
                new BigDecimal("1000"),
                new BigDecimal("0.001"),
                new BigDecimal("0.001"),
                new BigDecimal("1000"),
                new BigDecimal("0.001"),
                new BigDecimal("5"),
                new BigDecimal("0.05"));

        assertEquals(new BigDecimal("65000.10"), rules.normalizePrice(new BigDecimal("65000.19")));
    }
}