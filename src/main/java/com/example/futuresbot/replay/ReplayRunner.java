package com.example.futuresbot.replay;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.execution.AccountEquitySnapshot;
import com.example.futuresbot.execution.ExecutionPlanner;
import com.example.futuresbot.execution.OrderPlan;
import com.example.futuresbot.execution.RiskSizer;
import com.example.futuresbot.marketdata.BinanceMarketDataService;
import com.example.futuresbot.marketdata.Candle;
import com.example.futuresbot.marketdata.CandleInterval;
import com.example.futuresbot.marketdata.MarketDataService;
import com.example.futuresbot.strategy.SignalType;
import com.example.futuresbot.strategy.StrategyContext;
import com.example.futuresbot.strategy.TradeSignal;
import com.example.futuresbot.strategy.TradingStrategy;
import com.example.futuresbot.strategy.elder.ElderTripleScreenStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ReplayRunner {
    private static final Logger log = LoggerFactory.getLogger(ReplayRunner.class);
    private static final int WARMUP_DAYS = 20;

    private final AppConfig config;
    private final MarketDataService marketDataService;
    private final TradingStrategy strategy = new ElderTripleScreenStrategy();
    private final ExecutionPlanner executionPlanner = new ExecutionPlanner(new RiskSizer());

    public ReplayRunner(AppConfig config) {
        this(config, new BinanceMarketDataService(config.exchange()));
    }

    public ReplayRunner(AppConfig config, MarketDataService marketDataService) {
        this.config = config;
        this.marketDataService = marketDataService;
    }

    public void run() {
        AppConfig.ReplayConfig replay = requireReplayConfig(config);
        ReplayTradeCsvWriter writer = new ReplayTradeCsvWriter(replay.tradesCsvPath());

        Map<String, SymbolReplaySeries> seriesBySymbol = loadSeries(replay);
        List<ReplayEvent> events = buildEvents(seriesBySymbol, replay);

        BigDecimal equity = BigDecimal.valueOf(replay.initialEquityUsd()).setScale(8, RoundingMode.HALF_UP);
        Map<String, SimulatedPosition> openPositions = new HashMap<>();
        Map<String, Integer> idx4hBySymbol = new HashMap<>();
        Map<String, Integer> idx1hBySymbol = new HashMap<>();
        Map<String, ReplaySummary> summaryBySymbol = new LinkedHashMap<>();
        ReplaySummary portfolioSummary = new ReplaySummary("PORTFOLIO");

        for (String symbol : replay.symbols()) {
            idx4hBySymbol.put(symbol, 0);
            idx1hBySymbol.put(symbol, 0);
            summaryBySymbol.put(symbol, new ReplaySummary(symbol));
        }

        for (ReplayEvent event : events) {
            String symbol = event.symbol();
            Candle current15m = event.candle();
            SymbolReplaySeries series = seriesBySymbol.get(symbol);

            int idx4h = advanceIndex(series.bars4h(), idx4hBySymbol.get(symbol), current15m.closeTime());
            int idx1h = advanceIndex(series.bars1h(), idx1hBySymbol.get(symbol), current15m.closeTime());
            idx4hBySymbol.put(symbol, idx4h);
            idx1hBySymbol.put(symbol, idx1h);

            SimulatedPosition existingPosition = openPositions.get(symbol);
            if (existingPosition != null) {
                Optional<ReplayTradeRecord> closedTrade = tryClosePosition(
                        symbol,
                        current15m,
                        existingPosition,
                        replay.takerFeeBps(),
                        equity);

                if (closedTrade.isPresent()) {
                    ReplayTradeRecord record = closedTrade.get();
                    writer.append(record);
                    equity = record.equityAfterUsd();
                    summaryBySymbol.get(symbol).accept(record);
                    portfolioSummary.accept(record);
                    openPositions.remove(symbol);
                }
            }

            if (openPositions.containsKey(symbol)) {
                continue;
            }

            if (openPositions.size() >= config.trading().maxOpenPositions()) {
                continue;
            }

            Map<CandleInterval, List<Candle>> contextSeries = new EnumMap<>(CandleInterval.class);
            contextSeries.put(CandleInterval.HOUR_4, series.bars4h().subList(0, idx4h));
            contextSeries.put(CandleInterval.HOUR_1, series.bars1h().subList(0, idx1h));
            contextSeries.put(CandleInterval.MINUTES_15, series.bars15m().subList(0, event.index15m() + 1));

            StrategyContext context = new StrategyContext(symbol, contextSeries);
            Optional<TradeSignal> signal = strategy.evaluate(context);
            if (signal.isEmpty()) {
                continue;
            }

            AccountEquitySnapshot syntheticEquity = new AccountEquitySnapshot(equity, equity, BigDecimal.ZERO);
            OrderPlan plan = executionPlanner.plan(signal.get(), context, syntheticEquity, config.trading());
            if (!plan.accepted()) {
                continue;
            }

            BigDecimal entryFee = fee(plan.notionalUsd(), replay.takerFeeBps());
            equity = equity.subtract(entryFee).setScale(8, RoundingMode.HALF_UP);

            openPositions.put(symbol, new SimulatedPosition(
                    signal.get().type(),
                    current15m.closeTime(),
                    plan.entryPrice(),
                    plan.quantity(),
                    plan.stopPrice(),
                    plan.takeProfitPrice(),
                    plan.notionalUsd(),
                    entryFee));
        }

        for (String symbol : replay.symbols()) {
            SimulatedPosition position = openPositions.get(symbol);
            if (position == null) {
                continue;
            }

            SymbolReplaySeries series = seriesBySymbol.get(symbol);
            Candle lastBar = series.bars15m().getLast();

            ReplayTradeRecord record = forceCloseAtEnd(
                    symbol,
                    lastBar,
                    position,
                    replay.takerFeeBps(),
                    equity);
            writer.append(record);
            equity = record.equityAfterUsd();
            summaryBySymbol.get(symbol).accept(record);
            portfolioSummary.accept(record);
        }

        for (ReplaySummary summary : summaryBySymbol.values()) {
            log.info(summary.formatLine(equity));
        }
        log.info(portfolioSummary.formatLine(equity));
    }

    private Map<String, SymbolReplaySeries> loadSeries(AppConfig.ReplayConfig replay) {
        Map<String, SymbolReplaySeries> result = new LinkedHashMap<>();
        Instant from = Instant.parse(replay.from());
        Instant to = Instant.parse(replay.to());
        Instant warmupFrom = from.minus(WARMUP_DAYS, ChronoUnit.DAYS);

        for (String symbol : replay.symbols()) {
            List<Candle> bars4h = marketDataService.loadHistoricalKlines(
                    symbol, CandleInterval.HOUR_4, warmupFrom, to.plus(1, ChronoUnit.SECONDS));
            List<Candle> bars1h = marketDataService.loadHistoricalKlines(
                    symbol, CandleInterval.HOUR_1, warmupFrom, to.plus(1, ChronoUnit.SECONDS));
            List<Candle> bars15m = marketDataService.loadHistoricalKlines(
                    symbol, CandleInterval.MINUTES_15, warmupFrom, to.plus(1, ChronoUnit.SECONDS));

            if (bars4h.isEmpty() || bars1h.isEmpty() || bars15m.isEmpty()) {
                throw new IllegalStateException("Replay data incomplete for " + symbol);
            }

            result.put(symbol, new SymbolReplaySeries(bars4h, bars1h, bars15m));
        }

        return result;
    }

    private List<ReplayEvent> buildEvents(Map<String, SymbolReplaySeries> seriesBySymbol,
            AppConfig.ReplayConfig replay) {
        Instant from = Instant.parse(replay.from());
        Instant to = Instant.parse(replay.to());

        List<ReplayEvent> events = new ArrayList<>();
        for (Map.Entry<String, SymbolReplaySeries> entry : seriesBySymbol.entrySet()) {
            String symbol = entry.getKey();
            List<Candle> bars15m = entry.getValue().bars15m();

            for (int i = 0; i < bars15m.size(); i++) {
                Candle candle = bars15m.get(i);
                if (candle.closeTime().isBefore(from) || candle.closeTime().isAfter(to)) {
                    continue;
                }
                events.add(new ReplayEvent(symbol, candle, i));
            }
        }

        events.sort((left, right) -> {
            int timeCompare = left.candle().closeTime().compareTo(right.candle().closeTime());
            if (timeCompare != 0) {
                return timeCompare;
            }
            return left.symbol().compareTo(right.symbol());
        });
        return events;
    }

    private int advanceIndex(List<Candle> bars, int currentIndex, Instant time) {
        int idx = currentIndex;
        while (idx < bars.size() && !bars.get(idx).closeTime().isAfter(time)) {
            idx++;
        }
        return idx;
    }

    private Optional<ReplayTradeRecord> tryClosePosition(
            String symbol,
            Candle bar,
            SimulatedPosition position,
            double takerFeeBps,
            BigDecimal currentEquity) {
        boolean stopHit;
        boolean tpHit;
        BigDecimal exitPrice;
        String exitReason;

        if (position.signalType() == SignalType.LONG_ENTRY) {
            stopHit = bar.low().compareTo(position.stopPrice()) <= 0;
            tpHit = bar.high().compareTo(position.takeProfitPrice()) >= 0;

            if (!stopHit && !tpHit) {
                return Optional.empty();
            }

            if (stopHit) {
                exitPrice = position.stopPrice();
                exitReason = "STOP";
            } else {
                exitPrice = position.takeProfitPrice();
                exitReason = "TAKE_PROFIT";
            }
        } else {
            stopHit = bar.high().compareTo(position.stopPrice()) >= 0;
            tpHit = bar.low().compareTo(position.takeProfitPrice()) <= 0;

            if (!stopHit && !tpHit) {
                return Optional.empty();
            }

            if (stopHit) {
                exitPrice = position.stopPrice();
                exitReason = "STOP";
            } else {
                exitPrice = position.takeProfitPrice();
                exitReason = "TAKE_PROFIT";
            }
        }

        return Optional
                .of(closeRecord(symbol, bar.closeTime(), exitPrice, exitReason, position, takerFeeBps, currentEquity));
    }

    private ReplayTradeRecord forceCloseAtEnd(
            String symbol,
            Candle bar,
            SimulatedPosition position,
            double takerFeeBps,
            BigDecimal currentEquity) {
        return closeRecord(symbol, bar.closeTime(), bar.close(), "END_OF_REPLAY", position, takerFeeBps, currentEquity);
    }

    private ReplayTradeRecord closeRecord(
            String symbol,
            Instant exitTime,
            BigDecimal exitPrice,
            String exitReason,
            SimulatedPosition position,
            double takerFeeBps,
            BigDecimal currentEquity) {
        BigDecimal grossPnl = grossPnl(position.signalType(), position.entryPrice(), exitPrice, position.quantity());
        BigDecimal exitNotional = exitPrice.multiply(position.quantity()).setScale(8, RoundingMode.HALF_UP);
        BigDecimal exitFee = fee(exitNotional, takerFeeBps);
        BigDecimal totalFees = position.entryFeeUsd().add(exitFee).setScale(8, RoundingMode.HALF_UP);
        BigDecimal netPnl = grossPnl.subtract(totalFees).setScale(8, RoundingMode.HALF_UP);
        BigDecimal equityAfter = currentEquity.add(grossPnl).subtract(exitFee).setScale(8, RoundingMode.HALF_UP);

        return new ReplayTradeRecord(
                symbol,
                position.signalType(),
                position.entryTime(),
                exitTime,
                position.entryPrice(),
                exitPrice,
                position.quantity(),
                grossPnl.setScale(8, RoundingMode.HALF_UP),
                totalFees,
                netPnl,
                exitReason,
                equityAfter);
    }

    private BigDecimal grossPnl(SignalType signalType, BigDecimal entryPrice, BigDecimal exitPrice,
            BigDecimal quantity) {
        BigDecimal move = exitPrice.subtract(entryPrice);
        if (signalType == SignalType.SHORT_ENTRY) {
            move = move.negate();
        }
        return move.multiply(quantity).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal fee(BigDecimal notional, double takerFeeBps) {
        return notional.multiply(BigDecimal.valueOf(takerFeeBps))
                .divide(BigDecimal.valueOf(10_000), 8, RoundingMode.HALF_UP);
    }

    private AppConfig.ReplayConfig requireReplayConfig(AppConfig config) {
        if (config.replay() == null) {
            throw new IllegalStateException("Missing replay config section");
        }
        if (config.replay().symbols() == null || config.replay().symbols().isEmpty()) {
            throw new IllegalStateException("Replay symbols must not be empty");
        }
        if (config.replay().from() == null || config.replay().to() == null) {
            throw new IllegalStateException("Replay from/to timestamps must be set");
        }
        return config.replay();
    }

    private record SymbolReplaySeries(
            List<Candle> bars4h,
            List<Candle> bars1h,
            List<Candle> bars15m) {
    }

    private record ReplayEvent(
            String symbol,
            Candle candle,
            int index15m) {
    }

    private record SimulatedPosition(
            SignalType signalType,
            Instant entryTime,
            BigDecimal entryPrice,
            BigDecimal quantity,
            BigDecimal stopPrice,
            BigDecimal takeProfitPrice,
            BigDecimal entryNotionalUsd,
            BigDecimal entryFeeUsd) {
    }
}