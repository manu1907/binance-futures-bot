package com.example.futuresbot.runtime;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.domain.ManagedPosition;
import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.domain.PositionSide;
import com.example.futuresbot.domain.PositionSnapshot;
import com.example.futuresbot.execution.AccountEquitySnapshot;
import com.example.futuresbot.execution.ExecutionPlanner;
import com.example.futuresbot.execution.ExecutionService;
import com.example.futuresbot.execution.GuardrailDecision;
import com.example.futuresbot.execution.LifecycleDecision;
import com.example.futuresbot.execution.OrderPlan;
import com.example.futuresbot.execution.PlacementResult;
import com.example.futuresbot.execution.PositionLifecycleManager;
import com.example.futuresbot.execution.RiskSizer;
import com.example.futuresbot.execution.TradeGuardrails;
import com.example.futuresbot.exchange.BinanceRestGateway;
import com.example.futuresbot.exchange.ExchangeGateway;
import com.example.futuresbot.exchange.ExchangeSnapshot;
import com.example.futuresbot.exchange.UserStreamEvents;
import com.example.futuresbot.marketdata.BinanceMarketDataService;
import com.example.futuresbot.marketdata.Candle;
import com.example.futuresbot.marketdata.CandleInterval;
import com.example.futuresbot.marketdata.InMemoryMarketDataBuffer;
import com.example.futuresbot.marketdata.MarketDataService;
import com.example.futuresbot.reconcile.ManualInterventionDetector;
import com.example.futuresbot.reconcile.PositionReconciler;
import com.example.futuresbot.reconcile.ReconcileDecision;
import com.example.futuresbot.risk.DailyRiskManager;
import com.example.futuresbot.risk.RiskGateDecision;
import com.example.futuresbot.risk.TradeJournalCsvWriter;
import com.example.futuresbot.strategy.SignalType;
import com.example.futuresbot.strategy.StrategyContext;
import com.example.futuresbot.strategy.TradeSignal;
import com.example.futuresbot.strategy.TradingStrategy;
import com.example.futuresbot.strategy.elder.ElderTripleScreenStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class BotRuntime {
    private static final Logger log = LoggerFactory.getLogger(BotRuntime.class);
    private static final List<CandleInterval> STRATEGY_INTERVALS = List.of(
            CandleInterval.HOUR_4,
            CandleInterval.HOUR_1,
            CandleInterval.MINUTES_15);

    private final AppConfig config;
    private final ExchangeGateway exchangeGateway;
    private final MarketDataService marketDataService;
    private final PositionReconciler reconciler = new PositionReconciler();
    private final ManualInterventionDetector manualDetector = new ManualInterventionDetector();
    private final Map<PositionKey, ManagedPosition> managedPositions = new ConcurrentHashMap<>();
    private final Map<PositionKey, OrderPlan> latestAcceptedPlanByKey = new ConcurrentHashMap<>();
    private final Set<String> knownBotClientOrderIds = ConcurrentHashMap.newKeySet();
    private final Set<String> knownBotClientAlgoIds = ConcurrentHashMap.newKeySet();
    private final InMemoryMarketDataBuffer marketDataBuffer = new InMemoryMarketDataBuffer(500);
    private final TradingStrategy strategy = new ElderTripleScreenStrategy();
    private final ExecutionPlanner executionPlanner = new ExecutionPlanner(new RiskSizer());
    private final ExecutionService executionService = new ExecutionService();
    private final TradeGuardrails tradeGuardrails = new TradeGuardrails();
    private final PositionLifecycleManager lifecycleManager = new PositionLifecycleManager();
    private final Map<String, Instant> lastSignalBarCloseBySymbol = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastTradeActivityBySymbol = new ConcurrentHashMap<>();
    private final ScheduledExecutorService riskScheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicReference<Instant> lastMarketDataEventAt = new AtomicReference<>(Instant.EPOCH);
    private final DailyRiskManager dailyRiskManager;

    private volatile String tradingHaltReason;

    public BotRuntime(AppConfig config) {
        this(config, new BinanceRestGateway(config), new BinanceMarketDataService(config.exchange()));
    }

    public BotRuntime(AppConfig config, ExchangeGateway exchangeGateway, MarketDataService marketDataService) {
        this.config = config;
        this.exchangeGateway = exchangeGateway;
        this.marketDataService = marketDataService;
        this.dailyRiskManager = new DailyRiskManager(
                config.trading().maxDailyDrawdownPct(),
                config.trading().maxConsecutiveLosses(),
                new TradeJournalCsvWriter(config.trading().journalCsvPath()));
    }

    public void start() {
        log.info("Runtime started. symbols={} adoptionMode={}",
                config.trading().symbols(), config.trading().adoptionMode());

        verifyExchangeSetup();

        AccountEquitySnapshot startupEquity = exchangeGateway.accountEquity();
        dailyRiskManager.initialize(startupEquity.marginBalanceUsd(), Instant.now());

        ExchangeSnapshot startupSnapshot = exchangeGateway.currentSnapshot();
        logStartupRecoverySummary(startupSnapshot);
        syncSnapshot(startupSnapshot, false);

        exchangeGateway.connectUserStream(this::onUserStreamEvent);
        bootstrapMarketData();
        lastMarketDataEventAt.set(Instant.now());
        marketDataService.connectKlineStreams(config.trading().symbols(), STRATEGY_INTERVALS, this::onCandle);

        riskScheduler.scheduleAtFixedRate(this::runRiskMonitor, 15, 15, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
        log.info("Exchange wiring complete. Bot is now reconciling live exchange state.");
    }

    public void stop() {
        riskScheduler.shutdownNow();
        try {
            marketDataService.close();
        } catch (Exception e) {
            log.warn("Error while closing market data service", e);
        }
        try {
            exchangeGateway.close();
        } catch (Exception e) {
            log.warn("Error while closing exchange gateway", e);
        }
    }

    private void verifyExchangeSetup() {
        if (config.exchange().hedgeModeRequired() && !exchangeGateway.isHedgeModeEnabled()) {
            throw new IllegalStateException(
                    "Bot requires Binance hedge mode, but account is currently in one-way mode");
        }
    }

    private void logStartupRecoverySummary(ExchangeSnapshot snapshot) {
        long nonFlatPositions = snapshot.positions().stream().filter(position -> !position.isFlat()).count();
        if (nonFlatPositions > 0 || !snapshot.openOrders().isEmpty() || !snapshot.openAlgoOrders().isEmpty()) {
            log.warn(
                    "Startup recovery detected positions={} openOrders={} openAlgoOrders={}",
                    nonFlatPositions,
                    snapshot.openOrders().size(),
                    snapshot.openAlgoOrders().size());
        }
    }

    private void bootstrapMarketData() {
        for (String symbol : config.trading().symbols()) {
            for (CandleInterval interval : STRATEGY_INTERVALS) {
                List<Candle> candles = marketDataService.loadHistoricalKlines(symbol, interval, 250);
                marketDataBuffer.seed(symbol, interval, candles);
                log.info("Bootstrapped {} candles for {} {}", candles.size(), symbol, interval.code());
            }
        }
    }

    private void onCandle(Candle candle) {
        lastMarketDataEventAt.set(Instant.now());
        marketDataBuffer.apply(candle);

        if (!candle.closed() || candle.interval() != CandleInterval.MINUTES_15) {
            return;
        }

        Instant previousProcessed = lastSignalBarCloseBySymbol.put(candle.symbol(), candle.closeTime());
        if (candle.closeTime().equals(previousProcessed)) {
            return;
        }

        StrategyContext context = StrategyContext.fromBuffer(candle.symbol(), marketDataBuffer, STRATEGY_INTERVALS);
        strategy.evaluate(context).ifPresent(signal -> onSignal(signal, context));
    }

    private void onSignal(TradeSignal signal, StrategyContext context) {
        if (tradingHaltReason != null) {
            log.warn("Trading halted. Ignoring signal symbol={} type={} reason={}",
                    signal.symbol(), signal.type(), tradingHaltReason);
            return;
        }

        AccountEquitySnapshot equity = exchangeGateway.accountEquity();
        RiskGateDecision riskDecision = dailyRiskManager.evaluateCanTrade(equity.marginBalanceUsd(), Instant.now());
        if (!riskDecision.allowed()) {
            triggerTradingHalt(riskDecision.reason(), false);
            log.warn("Risk gate blocked signal symbol={} type={} reason={}",
                    signal.symbol(), signal.type(), riskDecision.reason());
            return;
        }

        OrderPlan plan = executionPlanner.plan(signal, context, equity, config.trading());

        if (!plan.accepted()) {
            log.info("DRY-RUN plan rejected symbol={} type={} reason={}",
                    signal.symbol(), signal.type(), plan.rejectionReason());
            return;
        }

        ExchangeSnapshot symbolSnapshot = exchangeGateway.currentSnapshot(signal.symbol());
        PositionKey desiredKey = positionKey(signal.type(), signal.symbol());

        LifecycleDecision lifecycleDecision = lifecycleManager.evaluate(
                desiredKey,
                symbolSnapshot,
                config.trading().oppositeSignalPolicy());

        switch (lifecycleDecision.action()) {
            case IGNORE_SIGNAL -> {
                log.info("Lifecycle ignored signal symbol={} type={} reason={}",
                        signal.symbol(), signal.type(), lifecycleDecision.reason());
                return;
            }
            case FLATTEN_AND_WAIT -> {
                if (config.trading().dryRun()) {
                    log.info("DRY-RUN lifecycle flatten symbol={} type={} reason={}",
                            signal.symbol(), signal.type(), lifecycleDecision.reason());
                } else {
                    flattenOppositePosition(signal, symbolSnapshot);
                }
                return;
            }
            case PROCEED -> {
                // continue
            }
        }

        GuardrailDecision decision = tradeGuardrails.evaluate(
                desiredKey,
                symbolSnapshot,
                managedPositions,
                lastTradeActivityBySymbol.get(signal.symbol()),
                config.trading().maxOpenPositions(),
                config.trading().entryCooldownSeconds(),
                Instant.now());

        if (!decision.allowed()) {
            log.info("Guardrail blocked symbol={} type={} reason={}",
                    signal.symbol(), signal.type(), decision.reason());
            return;
        }

        if (config.trading().dryRun()) {
            log.info(
                    "DRY-RUN order plan symbol={} type={} entry={} stop={} tp={} qty={} notional={} sizingEquity={} risk={} mode={}",
                    plan.symbol(),
                    plan.signalType(),
                    plan.entryPrice(),
                    plan.stopPrice(),
                    plan.takeProfitPrice(),
                    plan.quantity(),
                    plan.notionalUsd(),
                    plan.sizingEquityUsd(),
                    plan.riskAmountUsd(),
                    config.trading().riskCapitalMode());
            return;
        }

        PlacementResult placement = executionService.execute(plan, exchangeGateway);
        if (!placement.accepted()) {
            log.warn("LIVE placement rejected symbol={} type={} reason={}",
                    plan.symbol(), plan.signalType(), placement.rejectionReason());
            return;
        }

        latestAcceptedPlanByKey.put(desiredKey, plan);
        rememberBotClientOrderId(placement.entryClientOrderId());
        rememberBotClientAlgoId(placement.stopClientAlgoId());
        rememberBotClientAlgoId(placement.takeProfitClientAlgoId());
        markTradeActivity(plan.symbol(), Instant.now());

        log.info(
                "LIVE placement sent symbol={} type={} qty={} stop={} tp={} entryClientOrderId={} stopClientAlgoId={} tpClientAlgoId={}",
                placement.symbol(),
                placement.signalType(),
                placement.executedQuantity(),
                placement.stopTriggerPrice(),
                placement.takeProfitTriggerPrice(),
                placement.entryClientOrderId(),
                placement.stopClientAlgoId(),
                placement.takeProfitClientAlgoId());
    }

    private void flattenOppositePosition(TradeSignal signal, ExchangeSnapshot symbolSnapshot) {
        PositionKey oppositeKey = oppositeKey(signal.type(), signal.symbol());
        Optional<PositionSnapshot> oppositePosition = symbolSnapshot.findPosition(oppositeKey);

        if (oppositePosition.isEmpty()) {
            log.warn("Lifecycle requested flatten but no opposite position was found for {}", oppositeKey);
            return;
        }

        PositionSnapshot snapshot = oppositePosition.get();
        exchangeGateway.cancelAllOpenOrders(signal.symbol());
        exchangeGateway.cancelAllOpenAlgoOrders(signal.symbol());

        String closeClientOrderId = "BOT_CLOSE_" + System.currentTimeMillis();
        String actualClientOrderId = exchangeGateway.closePositionMarket(oppositeKey, snapshot.quantity(),
                closeClientOrderId);
        rememberBotClientOrderId(actualClientOrderId);
        latestAcceptedPlanByKey.remove(oppositeKey);
        markTradeActivity(signal.symbol(), Instant.now());

        log.info("Lifecycle flatten sent symbol={} side={} qty={} clientOrderId={}",
                oppositeKey.symbol(), oppositeKey.side(), snapshot.quantity(), actualClientOrderId);
    }

    private void syncSnapshot(ExchangeSnapshot snapshot, boolean manualInterventionDetected) {
        for (String symbol : config.trading().symbols()) {
            reconcileForSymbol(snapshot, symbol, manualInterventionDetected);
        }
    }

    private void refreshSymbol(String symbol, boolean manualInterventionDetected) {
        if (!config.trading().symbols().contains(symbol)) {
            return;
        }
        ExchangeSnapshot snapshot = exchangeGateway.currentSnapshot(symbol);
        reconcileForSymbol(snapshot, symbol, manualInterventionDetected);
    }

    private void reconcileForSymbol(ExchangeSnapshot snapshot, String symbol, boolean manualInterventionDetected) {
        reconcileKey(snapshot, new PositionKey(symbol, PositionSide.LONG), manualInterventionDetected);
        reconcileKey(snapshot, new PositionKey(symbol, PositionSide.SHORT), manualInterventionDetected);
    }

    private void reconcileKey(ExchangeSnapshot snapshot, PositionKey key, boolean manualInterventionDetected) {
        Optional<ManagedPosition> internalPosition = Optional.ofNullable(managedPositions.get(key));
        var exchangePosition = snapshot.findPosition(key);

        ReconcileDecision decision = reconciler.reconcile(
                internalPosition,
                exchangePosition,
                manualInterventionDetected);

        switch (decision.action()) {
            case NO_CHANGE -> {
                // no-op
            }
            case ADOPT_EXCHANGE_POSITION, UPDATE_EXISTING -> {
                ManagedPosition managed = decision.managedPosition();
                managedPositions.put(key, managed);
                log.info("{} {} -> {}", key, decision.action(), decision.reason());

                trackPositionLifecycleOpenIfNeeded(key);

                boolean missingStop = !managed.snapshot().hasProtectiveStop();
                boolean missingTakeProfit = !managed.snapshot().hasTakeProfit();

                if (missingStop || missingTakeProfit) {
                    log.warn("{} protection incomplete. hasStop={} hasTakeProfit={}",
                            key, managed.snapshot().hasProtectiveStop(), managed.snapshot().hasTakeProfit());
                    attemptProtectionRepair(key, managed.snapshot(), missingStop, missingTakeProfit);
                }
            }
            case CLEAR_INTERNAL_POSITION -> {
                if (managedPositions.containsKey(key)) {
                    recordPositionClose(key, decision.reason());
                }
                managedPositions.remove(key);
                latestAcceptedPlanByKey.remove(key);
                log.info("{} -> CLEAR_INTERNAL_POSITION ({})", key, decision.reason());
            }
        }
    }

    private void trackPositionLifecycleOpenIfNeeded(PositionKey key) {
        if (dailyRiskManager.isTracked(key)) {
            return;
        }
        BigDecimal equity = exchangeGateway.accountEquity().marginBalanceUsd();
        dailyRiskManager.trackOpenPositionIfAbsent(key, equity, Instant.now(), "adopted_or_recovered");
    }

    private void recordPositionClose(PositionKey key, String closeReason) {
        BigDecimal equity = exchangeGateway.accountEquity().marginBalanceUsd();
        dailyRiskManager.onPositionClosed(key, equity, Instant.now(), closeReason, exchangeGateway)
                .ifPresent(record -> log.info(
                        "Journaled trade symbol={} side={} grossRealized={} commission={} funding={} netPnl={} outcome={} reason={}",
                        record.symbol(),
                        record.side(),
                        record.grossRealizedPnlUsd(),
                        record.commissionUsd(),
                        record.fundingFeeUsd(),
                        record.netPnlUsd(),
                        record.outcome(),
                        record.closeReason()));
    }

    private void attemptProtectionRepair(
            PositionKey key,
            PositionSnapshot snapshot,
            boolean missingStop,
            boolean missingTakeProfit) {
        if (config.trading().dryRun()) {
            log.info("DRY-RUN protection repair skipped for {}", key);
            return;
        }

        OrderPlan plan = latestAcceptedPlanByKey.get(key);
        if (plan == null) {
            log.warn("No cached plan available to repair protection for {}", key);
            return;
        }

        if (!isPlanCompatible(snapshot, plan)) {
            log.warn(
                    "Cached plan is not compatible with current exchange position for {}. entryPrice={} plannedEntry={}",
                    key, snapshot.entryPrice(), plan.entryPrice());
            return;
        }

        if (missingStop) {
            String stopClientAlgoId = exchangeGateway.placeProtectiveAlgoOrder(
                    key.symbol(),
                    plan.signalType(),
                    plan.stopPrice(),
                    false,
                    "BOT_REPAIR_STOP_" + System.currentTimeMillis());
            rememberBotClientAlgoId(stopClientAlgoId);
        }

        if (missingTakeProfit) {
            String tpClientAlgoId = exchangeGateway.placeProtectiveAlgoOrder(
                    key.symbol(),
                    plan.signalType(),
                    plan.takeProfitPrice(),
                    true,
                    "BOT_REPAIR_TP_" + System.currentTimeMillis());
            rememberBotClientAlgoId(tpClientAlgoId);
        }

        markTradeActivity(key.symbol(), Instant.now());
        log.info("Protection repair sent for {} missingStop={} missingTakeProfit={}", key, missingStop,
                missingTakeProfit);
    }

    private boolean isPlanCompatible(PositionSnapshot snapshot, OrderPlan plan) {
        if (!snapshot.symbol().equals(plan.symbol())) {
            return false;
        }

        double exchangeEntry = snapshot.entryPrice().doubleValue();
        double plannedEntry = plan.entryPrice().doubleValue();
        if (exchangeEntry <= 0.0d || plannedEntry <= 0.0d) {
            return false;
        }

        double pctDiff = Math.abs(exchangeEntry - plannedEntry) / plannedEntry;
        return pctDiff <= 0.01d;
    }

    private void onUserStreamEvent(UserStreamEvents.UserStreamEvent event) {
        switch (event) {
            case UserStreamEvents.OrderTradeUpdateEvent orderEvent -> onOrderTradeUpdate(orderEvent);
            case UserStreamEvents.AccountPositionUpdateEvent accountEvent -> onAccountPositionUpdate(accountEvent);
            case UserStreamEvents.AlgoOrderUpdateEvent algoEvent -> onAlgoOrderUpdate(algoEvent);
        }
    }

    public void onOrderTradeUpdate(UserStreamEvents.OrderTradeUpdateEvent event) {
        boolean manual = manualDetector.isManual(event, knownBotClientOrderIds);
        if (manual) {
            log.warn("Manual intervention detected: symbol={} side={} clientOrderId={}",
                    event.symbol(), event.positionSide(), event.clientOrderId());
        }

        if (isActivityWorthyOrderState(event.orderStatus(), event.executionType())) {
            markTradeActivity(event.symbol(), event.eventTime());
        }

        refreshSymbol(event.symbol(), manual);
    }

    public void onAccountPositionUpdate(UserStreamEvents.AccountPositionUpdateEvent event) {
        markTradeActivity(event.symbol(), event.eventTime());
        refreshSymbol(event.symbol(), false);
    }

    public void onAlgoOrderUpdate(UserStreamEvents.AlgoOrderUpdateEvent event) {
        boolean manual = manualDetector.isManualAlgo(event.clientAlgoId(), knownBotClientAlgoIds);
        if (manual) {
            log.warn("Manual algo intervention detected: symbol={} side={} clientAlgoId={} status={}",
                    event.symbol(), event.positionSide(), event.clientAlgoId(), event.algoStatus());
        } else {
            log.info("Algo order update symbol={} side={} clientAlgoId={} type={} status={}",
                    event.symbol(), event.positionSide(), event.clientAlgoId(), event.orderType(), event.algoStatus());
        }

        if (isActivityWorthyAlgoState(event.algoStatus())) {
            markTradeActivity(event.symbol(), event.eventTime());
        }

        refreshSymbol(event.symbol(), manual);
    }

    public Optional<ManagedPosition> getManagedPosition(PositionKey key) {
        return Optional.ofNullable(managedPositions.get(key));
    }

    public void rememberBotClientOrderId(String clientOrderId) {
        if (clientOrderId != null && !clientOrderId.isBlank()) {
            knownBotClientOrderIds.add(clientOrderId);
        }
    }

    public void rememberBotClientAlgoId(String clientAlgoId) {
        if (clientAlgoId != null && !clientAlgoId.isBlank()) {
            knownBotClientAlgoIds.add(clientAlgoId);
        }
    }

    private void markTradeActivity(String symbol, Instant when) {
        if (symbol != null && when != null) {
            lastTradeActivityBySymbol.put(symbol, when);
        }
    }

    private void runRiskMonitor() {
        try {
            Instant now = Instant.now();
            Instant lastMarketData = lastMarketDataEventAt.get();
            long staleSeconds = Math.max(0, java.time.Duration.between(lastMarketData, now).getSeconds());

            if (staleSeconds > config.trading().marketDataStaleSeconds()) {
                triggerTradingHalt(
                        "Market data stale for " + staleSeconds + "s (limit "
                                + config.trading().marketDataStaleSeconds() + "s)",
                        true);
                return;
            }

            AccountEquitySnapshot equity = exchangeGateway.accountEquity();
            RiskGateDecision riskDecision = dailyRiskManager.evaluateCanTrade(equity.marginBalanceUsd(), now);
            if (!riskDecision.allowed()) {
                triggerTradingHalt(riskDecision.reason(), false);
            }
        } catch (Exception e) {
            log.warn("Risk monitor error", e);
        }
    }

    private synchronized void triggerTradingHalt(String reason, boolean cancelRegularOrders) {
        if (tradingHaltReason != null) {
            return;
        }
        tradingHaltReason = reason;
        log.error("TRADING HALTED: {}", reason);

        if (cancelRegularOrders) {
            for (String symbol : config.trading().symbols()) {
                try {
                    exchangeGateway.cancelAllOpenOrders(symbol);
                } catch (Exception e) {
                    log.warn("Failed to cancel regular open orders for {} during trading halt", symbol, e);
                }
            }
        }
    }

    private boolean isActivityWorthyOrderState(String orderStatus, String executionType) {
        return "NEW".equalsIgnoreCase(orderStatus)
                || "PARTIALLY_FILLED".equalsIgnoreCase(orderStatus)
                || "FILLED".equalsIgnoreCase(orderStatus)
                || "CANCELED".equalsIgnoreCase(orderStatus)
                || "EXPIRED".equalsIgnoreCase(orderStatus)
                || "TRADE".equalsIgnoreCase(executionType);
    }

    private boolean isActivityWorthyAlgoState(String algoStatus) {
        return "NEW".equalsIgnoreCase(algoStatus)
                || "TRIGGERED".equalsIgnoreCase(algoStatus)
                || "FILLED".equalsIgnoreCase(algoStatus)
                || "CANCELED".equalsIgnoreCase(algoStatus)
                || "EXPIRED".equalsIgnoreCase(algoStatus)
                || "FINISHED".equalsIgnoreCase(algoStatus);
    }

    private PositionKey positionKey(SignalType signalType, String symbol) {
        return new PositionKey(symbol, signalType == SignalType.LONG_ENTRY ? PositionSide.LONG : PositionSide.SHORT);
    }

    private PositionKey oppositeKey(SignalType signalType, String symbol) {
        return new PositionKey(symbol, signalType == SignalType.LONG_ENTRY ? PositionSide.SHORT : PositionSide.LONG);
    }
}