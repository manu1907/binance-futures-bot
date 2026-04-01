package com.example.futuresbot.strategy;

import com.example.futuresbot.marketdata.Candle;
import com.example.futuresbot.marketdata.CandleInterval;
import com.example.futuresbot.marketdata.InMemoryMarketDataBuffer;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record StrategyContext(String symbol, Map<CandleInterval, List<Candle>> candlesByInterval) {

    public List<Candle> bars(CandleInterval interval) {
        return candlesByInterval.getOrDefault(interval, List.of());
    }

    public static StrategyContext fromBuffer(
            String symbol,
            InMemoryMarketDataBuffer buffer,
            List<CandleInterval> intervals
    ) {
        Map<CandleInterval, List<Candle>> values = new EnumMap<>(CandleInterval.class);
        for (CandleInterval interval : intervals) {
            values.put(interval, buffer.closedBars(symbol, interval));
        }
        return new StrategyContext(symbol, values);
    }
}