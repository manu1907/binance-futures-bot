package com.example.futuresbot.runtime;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.domain.ManagedPosition;
import com.example.futuresbot.domain.PositionKey;
import com.example.futuresbot.domain.PositionSide;
import com.example.futuresbot.domain.PositionSnapshot;
import com.example.futuresbot.exchange.BinanceApiException;
import com.example.futuresbot.exchange.BinanceRestGateway;
import com.example.futuresbot.exchange.ExchangeGateway;
import com.example.futuresbot.exchange.ExchangeSnapshot;
import com.example.futuresbot.exchange.UserStreamEvents;
import com.example.futuresbot.execution.AccountEquitySnapshot;
import com.example.futuresbot.execution.ActiveProtectionState;
import com.example.futuresbot.execution.ExecutionPlanner;
import com.example.futuresbot.execution.ExecutionService;
import com.example.futuresbot.execution.GuardrailDecision;
import com.example.futuresbot.execution.LifecycleDecision;
import com.example.futuresbot.execution.OrderPlan;
import com.example.futuresbot.execution.PlacementResult;
import com.example.futuresbot.execution.PositionExitManager;
import com.example.futuresbot.execution.PositionLifecycleManager;
import com.example.futuresbot.execution.PositionManagementDecision;
import com.example.futuresbot.execution.TradeGuardrails;
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
import com.example.futuresbot.utils.NumberUtils;
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
    private static final int UNKNOWN_PLACEMENT_RECONCILE_ATTEMPTS = 3;
    private static final long UNKNOWN_PLACEMENT_RECONCILE_SLEEP_MS = 500L;

    private final AppConfig config;
    private final ExchangeGateway exchangeGateway;
    private final MarketDataService marketDataService;
    private final PositionReconciler reconciler;
    private final ManualInterventionDetector manualDetector;
    private final Map<PositionKey, ManagedPosition> managedPositions = new ConcurrentHashMap<>();
    private final Map<PositionKey, OrderPlan> latestAcceptedPlanByKey = new ConcurrentHashMap<>();
    private final Set<String> knownBotClientOrderIds = ConcurrentHashMap.newKeySet();
    private final Set<String> knownBotClientAlgoIds = ConcurrentHashMap.newKeySet();
    private final InMemoryMarketDataBuffer marketDataBuffer;
    private final TradingStrategy strategy;
    private final ExecutionPlanner executionPlanner;
    private final ExecutionService executionService;
    private final TradeGuardrails tradeGuardrails;
    private final PositionLifecycleManager lifecycleManager;
    private final Map<String, Instant> lastSignalBarCloseBySymbol = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastTradeActivityBySymbol = new ConcurrentHashMap<>();
    private final ScheduledExecutorService riskScheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicReference<Instant> lastMarketDataEventAt = new AtomicReference<>(Instant.EPOCH);
    private final DailyRiskManager dailyRiskManager;
    private final PositionExitManager positionExitManager = new PositionExitManager();
    private final Map<PositionKey, ActiveProtectionState> activeProtectionByKey = new ConcurrentHashMap<>();

    private volatile String tradingHaltReason;

    public BotRuntime(AppConfig config) {
        this(
                config,
                new BinanceRestGateway(config),
                new BinanceMarketDataService(config.exchange()),
                new PositionReconciler(),
                new ManualInterventionDetector(),
                new InMemoryMarketDataBuffer(500),
                new ElderTripleScreenStrategy(),
                new ExecutionPlanner(),
                new ExecutionService(),
                new TradeGuardrails(),
                new PositionLifecycleManager(),
                new DailyRiskManager(
                        config.trading().maxDailyDrawdownPct(),
                        config.trading().maxConsecutiveLosses(),
                        new TradeJournalCsvWriter(config.trading().journalCsvPath())));
    }

    public BotRuntime(AppConfig config, ExchangeGateway exchangeGateway, MarketDataService marketDataService) {
        this(
                config,
                exchangeGateway,
                marketDataService,
                new PositionReconciler(),
                new ManualInterventionDetector(),
                new InMemoryMarketDataBuffer(500),
                new ElderTripleScreenStrategy(),
                new ExecutionPlanner(),
                new ExecutionService(),
                new TradeGuardrails(),
                new PositionLifecycleManager(),
                new DailyRiskManager(
                        config.trading().maxDailyDrawdownPct(),
                        config.trading().maxConsecutiveLosses(),
                        new TradeJournalCsvWriter(config.trading().journalCsvPath())));
    }

    BotRuntime(
            AppConfig config,
            ExchangeGateway exchangeGateway,
            MarketDataService marketDataService,
            PositionReconciler reconciler,
            ManualInterventionDetector manualDetector,
            InMemoryMarketDataBuffer marketDataBuffer,
            TradingStrategy strategy,
            ExecutionPlanner executionPlanner,
            ExecutionService executionService,
            TradeGuardrails tradeGuardrails,
            PositionLifecycleManager lifecycleManager,
            DailyRiskManager dailyRiskManager) {
        this.config = config;
        this.exchangeGateway = exchangeGateway;
        this.marketDataService = marketDataService;
        this.reconciler = reconciler;
        this.manualDetector = manualDetector;
        this.marketDataBuffer = marketDataBuffer;
        this.strategy = strategy;
        this.executionPlanner = executionPlanner;
        this.executionService = executionService;
        this.tradeGuardrails = tradeGuardrails;
        this.lifecycleManager = lifecycleManager;
        this.dailyRiskManager = dailyRiskManager;
    }

    public void start() {
        log.info("Runtime started. symbols={} adoptionMode={}",
                this.config.trading().symbols(), this.config.trading().adoptionMode());

        this.verifyExchangeSetup();

        AccountEquitySnapshot startupEquity = this.exchangeGateway.accountEquity();
        this.dailyRiskManager.initialize(startupEquity.marginBalanceUsd(), Instant.now());

        ExchangeSnapshot startupSnapshot = this.exchangeGateway.currentSnapshot();
        this.logStartupRecoverySummary(startupSnapshot);
        this.syncSnapshot(startupSnapshot);
        this.auditStartupProtection();

        this.exchangeGateway.connectUserStream(this::onUserStreamEvent);
        this.bootstrapMarketData();
        this.lastMarketDataEventAt.set(Instant.now());
        this.marketDataService.connectKlineStreams(this.config.trading().symbols(), STRATEGY_INTERVALS, this::onCandle);
        this.riskScheduler.scheduleAtFixedRate(this::runRiskMonitor, 15, 15, TimeUnit.SECONDS);
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
        if (this.config.exchange().hedgeModeRequired() && !this.exchangeGateway.isHedgeModeEnabled()) {
            throw new IllegalStateException(
                    "Bot requires Binance hedge mode, but account is currently in one-way mode");
        }

        int leverage = this.config.trading().defaultLeverage();
        if (NumberUtils.isPositive(leverage)) {
            for (String symbol : this.config.trading().symbols()) {
                this.exchangeGateway.setLeverage(symbol, leverage);
            }
        }
    }

    private void logStartupRecoverySummary(ExchangeSnapshot snapshot) {
        long openPositions = snapshot.positions().stream().filter(PositionSnapshot::isOpen).count();
        if (NumberUtils.isPositive(openPositions) || !snapshot.openOrders().isEmpty() || !snapshot.openAlgoOrders().isEmpty()) {
            log.warn(
                    "Startup recovery detected positions={} openOrders={} openAlgoOrders={}",
                    openPositions,
                    snapshot.openOrders().size(),
                    snapshot.openAlgoOrders().size());
        }
    }

    private void auditStartupProtection() {
        for (String symbol : this.config.trading().symbols()) {
            ExchangeSnapshot snapshot = this.exchangeGateway.currentSnapshot(symbol);

            this.auditStartupProtection(snapshot, new PositionKey(symbol, PositionSide.LONG));
            this.auditStartupProtection(snapshot, new PositionKey(symbol, PositionSide.SHORT));
            this.logDanglingStartupAlgoOrders(snapshot, symbol);
        }
    }

    private void auditStartupProtection(ExchangeSnapshot snapshot, PositionKey key) {
        Optional<PositionSnapshot> exchangePosition = snapshot.findPosition(key);
        if (exchangePosition.isEmpty()) {
            return;
        }

        boolean hasStop = snapshot.hasProtectiveStop(key);
        boolean hasTakeProfit = snapshot.hasTakeProfit(key);

        if (hasStop && hasTakeProfit) {
            log.info("Startup protection audit ok symbol={} side={} hasStop={} hasTakeProfit={}",
                    key.symbol(), key.side(), hasStop, hasTakeProfit);
            return;
        }

        String reason = "Startup protection audit failed for "
                + key
                + ": hasStop=" + hasStop
                + " hasTakeProfit=" + hasTakeProfit;

        log.error(reason);
        this.triggerTradingHalt(reason, false);
    }

    private void logDanglingStartupAlgoOrders(ExchangeSnapshot snapshot, String symbol) {
        long openPositions = snapshot.positions().stream()
                .filter(position -> position.symbol().equals(symbol) && position.isOpen())
                .count();

        long openAlgoOrders = snapshot.openAlgoOrders().stream()
                .filter(order -> order.symbol().equals(symbol))
                .count();

        if (openPositions == 0 && openAlgoOrders > 0) {
            log.warn("Startup found dangling algo orders symbol={} openAlgoOrders={} without open position",
                    symbol, openAlgoOrders);
        }
    }

    private void bootstrapMarketData() {
        for (String symbol : config.trading().symbols()) {
            for (CandleInterval interval : STRATEGY_INTERVALS) {
                List<Candle> candles = this.marketDataService.loadHistoricalKlines(symbol, interval, 250);
                this.marketDataBuffer.seed(symbol, interval, candles);
                log.info("Bootstrapped {} candles for {} {}", candles.size(), symbol, interval.code());
            }
        }
    }

    private void onCandle(Candle candle) {
        this.lastMarketDataEventAt.set(Instant.now());
        this.marketDataBuffer.apply(candle);

        if (!candle.closed() || candle.interval() != CandleInterval.MINUTES_15) {
            return;
        }

        Instant previousProcessed = this.lastSignalBarCloseBySymbol.put(candle.symbol(), candle.closeTime());
        if (candle.closeTime().equals(previousProcessed)) {
            return;
        }

        this.manageOpenPositions(candle.symbol());

        StrategyContext context = StrategyContext.fromBuffer(candle.symbol(), this.marketDataBuffer, STRATEGY_INTERVALS);
        this.strategy.evaluate(context).ifPresent(signal -> onSignal(signal, context));
    }

    private void manageOpenPositions(String symbol) {
        if (this.config.trading().dryRun() || !this.config.trading().exitManagement().enabled()) {
            return;
        }

        StrategyContext context = StrategyContext.fromBuffer(symbol, this.marketDataBuffer, STRATEGY_INTERVALS);

        this.manageOpenPosition(new PositionKey(symbol, PositionSide.LONG), context);
        this.manageOpenPosition(new PositionKey(symbol, PositionSide.SHORT), context);
    }

    private void manageOpenPosition(PositionKey key, StrategyContext context) {
        ManagedPosition managed = this.managedPositions.get(key);
        if (managed == null || !managed.snapshot().isOpen()) {
            return;
        }

        OrderPlan entryPlan = this.latestAcceptedPlanByKey.get(key);
        ActiveProtectionState currentProtection = this.activeProtectionByKey.get(key);

        PositionManagementDecision decision = this.positionExitManager.evaluate(
                key,
                managed.snapshot(),
                entryPlan,
                currentProtection,
                context,
                config.trading().exitManagement());

        if (!decision.updateRequired()) {
            return;
        }

        this.replaceProtection(key, decision);
    }

    private void replaceProtection(PositionKey key, PositionManagementDecision decision) {
        ActiveProtectionState current = this.activeProtectionByKey.get(key);
        if (current == null) {
            return;
        }

        if (current.stopClientAlgoId() != null && !current.stopClientAlgoId().isBlank()) {
            this.exchangeGateway.cancelAlgoOrder(current.stopClientAlgoId());
        }
        if (current.takeProfitClientAlgoId() != null && !current.takeProfitClientAlgoId().isBlank()) {
            this.exchangeGateway.cancelAlgoOrder(current.takeProfitClientAlgoId());
        }

        SignalType signalType = key.side() == PositionSide.LONG ? SignalType.LONG_ENTRY : SignalType.SHORT_ENTRY;

        String stopClientAlgoId = this.exchangeGateway.placeProtectiveAlgoOrder(
                key.symbol(),
                signalType,
                decision.newStopTriggerPrice(),
                false,
                "BOT_TRAIL_STOP_" + System.currentTimeMillis());

        String tpClientAlgoId = this.exchangeGateway.placeProtectiveAlgoOrder(
                key.symbol(),
                signalType,
                decision.newTakeProfitTriggerPrice(),
                true,
                "BOT_TRAIL_TP_" + System.currentTimeMillis());

        this.rememberBotClientAlgoId(stopClientAlgoId);
        this.rememberBotClientAlgoId(tpClientAlgoId);

        this.activeProtectionByKey.put(key, new ActiveProtectionState(
                decision.newStopTriggerPrice(),
                decision.newTakeProfitTriggerPrice(),
                stopClientAlgoId,
                tpClientAlgoId,
                Instant.now()));

        this.markTradeActivity(key.symbol(), Instant.now());

        log.info("Protection updated for {} stop={} tp={} reason={}",
                key,
                decision.newStopTriggerPrice(),
                decision.newTakeProfitTriggerPrice(),
                decision.reason());
    }

    private void onSignal(TradeSignal signal, StrategyContext context) {
        if (tradingHaltReason != null) {
            log.warn("Trading halted. Ignoring signal symbol={} type={} reason={}",
                    signal.symbol(), signal.type(), tradingHaltReason);
            return;
        }

        AccountEquitySnapshot equity = exchangeGateway.accountEquity();
        BigDecimal sizingEquityUsd = equity.sizingEquity(
                config.trading().riskCapitalMode(),
                BigDecimal.valueOf(config.trading().effectiveCapitalUsd()));
        log.info(
                "Signal equity snapshot symbol={} type={} marginBalance={} availableBalance={} unrealizedPnl={} riskMode={} configuredCapitalUsd={} sizingEquityUsd={}",
                signal.symbol(),
                signal.type(),
                equity.marginBalanceUsd(),
                equity.availableBalanceUsd(),
                equity.unrealizedPnlUsd(),
                config.trading().riskCapitalMode(),
                config.trading().effectiveCapitalUsd(),
                sizingEquityUsd);
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

        PlacementResult placement;

        try {
            placement = executionService.execute(plan, exchangeGateway);
        } catch (Exception e) {
            if (this.isUnknownPlacementException(e) && this.recoverUnknownPlacement(desiredKey, plan)) {
                return;
            }

            log.warn("LIVE placement failed symbol={} type={}", plan.symbol(), plan.signalType(), e);
            return;
        }

        latestAcceptedPlanByKey.put(desiredKey, plan);
        activeProtectionByKey.put(desiredKey, new ActiveProtectionState(
                placement.stopTriggerPrice(),
                placement.takeProfitTriggerPrice(),
                placement.stopClientAlgoId(),
                placement.takeProfitClientAlgoId(),
                Instant.now()));
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

    private boolean isUnknownPlacementException(Exception e) {
        if (!(e instanceof BinanceApiException apiException)) {
            return false;
        }

        String responseBody = apiException.responseBody();
        if (responseBody == null || responseBody.isBlank()) {
            return false;
        }

        return apiException.statusCode() == 408
                || responseBody.contains("\"code\":-1007")
                || responseBody.contains("execution status unknown")
                || responseBody.contains("Send status unknown");
    }

    boolean recoverUnknownPlacement(PositionKey key, OrderPlan plan) {
        log.warn(
                "LIVE placement status unknown. Attempting reconciliation symbol={} side={} signalType={}",
                key.symbol(),
                key.side(),
                plan.signalType());

        for (int attempt = 1; attempt <= UNKNOWN_PLACEMENT_RECONCILE_ATTEMPTS; attempt++) {
            ExchangeSnapshot snapshot = this.exchangeGateway.currentSnapshot(key.symbol());

            if (snapshot.findPosition(key).isPresent()) {
                this.latestAcceptedPlanByKey.put(key, plan);
                this.reconcileForSymbol(snapshot, key.symbol(), false);
                this.markTradeActivity(key.symbol(), Instant.now());

                log.warn(
                        "Recovered unknown placement as open position symbol={} side={} attempt={}",
                        key.symbol(),
                        key.side(),
                        attempt);

                return true;
            }

            boolean hasOpenRegularOrder = snapshot.hasAnyOpenRegularOrder(key);
            boolean hasOpenAlgoOrder = snapshot.hasAnyOpenAlgoOrder(key);

            if (hasOpenRegularOrder || hasOpenAlgoOrder) {
                this.latestAcceptedPlanByKey.put(key, plan);

                log.warn(
                        "Unknown placement still pending on exchange symbol={} side={} attempt={} openRegularOrder={} openAlgoOrder={}",
                        key.symbol(),
                        key.side(),
                        attempt,
                        hasOpenRegularOrder,
                        hasOpenAlgoOrder);

                return true;
            }

            if (attempt < UNKNOWN_PLACEMENT_RECONCILE_ATTEMPTS) {
                this.pauseUnknownPlacementReconcile();
            }
        }

        this.latestAcceptedPlanByKey.remove(key);
        this.activeProtectionByKey.remove(key);

        log.error(
                "Unable to confirm unknown placement symbol={} side={} after {} attempts. Treating as failed.",
                key.symbol(),
                key.side(),
                UNKNOWN_PLACEMENT_RECONCILE_ATTEMPTS);

        return false;
    }

    private void pauseUnknownPlacementReconcile() {
        try {
            Thread.sleep(UNKNOWN_PLACEMENT_RECONCILE_SLEEP_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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

    private void syncSnapshot(ExchangeSnapshot snapshot) {
        for (String symbol : this.config.trading().symbols()) {
            this.reconcileForSymbol(snapshot, symbol, false);
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
        this.reconcileKey(snapshot, new PositionKey(symbol, PositionSide.LONG), manualInterventionDetected);
        this.reconcileKey(snapshot, new PositionKey(symbol, PositionSide.SHORT), manualInterventionDetected);
    }

    private void reconcileKey(ExchangeSnapshot snapshot, PositionKey key, boolean manualInterventionDetected) {
        Optional<ManagedPosition> internalPosition = Optional.ofNullable(this.managedPositions.get(key));
        var exchangePosition = snapshot.findPosition(key);

        ReconcileDecision decision = this.reconciler.reconcile(
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
                activeProtectionByKey.remove(key);
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

        ActiveProtectionState existingProtection = activeProtectionByKey.get(key);

        String stopClientAlgoId = existingProtection != null ? existingProtection.stopClientAlgoId() : null;
        String tpClientAlgoId = existingProtection != null ? existingProtection.takeProfitClientAlgoId() : null;

        BigDecimal stopTriggerPrice = existingProtection != null && !missingStop
                ? existingProtection.stopTriggerPrice()
                : plan.stopPrice();

        BigDecimal takeProfitTriggerPrice = existingProtection != null && !missingTakeProfit
                ? existingProtection.takeProfitTriggerPrice()
                : plan.takeProfitPrice();

        if (missingStop) {
            stopClientAlgoId = exchangeGateway.placeProtectiveAlgoOrder(
                    key.symbol(),
                    plan.signalType(),
                    plan.stopPrice(),
                    false,
                    "BOT_REPAIR_STOP_" + System.currentTimeMillis());
            rememberBotClientAlgoId(stopClientAlgoId);
        }

        if (missingTakeProfit) {
            tpClientAlgoId = exchangeGateway.placeProtectiveAlgoOrder(
                    key.symbol(),
                    plan.signalType(),
                    plan.takeProfitPrice(),
                    true,
                    "BOT_REPAIR_TP_" + System.currentTimeMillis());
            rememberBotClientAlgoId(tpClientAlgoId);
        }

        activeProtectionByKey.put(key, new ActiveProtectionState(
                stopTriggerPrice,
                takeProfitTriggerPrice,
                stopClientAlgoId,
                tpClientAlgoId,
                Instant.now()));

        markTradeActivity(key.symbol(), Instant.now());
        log.info("Protection repair sent for {} missingStop={} missingTakeProfit={}",
                key, missingStop, missingTakeProfit);
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
            case UserStreamEvents.OrderTradeUpdateEvent orderEvent -> this.onOrderTradeUpdate(orderEvent);
            case UserStreamEvents.AccountPositionUpdateEvent accountEvent -> this.onAccountPositionUpdate(accountEvent);
            case UserStreamEvents.AlgoOrderUpdateEvent algoEvent -> this.onAlgoOrderUpdate(algoEvent);
        }
    }

    public void onOrderTradeUpdate(UserStreamEvents.OrderTradeUpdateEvent event) {
        boolean manual = this.manualDetector.isManual(event, this.knownBotClientOrderIds);
        if (manual) {
            log.warn("Manual intervention detected: symbol={} side={} clientOrderId={}",
                    event.symbol(), event.positionSide(), event.clientOrderId());
        }

        if (this.isActivityWorthyOrderState(event.orderStatus(), event.executionType())) {
            this.markTradeActivity(event.symbol(), event.eventTime());
        }

        this.refreshSymbol(event.symbol(), manual);
    }

    public void onAccountPositionUpdate(UserStreamEvents.AccountPositionUpdateEvent event) {
        this.markTradeActivity(event.symbol(), event.eventTime());
        this.refreshSymbol(event.symbol(), false);
    }

    public void onAlgoOrderUpdate(UserStreamEvents.AlgoOrderUpdateEvent event) {
        boolean manual = this.manualDetector.isManualAlgo(event.clientAlgoId(), this.knownBotClientAlgoIds);
        if (manual) {
            log.warn("Manual algo intervention detected: symbol={} side={} clientAlgoId={} status={}",
                    event.symbol(), event.positionSide(), event.clientAlgoId(), event.algoStatus());
        } else {
            log.info("Algo order update symbol={} side={} clientAlgoId={} type={} status={}",
                    event.symbol(), event.positionSide(), event.clientAlgoId(), event.orderType(), event.algoStatus());
        }

        if (this.isActivityWorthyAlgoState(event.algoStatus())) {
            this.markTradeActivity(event.symbol(), event.eventTime());
        }

        this.refreshSymbol(event.symbol(), manual);
    }

    public Optional<ManagedPosition> getManagedPosition(PositionKey key) {
        return Optional.ofNullable(managedPositions.get(key));
    }

    public Optional<String> getTradingHaltReason() {
        return Optional.ofNullable(this.tradingHaltReason);
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
            this.lastTradeActivityBySymbol.put(symbol, when);
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