package com.example.futuresbot.exchange;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.domain.PositionSide;
import com.example.futuresbot.domain.PositionSnapshot;
import com.example.futuresbot.execution.AccountEquitySnapshot;
import com.example.futuresbot.strategy.SignalType;
import com.example.futuresbot.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class BinanceRestGateway implements ExchangeGateway {
    private static final Logger log = LoggerFactory.getLogger(BinanceRestGateway.class);

    private final AppConfig config;
    private final BinanceHttpClient httpClient;
    private final ConcurrentHashMap<String, SymbolRules> symbolRulesCache = new ConcurrentHashMap<>();
    private BinanceUserStreamService userStreamService;

    public BinanceRestGateway(AppConfig config) {
        this.config = config;
        this.httpClient = new BinanceHttpClient(config.exchange());
    }

    @Override
    public ExchangeSnapshot currentSnapshot() {
        List<PositionSnapshot> positions = new ArrayList<>();
        List<OpenOrderSnapshot> openOrders = new ArrayList<>();
        List<AlgoOrderSnapshot> openAlgoOrders = new ArrayList<>();

        for (String symbol : this.config.trading().symbols()) {
            ExchangeSnapshot perSymbol = this.currentSnapshot(symbol);
            positions.addAll(perSymbol.positions());
            openOrders.addAll(perSymbol.openOrders());
            openAlgoOrders.addAll(perSymbol.openAlgoOrders());
        }

        return new ExchangeSnapshot(positions, openOrders, openAlgoOrders);
    }

    @Override
    public ExchangeSnapshot currentSnapshot(String symbol) {
        List<OpenOrderSnapshot> openOrders = parseOpenOrders(
                this.httpClient.getSigned("/fapi/v1/openOrders", Map.of("symbol", symbol)));
        List<AlgoOrderSnapshot> openAlgoOrders = parseAlgoOrders(
                this.httpClient.getSigned("/fapi/v1/openAlgoOrders", Map.of("symbol", symbol)));
        List<PositionSnapshot> positions = parsePositions(
                this.httpClient.getSigned("/fapi/v3/positionRisk", Map.of("symbol", symbol)),
                openOrders,
                openAlgoOrders);
        return new ExchangeSnapshot(positions, openOrders, openAlgoOrders);
    }

    @Override
    public boolean isHedgeModeEnabled() {
        JsonNode node = httpClient.getSigned("/fapi/v1/positionSide/dual", Map.of());
        return node.path("dualSidePosition").asBoolean(false);
    }

    @Override
    public AccountEquitySnapshot accountEquity() {
        JsonNode node = httpClient.getSigned("/fapi/v2/account", Map.of());
        return new AccountEquitySnapshot(
                JsonUtils.decimal(node, "totalMarginBalance"),
                JsonUtils.decimal(node, "availableBalance"),
                JsonUtils.decimal(node, "totalUnrealizedProfit"));
    }

    @Override
    public SymbolRules symbolRules(String symbol) {
        SymbolRules cached = symbolRulesCache.get(symbol);
        if (cached != null) {
            return cached;
        }

        JsonNode exchangeInfo = httpClient.getPublic("/fapi/v1/exchangeInfo", Map.of());
        JsonNode symbols = exchangeInfo.path("symbols");
        if (!symbols.isArray()) {
            throw new IllegalStateException("exchangeInfo symbols response was not an array");
        }

        for (JsonNode node : symbols) {
            String currentSymbol = node.path("symbol").asText();
            symbolRulesCache.put(currentSymbol, parseSymbolRules(node));
        }

        SymbolRules loaded = symbolRulesCache.get(symbol);
        if (loaded == null) {
            throw new IllegalArgumentException("No exchange rules found for symbol " + symbol);
        }
        return loaded;
    }

    @Override
    public String placeEntryMarketOrder(String symbol, SignalType signalType, BigDecimal quantity,
            String clientOrderId) {
        JsonNode response = httpClient.postSigned("/fapi/v1/order", Map.of(
                "symbol", symbol,
                "side", entrySide(signalType),
                "positionSide", entryPositionSide(signalType),
                "type", "MARKET",
                "quantity", quantity.toPlainString(),
                "newClientOrderId", clientOrderId,
                "newOrderRespType", "RESULT"));
        return response.path("clientOrderId").asText(clientOrderId);
    }

    @Override
    public String placeProtectiveAlgoOrder(
            String symbol,
            SignalType signalType,
            BigDecimal triggerPrice,
            boolean takeProfit,
            String clientAlgoId) {
        JsonNode response = httpClient.postSigned("/fapi/v1/algoOrder", Map.of(
                "algoType", "CONDITIONAL",
                "symbol", symbol,
                "side", exitSide(signalType),
                "positionSide", entryPositionSide(signalType),
                "type", takeProfit ? "TAKE_PROFIT_MARKET" : "STOP_MARKET",
                "triggerPrice", triggerPrice.toPlainString(),
                "workingType", "CONTRACT_PRICE",
                "closePosition", "true",
                "clientAlgoId", clientAlgoId,
                "newOrderRespType", "ACK"));
        return response.path("clientAlgoId").asText(clientAlgoId);
    }

    @Override
    public void cancelAllOpenOrders(String symbol) {
        httpClient.deleteSigned("/fapi/v1/allOpenOrders", Map.of("symbol", symbol));
    }

    @Override
    public void cancelAllOpenAlgoOrders(String symbol) {
        httpClient.deleteSigned("/fapi/v1/algoOpenOrders", Map.of("symbol", symbol));
    }

    @Override
    public String closePositionMarket(PositionKey key, BigDecimal quantity, String clientOrderId) {
        JsonNode response = httpClient.postSigned("/fapi/v1/order", Map.of(
                "symbol", key.symbol(),
                "side", key.side() == PositionSide.LONG ? "SELL" : "BUY",
                "positionSide", key.side().name(),
                "type", "MARKET",
                "quantity", quantity.toPlainString(),
                "newClientOrderId", clientOrderId,
                "newOrderRespType", "RESULT"));
        return response.path("clientOrderId").asText(clientOrderId);
    }

    @Override
    public synchronized void connectUserStream(Consumer<UserStreamEvents.UserStreamEvent> consumer) {
        if (this.userStreamService != null) {
            return;
        }
        this.userStreamService = new BinanceUserStreamService(this.config.exchange(), this.httpClient);
        this.userStreamService.start(consumer);
    }

    @Override
    public void setLeverage(String symbol, int leverage) {
        this.httpClient.postSigned("/fapi/v1/leverage", Map.of(
                "symbol", symbol,
                "leverage", String.valueOf(leverage)));
        log.info("Leverage set: symbol={} leverage={}x", symbol, leverage);
    }

    @Override
    public synchronized void close() {
        if (this.userStreamService != null) {
            this.userStreamService.close();
            this.userStreamService = null;
        }
    }

    @Override
    public List<IncomeRecord> incomeHistory(String symbol, Instant startInclusive, Instant endInclusive) {
        JsonNode json = httpClient.getSigned("/fapi/v1/income", Map.of(
                "symbol", symbol,
                "startTime", Long.toString(startInclusive.toEpochMilli()),
                "endTime", Long.toString(endInclusive.toEpochMilli()),
                "limit", "1000"));

        List<IncomeRecord> records = new ArrayList<>();
        if (!json.isArray()) {
            return records;
        }

        for (JsonNode node : json) {
            records.add(new IncomeRecord(
                    node.path("symbol").asText(),
                    node.path("incomeType").asText(),
                    node.path("income").decimalValue(),
                    node.path("asset").asText(),
                    Instant.ofEpochMilli(node.path("time").asLong()),
                    node.path("info").asText("")));
        }

        return records;
    }

    private SymbolRules parseSymbolRules(JsonNode node) {
        BigDecimal minPrice = BigDecimal.ZERO;
        BigDecimal maxPrice = new BigDecimal("999999999");
        BigDecimal tickSize = BigDecimal.ONE;
        BigDecimal lotMinQty = BigDecimal.ZERO;
        BigDecimal lotMaxQty = new BigDecimal("999999999");
        BigDecimal lotStep = BigDecimal.ONE;
        BigDecimal marketMinQty = BigDecimal.ZERO;
        BigDecimal marketMaxQty = new BigDecimal("999999999");
        BigDecimal marketStep = BigDecimal.ONE;
        BigDecimal minNotional = BigDecimal.ZERO;
        BigDecimal triggerProtect = node.path("triggerProtect").decimalValue();

        JsonNode filters = node.path("filters");
        if (filters.isArray()) {
            for (JsonNode filter : filters) {
                String filterType = filter.path("filterType").asText();
                switch (filterType) {
                    case "PRICE_FILTER" -> {
                        minPrice = filter.path("minPrice").decimalValue();
                        maxPrice = filter.path("maxPrice").decimalValue();
                        tickSize = filter.path("tickSize").decimalValue();
                    }
                    case "LOT_SIZE" -> {
                        lotMinQty = filter.path("minQty").decimalValue();
                        lotMaxQty = filter.path("maxQty").decimalValue();
                        lotStep = filter.path("stepSize").decimalValue();
                    }
                    case "MARKET_LOT_SIZE" -> {
                        marketMinQty = filter.path("minQty").decimalValue();
                        marketMaxQty = filter.path("maxQty").decimalValue();
                        marketStep = filter.path("stepSize").decimalValue();
                    }
                    case "MIN_NOTIONAL" -> minNotional = filter.path("notional").decimalValue();
                    default -> {
                        // ignore
                    }
                }
            }
        }

        return new SymbolRules(
                node.path("symbol").asText(),
                minPrice,
                maxPrice,
                tickSize,
                lotMinQty,
                lotMaxQty,
                lotStep,
                marketMinQty,
                marketMaxQty,
                marketStep,
                minNotional,
                triggerProtect);
    }

    private List<PositionSnapshot> parsePositions(
            JsonNode json,
            List<OpenOrderSnapshot> openOrders,
            List<AlgoOrderSnapshot> openAlgoOrders) {
        List<PositionSnapshot> snapshots = new ArrayList<>();
        if (!json.isArray()) {
            return snapshots;
        }

        ExchangeSnapshot snapshot = new ExchangeSnapshot(List.of(), openOrders, openAlgoOrders);

        for (JsonNode node : json) {
            String sideText = node.path("positionSide").asText();
            if ("BOTH".equals(sideText)) {
                continue;
            }
            PositionSide side = PositionSide.valueOf(sideText);
            PositionKey key = new PositionKey(node.path("symbol").asText(), side);
            snapshots.add(new PositionSnapshot(
                    node.path("symbol").asText(),
                    side,
                    node.path("positionAmt").decimalValue().abs(),
                    node.path("entryPrice").decimalValue(),
                    node.path("breakEvenPrice").decimalValue(),
                    node.path("unRealizedProfit").decimalValue(),
                    node.path("liquidationPrice").decimalValue(),
                    snapshot.hasProtectiveStop(key),
                    snapshot.hasTakeProfit(key),
                    Instant.ofEpochMilli(node.path("updateTime").asLong(0L))));
        }
        return snapshots;
    }

    private List<OpenOrderSnapshot> parseOpenOrders(JsonNode json) {
        List<OpenOrderSnapshot> orders = new ArrayList<>();
        if (!json.isArray()) {
            return orders;
        }

        for (JsonNode node : json) {
            String positionSide = node.path("positionSide").asText();
            if ("BOTH".equals(positionSide)) {
                continue;
            }
            orders.add(new OpenOrderSnapshot(
                    node.path("symbol").asText(),
                    PositionSide.valueOf(positionSide),
                    node.path("clientOrderId").asText(),
                    node.path("side").asText(),
                    node.path("type").asText(),
                    node.path("reduceOnly").asBoolean(false)));
        }
        return orders;
    }

    private List<AlgoOrderSnapshot> parseAlgoOrders(JsonNode json) {
        List<AlgoOrderSnapshot> orders = new ArrayList<>();
        if (!json.isArray()) {
            return orders;
        }

        for (JsonNode node : json) {
            String positionSide = node.path("positionSide").asText();
            if (positionSide == null || positionSide.isBlank() || "BOTH".equals(positionSide)) {
                continue;
            }
            orders.add(new AlgoOrderSnapshot(
                    node.path("symbol").asText(),
                    PositionSide.valueOf(positionSide),
                    node.path("clientAlgoId").asText(),
                    node.path("algoType").asText(),
                    node.path("orderType").asText()));
        }
        return orders;
    }

    private String entrySide(SignalType signalType) {
        return signalType == SignalType.LONG_ENTRY ? "BUY" : "SELL";
    }

    private String exitSide(SignalType signalType) {
        return signalType == SignalType.LONG_ENTRY ? "SELL" : "BUY";
    }

    private String entryPositionSide(SignalType signalType) {
        return signalType == SignalType.LONG_ENTRY ? "LONG" : "SHORT";
    }
}