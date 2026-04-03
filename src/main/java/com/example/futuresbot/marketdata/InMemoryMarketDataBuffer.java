package com.example.futuresbot.marketdata;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryMarketDataBuffer {
    private final int maxBarsPerSeries;
    private final Map<SymbolTimeframeKey, Deque<Candle>> series = new ConcurrentHashMap<>();

    public InMemoryMarketDataBuffer(int maxBarsPerSeries) {
        this.maxBarsPerSeries = maxBarsPerSeries;
    }

    public void seed(String symbol, CandleInterval interval, List<Candle> candles) {
        SymbolTimeframeKey key = new SymbolTimeframeKey(symbol, interval);
        Deque<Candle> deque = this.series.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            deque.clear();
            for (Candle candle : candles) {
                deque.addLast(candle);
                this.trim(deque);
            }
        }
    }

    public void apply(Candle candle) {
        SymbolTimeframeKey key = new SymbolTimeframeKey(candle.symbol(), candle.interval());
        Deque<Candle> deque = series.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            Candle last = deque.peekLast();
            if (last != null && last.openTime().equals(candle.openTime())) {
                deque.removeLast();
            }
            deque.addLast(candle);
            trim(deque);
        }
    }

    public List<Candle> closedBars(String symbol, CandleInterval interval) {
        SymbolTimeframeKey key = new SymbolTimeframeKey(symbol, interval);
        Deque<Candle> deque = series.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            return deque.stream().filter(Candle::closed).toList();
        }
    }

    private void trim(Deque<Candle> deque) {
        while (deque.size() > this.maxBarsPerSeries) {
            deque.removeFirst();
        }
    }
}