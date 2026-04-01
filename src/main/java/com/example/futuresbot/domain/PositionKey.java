package com.example.futuresbot.domain;

import java.util.Objects;

public record PositionKey(String symbol, PositionSide side) {
    public PositionKey {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(side, "side");
    }
}