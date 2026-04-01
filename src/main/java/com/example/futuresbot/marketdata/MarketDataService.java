package com.example.futuresbot.marketdata;

import java.util.List;
import java.util.function.Consumer;

public interface MarketDataService extends AutoCloseable {
    List<Candle> loadHistoricalKlines(String symbol, CandleInterval interval, int limit);
    void connectKlineStreams(List<String> symbols, List<CandleInterval> intervals, Consumer<Candle> consumer);

    @Override
    default void close() {
        // default no-op
    }
}