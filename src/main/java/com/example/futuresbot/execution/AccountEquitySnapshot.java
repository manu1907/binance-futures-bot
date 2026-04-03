package com.example.futuresbot.execution;

import java.math.BigDecimal;

public record AccountEquitySnapshot(
        BigDecimal marginBalanceUsd,
        BigDecimal availableBalanceUsd,
        BigDecimal unrealizedPnlUsd) {

    public BigDecimal sizingEquity(RiskCapitalMode mode, BigDecimal configuredCapitalUsd) {
        BigDecimal positiveMarginBalance = marginBalanceUsd == null ? BigDecimal.ZERO : marginBalanceUsd.max(BigDecimal.ZERO);
        BigDecimal positiveAvailableBalance = availableBalanceUsd == null ? BigDecimal.ZERO
                : availableBalanceUsd.max(BigDecimal.ZERO);

        BigDecimal liveEquityUsd = positiveMarginBalance.max(positiveAvailableBalance);

        return switch (mode) {
            case LIVE_EQUITY -> liveEquityUsd;
            case CAPPED_EQUITY -> configuredCapitalUsd.min(liveEquityUsd);
        };
    }
}