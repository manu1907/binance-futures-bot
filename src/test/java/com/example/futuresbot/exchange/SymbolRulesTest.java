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

        assertEquals(0, rules.normalizeMarketQuantity(new BigDecimal("0.0059"))
                .compareTo(new BigDecimal("0.005")));
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

        assertEquals(0, rules.normalizePrice(new BigDecimal("65000.19"))
                .compareTo(new BigDecimal("65000.1")));
    }

    @Test
    void stripsTrailingZerosFromMarketStepSizeBeforeReturningQuantity() {
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

    @Test
    void stripsTrailingZerosFromTickSizeBeforeReturningPrice() {
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

        BigDecimal normalized = rules.normalizePrice(new BigDecimal("65000.19"));

        assertEquals("65000.1", normalized.toPlainString());
        assertEquals(1, normalized.scale());
    }
}