package com.example.futuresbot.exchange;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.domain.PositionSide;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class BinanceUserStreamService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BinanceUserStreamService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MAINNET_WS_BASE_URL = "wss://fstream.binance.com";
    private static final String TESTNET_WS_BASE_URL = "wss://fstream.binancefuture.com";

    private final AppConfig.ExchangeConfig config;
    private final BinanceHttpClient httpClient;
    private final HttpClient webSocketClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private volatile String listenKey;
    private volatile WebSocket webSocket;
    private volatile Consumer<UserStreamEvents.UserStreamEvent> consumer;

    public BinanceUserStreamService(AppConfig.ExchangeConfig config, BinanceHttpClient httpClient) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public synchronized void start(Consumer<UserStreamEvents.UserStreamEvent> consumer) {
        this.consumer = consumer;
        this.listenKey = createListenKey();
        connect();
        scheduler.scheduleAtFixedRate(this::keepAlive, 50, 50, TimeUnit.MINUTES);
    }

    private String createListenKey() {
        JsonNode node = httpClient.postApiKeyOnly("/fapi/v1/listenKey");
        String key = node.path("listenKey").asText();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Binance did not return a listenKey");
        }
        return key;
    }

    private void keepAlive() {
        try {
            httpClient.putApiKeyOnly("/fapi/v1/listenKey");
            log.debug("Refreshed Binance listenKey");
        } catch (Exception e) {
            log.warn("Failed to keepalive listenKey, recreating stream", e);
            restart();
        }
    }

    private synchronized void restart() {
        closeWebSocketOnly();
        this.listenKey = createListenKey();
        connect();
    }

    private void connect() {
        URI uri = buildUserStreamUri(listenKey);
        log.info("Connecting Binance user stream to {}", uri);
        this.webSocket = webSocketClient.newWebSocketBuilder()
                .buildAsync(uri, new UserStreamListener())
                .join();
    }

    private URI buildUserStreamUri(String activeListenKey) {
        String base = config.useTestnet() ? TESTNET_WS_BASE_URL : normalize(config.wsBaseUrl(), MAINNET_WS_BASE_URL);
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;

        if (normalized.endsWith("/private")) {
            String encoded = URLEncoder.encode(activeListenKey, StandardCharsets.UTF_8);
            return URI.create(
                    normalized + "/ws?listenKey=" + encoded + "&events=ORDER_TRADE_UPDATE/ACCOUNT_UPDATE/ALGO_UPDATE");
        }

        return URI.create(normalized + "/ws/" + activeListenKey);
    }

    private void handleText(String text) {
        try {
            JsonNode root = JSON.readTree(text);
            String eventType = root.path("e").asText();

            if ("ORDER_TRADE_UPDATE".equals(eventType)) {
                JsonNode order = root.path("o");
                PositionSide side = parsePositionSide(order.path("ps").asText());
                if (side != null) {
                    consumer.accept(new UserStreamEvents.OrderTradeUpdateEvent(
                            order.path("s").asText(),
                            side,
                            order.path("c").asText(),
                            order.path("X").asText(),
                            order.path("x").asText(),
                            Instant.ofEpochMilli(root.path("E").asLong())));
                }
                return;
            }

            if ("ACCOUNT_UPDATE".equals(eventType)) {
                JsonNode positions = root.path("a").path("P");
                if (positions.isArray()) {
                    for (JsonNode position : positions) {
                        PositionSide side = parsePositionSide(position.path("ps").asText());
                        if (side == null) {
                            continue;
                        }
                        consumer.accept(new UserStreamEvents.AccountPositionUpdateEvent(
                                position.path("s").asText(),
                                side,
                                new BigDecimal(position.path("pa").asText("0")),
                                position.path("ep").decimalValue(),
                                Instant.ofEpochMilli(root.path("E").asLong())));
                    }
                }
                return;
            }

            if ("ALGO_UPDATE".equals(eventType)) {
                JsonNode algo = root.path("o");
                PositionSide side = parsePositionSide(algo.path("ps").asText());
                if (side != null) {
                    consumer.accept(new UserStreamEvents.AlgoOrderUpdateEvent(
                            algo.path("s").asText(),
                            side,
                            algo.path("caid").asText(),
                            algo.path("X").asText(),
                            algo.path("o").asText(),
                            Instant.ofEpochMilli(root.path("E").asLong())));
                }
                return;
            }

            if ("listenKeyExpired".equals(eventType)) {
                log.warn("Binance listenKeyExpired received, restarting user stream");
                restart();
            }
        } catch (Exception e) {
            log.warn("Failed to parse user stream message: {}", text, e);
        }
    }

    private PositionSide parsePositionSide(String value) {
        if (value == null || value.isBlank() || "BOTH".equalsIgnoreCase(value)) {
            return null;
        }
        return PositionSide.valueOf(value.toUpperCase());
    }

    private void closeWebSocketOnly() {
        WebSocket current = this.webSocket;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join();
            } catch (Exception e) {
                log.debug("Ignoring websocket close error", e);
            }
        }
    }

    @Override
    public synchronized void close() {
        scheduler.shutdownNow();
        closeWebSocketOnly();
        if (listenKey != null && !listenKey.isBlank()) {
            try {
                httpClient.deleteApiKeyOnly("/fapi/v1/listenKey");
            } catch (Exception e) {
                log.debug("Ignoring listenKey close error", e);
            }
        }
    }

    private String normalize(String configured, String fallback) {
        return configured == null || configured.isBlank() ? fallback : configured;
    }

    private final class UserStreamListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            log.info("Binance user stream connected");
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handleText(buffer.toString());
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
            log.warn("Binance user stream closed: status={} reason={}", statusCode, reason);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("Binance user stream error", error);
        }
    }
}