package com.example.futuresbot.strategy;

import java.util.Optional;

public interface TradingStrategy {
    Optional<TradeSignal> evaluate(StrategyContext context);
}