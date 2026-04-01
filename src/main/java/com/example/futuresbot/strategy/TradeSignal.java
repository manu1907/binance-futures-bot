package com.example.futuresbot.strategy;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeSignal(
        String symbol,
        SignalType type,
        BigDecimal referencePrice,
        Instant generatedAt,
        String reason
) {}