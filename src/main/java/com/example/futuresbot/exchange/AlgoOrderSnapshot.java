package com.example.futuresbot.exchange;

import com.example.futuresbot.domain.PositionSide;

public record AlgoOrderSnapshot(
        String symbol,
        PositionSide positionSide,
        String clientAlgoId,
        String algoType,
        String orderType) {
}