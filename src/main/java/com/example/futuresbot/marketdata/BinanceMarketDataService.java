package com.example.futuresbot.marketdata;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.exchange.BinanceHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public final class BinanceMarketDataService implements MarketDataService {
    private static final Logger log = LoggerFactory.getLogger(BinanceMarketDataService.class);
    private static final String MAINNET_WS_BASE_URL = "wss://fstream.binance.com";
    private static final String TESTNET_WS_BASE_URL = "wss://fstream.binancefuture.com";
    private static final int MAX_KLINES_PER_REQUEST = 1500;

    private final AppConfig.ExchangeConfig config;
    private final BinanceHttpClient httpClient;
    private final HttpClient webSocketClient = HttpClient.newHttpClient();

    private volatile WebSocket webSocket;
    private volatile Consumer<Candle> consumer;

    public BinanceMarketDataService(AppConfig.ExchangeConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = new BinanceHttpClient(config);
    }

    @Override
    public List<Candle> loadHistoricalKlines(String symbol, CandleInterval interval, int limit) {
        JsonNode json = httpClient.getPublic("/fapi/v1/klines", Map.of(
                "symbol", symbol,
                "interval", interval.code(),
                "limit", Integer.toString(limit)));

        List<Candle> candles = new ArrayList<>();
        if (!json.isArray()) {
            return candles;
        }

        for (JsonNode row : json) {
            candles.add(parseRestKline(symbol, interval, row));
        }

        return candles;
    }

    @Override
    public List<Candle> loadHistoricalKlines(
            String symbol,
            CandleInterval interval,
            Instant startInclusive,
            Instant endExclusive) {
        List<Candle> candles = new ArrayList<>();
        long cursor = startInclusive.toEpochMilli();
        long end = endExclusive.toEpochMilli();

        while (cursor < end) {
            JsonNode json = httpClient.getPublic("/fapi/v1/klines", Map.of(
                    "symbol", symbol,
                    "interval", interval.code(),
                    "startTime", Long.toString(cursor),
                    "endTime", Long.toString(end),
                    "limit", Integer.toString(MAX_KLINES_PER_REQUEST)));

            if (!json.isArray() || json.isEmpty()) {
                break;
            }

            long nextCursor = cursor;
            for (JsonNode row : json) {
                Candle candle = parseRestKline(symbol, interval, row);
                candles.add(candle);
                nextCursor = Math.max(nextCursor, candle.closeTime().toEpochMilli() + 1L);
            }

            if (nextCursor <= cursor) {
                break;
            }
            cursor = nextCursor;

            if (json.size() < MAX_KLINES_PER_REQUEST) {
                break;
            }
        }

        return candles;
    }

    @Override
    public synchronized void connectKlineStreams(List<String> symbols, List<CandleInterval> intervals,
            Consumer<Candle> consumer) {
        this.consumer = consumer;
        String base = config.useTestnet() ? TESTNET_WS_BASE_URL : normalize(config.wsBaseUrl(), MAINNET_WS_BASE_URL);
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;

        String streamList = symbols.stream()
                .flatMap(symbol -> intervals.stream()
                        .map(interval -> symbol.toLowerCase() + "@kline_" + interval.code()))
                .reduce((left, right) -> left + "/" + right)
                .orElseThrow(() -> new IllegalArgumentException("At least one symbol and interval are required"));

        URI uri = URI.create(normalized + "/stream?streams=" + streamList);
        log.info("Connecting market data stream to {}", uri);
        this.webSocket = webSocketClient.newWebSocketBuilder()
                .buildAsync(uri, new KlineStreamListener())
                .join();
    }

    @Override
    public synchronized void close() {
        WebSocket current = this.webSocket;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join();
            } catch (Exception e) {
                log.debug("Ignoring market websocket close error", e);
            }
        }
    }

    private void handleMessage(String text) {
        try {
            JsonNode root = JsonMapper.builder().build().readTree(text);
            JsonNode payload = root.has("data") ? root.path("data") : root;
            JsonNode kline = payload.path("k");
            if (kline.isMissingNode()) {
                return;
            }

            Candle candle = new Candle(
                    payload.path("s").asText(),
                    CandleInterval.fromCode(kline.path("i").asText()),
                    Instant.ofEpochMilli(kline.path("t").asLong()),
                    Instant.ofEpochMilli(kline.path("T").asLong()),
                    new BigDecimal(kline.path("o").asText()),
                    new BigDecimal(kline.path("h").asText()),
                    new BigDecimal(kline.path("l").asText()),
                    new BigDecimal(kline.path("c").asText()),
                    new BigDecimal(kline.path("v").asText()),
                    kline.path("x").asBoolean(false));
            consumer.accept(candle);
        } catch (Exception e) {
            log.warn("Failed to parse market data message: {}", text, e);
        }
    }

    private Candle parseRestKline(String symbol, CandleInterval interval, JsonNode row) {
        return new Candle(
                symbol,
                interval,
                Instant.ofEpochMilli(row.get(0).asLong()),
                Instant.ofEpochMilli(row.get(6).asLong()),
                new BigDecimal(row.get(1).asText()),
                new BigDecimal(row.get(2).asText()),
                new BigDecimal(row.get(3).asText()),
                new BigDecimal(row.get(4).asText()),
                new BigDecimal(row.get(5).asText()),
                true);
    }

    private String normalize(String configured, String fallback) {
        return configured == null || configured.isBlank() ? fallback : configured;
    }

    private final class KlineStreamListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            log.info("Market data stream connected");
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handleMessage(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return webSocket.sendPong(message);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("Market data stream closed: status={} reason={}", statusCode, reason);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("Market data stream error", error);
        }
    }
}