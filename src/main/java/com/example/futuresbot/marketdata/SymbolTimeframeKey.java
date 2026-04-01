package com.example.futuresbot.marketdata;

import java.util.Objects;

public record SymbolTimeframeKey(String symbol, CandleInterval interval) {
    public SymbolTimeframeKey {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(interval, "interval");
    }
}