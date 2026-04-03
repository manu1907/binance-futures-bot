package com.example.futuresbot.exchange;

import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.execution.AccountEquitySnapshot;
import com.example.futuresbot.strategy.SignalType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

public interface ExchangeGateway extends AutoCloseable {
    ExchangeSnapshot currentSnapshot();

    ExchangeSnapshot currentSnapshot(String symbol);

    boolean isHedgeModeEnabled();

    AccountEquitySnapshot accountEquity();

    SymbolRules symbolRules(String symbol);

    String placeEntryMarketOrder(String symbol, SignalType signalType, BigDecimal quantity, String clientOrderId);

    String placeProtectiveAlgoOrder(
            String symbol,
            SignalType signalType,
            BigDecimal triggerPrice,
            boolean takeProfit,
            String clientAlgoId);

    void cancelAllOpenOrders(String symbol);

    void cancelAllOpenAlgoOrders(String symbol);

    String closePositionMarket(PositionKey key, BigDecimal quantity, String clientOrderId);

    List<IncomeRecord> incomeHistory(String symbol, Instant startInclusive, Instant endInclusive);

    void connectUserStream(Consumer<UserStreamEvents.UserStreamEvent> consumer);

    void setLeverage(String symbol, int leverage);

    void cancelAlgoOrder(String clientAlgoId);

    @Override
    default void close() {
        // default no-op
    }
}