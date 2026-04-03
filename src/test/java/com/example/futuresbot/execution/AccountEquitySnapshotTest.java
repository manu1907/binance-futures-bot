package com.example.futuresbot.execution;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountEquitySnapshotTest {

    @Test
    void usesMarginBalanceWhenPositive() {
        AccountEquitySnapshot snapshot = new AccountEquitySnapshot(
                BigDecimal.valueOf(5000),
                BigDecimal.valueOf(4000),
                BigDecimal.ZERO);

        assertEquals(BigDecimal.valueOf(5000),
                snapshot.sizingEquity(RiskCapitalMode.LIVE_EQUITY, BigDecimal.valueOf(200)));
        assertEquals(BigDecimal.valueOf(200),
                snapshot.sizingEquity(RiskCapitalMode.CAPPED_EQUITY, BigDecimal.valueOf(200)));
    }

    @Test
    void fallsBackToAvailableBalanceWhenMarginBalanceIsZero() {
        AccountEquitySnapshot snapshot = new AccountEquitySnapshot(
                BigDecimal.ZERO,
                BigDecimal.valueOf(5000),
                BigDecimal.ZERO);

        assertEquals(BigDecimal.valueOf(5000),
                snapshot.sizingEquity(RiskCapitalMode.LIVE_EQUITY, BigDecimal.valueOf(200)));
        assertEquals(BigDecimal.valueOf(200),
                snapshot.sizingEquity(RiskCapitalMode.CAPPED_EQUITY, BigDecimal.valueOf(200)));
    }

    @Test
    void returnsZeroWhenBothBalancesAreZero() {
        AccountEquitySnapshot snapshot = new AccountEquitySnapshot(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);

        assertEquals(BigDecimal.ZERO,
                snapshot.sizingEquity(RiskCapitalMode.LIVE_EQUITY, BigDecimal.valueOf(200)));
        assertEquals(BigDecimal.ZERO,
                snapshot.sizingEquity(RiskCapitalMode.CAPPED_EQUITY, BigDecimal.valueOf(200)));
    }
}