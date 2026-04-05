package com.example.futuresbot.execution;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountEquitySnapshotTest {

    @Test
    void sizingEquity_uses_live_equity_for_live_equity_mode() {
        AccountEquitySnapshot snapshot = new AccountEquitySnapshot(
                BigDecimal.valueOf(5034.41201928),
                BigDecimal.valueOf(1164.59183152),
                BigDecimal.ZERO);

        BigDecimal sizingEquity = snapshot.sizingEquity(
                RiskCapitalMode.LIVE_EQUITY,
                BigDecimal.valueOf(200.0));

        assertEquals(new BigDecimal("5034.41201928"), sizingEquity);
    }

    @Test
    void sizingEquity_caps_live_equity_for_capped_equity_mode() {
        AccountEquitySnapshot snapshot = new AccountEquitySnapshot(
                BigDecimal.valueOf(5034.41201928),
                BigDecimal.valueOf(1164.59183152),
                BigDecimal.ZERO);

        BigDecimal sizingEquity = snapshot.sizingEquity(
                RiskCapitalMode.CAPPED_EQUITY,
                BigDecimal.valueOf(200.0));

        assertEquals(new BigDecimal("200.0"), sizingEquity);
    }

    @Test
    void sizingEquity_handles_negative_or_null_balances_as_zero() {
        AccountEquitySnapshot snapshot = new AccountEquitySnapshot(
                BigDecimal.valueOf(-10.0),
                null,
                BigDecimal.ZERO);

        BigDecimal liveEquity = snapshot.sizingEquity(
                RiskCapitalMode.LIVE_EQUITY,
                BigDecimal.valueOf(200.0));

        BigDecimal cappedEquity = snapshot.sizingEquity(
                RiskCapitalMode.CAPPED_EQUITY,
                BigDecimal.valueOf(200.0));

        assertEquals(BigDecimal.ZERO, liveEquity);
        assertEquals(BigDecimal.ZERO, cappedEquity);
    }
}