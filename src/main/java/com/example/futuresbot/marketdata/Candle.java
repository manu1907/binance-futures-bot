package com.example.futuresbot.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

public record Candle(
        String symbol,
        CandleInterval interval,
        Instant openTime,
        Instant closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        boolean closed
) {}