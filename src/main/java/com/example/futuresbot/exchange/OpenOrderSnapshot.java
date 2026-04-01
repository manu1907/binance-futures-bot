package com.example.futuresbot.exchange;

import com.example.futuresbot.domain.PositionSide;

public record OpenOrderSnapshot(
        String symbol,
        PositionSide positionSide,
        String clientOrderId,
        String side,
        String type,
        boolean reduceOnly) {
}