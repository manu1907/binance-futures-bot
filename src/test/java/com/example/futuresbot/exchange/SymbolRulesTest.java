package com.example.futuresbot.exchange;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SymbolRulesTest {

    @Test
    void roundsMarketQuantityDownToConfiguredStep() {
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

        assertEquals("0.005", rules.normalizeMarketQuantity(new BigDecimal("0.0059")).toPlainString());
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

        assertEquals("65000.1", rules.normalizePrice(new BigDecimal("65000.19")).toPlainString());
    }

    @Test
    void normalizesSolMarketQuantityToTwoDecimalsFromExchangeFilters() {
        SymbolRules rules = new SymbolRules(
                "SOLUSDT",
                new BigDecimal("0.0001"),
                new BigDecimal("1000000"),
                new BigDecimal("0.0001"),
                new BigDecimal("0.01"),
                new BigDecimal("1000000"),
                new BigDecimal("0.01"),
                new BigDecimal("0.01"),
                new BigDecimal("6000"),
                new BigDecimal("0.01"),
                new BigDecimal("5"),
                new BigDecimal("0.05"));

        assertEquals("4.83", rules.normalizeMarketQuantity(new BigDecimal("4.83592410")).toPlainString());
    }

    @Test
    void stripsTrailingZerosFromStepScale() {
        SymbolRules rules = new SymbolRules(
                "BTCUSDT",
                new BigDecimal("0.10"),
                new BigDecimal("1000000"),
                new BigDecimal("0.10"),
                new BigDecimal("0.00100000"),
                new BigDecimal("1000"),
                new BigDecimal("0.00100000"),
                new BigDecimal("0.00100000"),
                new BigDecimal("1000"),
                new BigDecimal("0.00100000"),
                new BigDecimal("5"),
                new BigDecimal("0.05"));

        BigDecimal normalized = rules.normalizeMarketQuantity(new BigDecimal("0.00450450"));

        assertEquals("0.004", normalized.toPlainString());
        assertEquals(3, normalized.scale());
    }
}