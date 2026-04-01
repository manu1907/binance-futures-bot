package com.example.futuresbot.exchange;

import com.example.futuresbot.domain.PositionSide;

import java.math.BigDecimal;
import java.time.Instant;

public final class UserStreamEvents {
        private UserStreamEvents() {
        }

        public sealed interface UserStreamEvent
                        permits OrderTradeUpdateEvent, AccountPositionUpdateEvent, AlgoOrderUpdateEvent {
        }

        public record OrderTradeUpdateEvent(
                        String symbol,
                        PositionSide positionSide,
                        String clientOrderId,
                        String orderStatus,
                        String executionType,
                        Instant eventTime) implements UserStreamEvent {
        }

        public record AccountPositionUpdateEvent(
                        String symbol,
                        PositionSide positionSide,
                        BigDecimal quantity,
                        BigDecimal entryPrice,
                        Instant eventTime) implements UserStreamEvent {
        }

        public record AlgoOrderUpdateEvent(
                        String symbol,
                        PositionSide positionSide,
                        String clientAlgoId,
                        String algoStatus,
                        String orderType,
                        Instant eventTime) implements UserStreamEvent {
        }
}