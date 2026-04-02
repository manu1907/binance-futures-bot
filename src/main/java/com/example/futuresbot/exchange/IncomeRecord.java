package com.example.futuresbot.exchange;

import java.math.BigDecimal;
import java.time.Instant;

public record IncomeRecord(
        String symbol,
        String incomeType,
        BigDecimal income,
        String asset,
        Instant time,
        String info) {
}