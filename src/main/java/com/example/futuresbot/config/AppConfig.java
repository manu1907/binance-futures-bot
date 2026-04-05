package com.example.futuresbot.config;

import com.example.futuresbot.execution.OppositeSignalPolicy;
import com.example.futuresbot.execution.RiskCapitalMode;
import com.example.futuresbot.reconcile.AdoptionMode;

import java.util.List;

public record AppConfig(ExchangeConfig exchange, TradingConfig trading, ReplayConfig replay) {

    public record ExchangeConfig(
            String baseUrl,
            String wsBaseUrl,
            String apiKey,
            String apiSecret,
            boolean useTestnet,
            boolean hedgeModeRequired) {
    }

    public record TradingConfig(
            List<String> symbols,
            boolean dryRun,
            AdoptionMode adoptionMode,
            int defaultLeverage,
            double maxRiskPerTradePct,
            double maxDailyDrawdownPct,
            int maxOpenPositions,
            double effectiveCapitalUsd,
            RiskCapitalMode riskCapitalMode,
            double minimumTradeNotionalUsd,
            double stopAtrMultiple,
            double takeProfitRiskReward,
            double takerFeeBps,
            int entryCooldownSeconds,
            OppositeSignalPolicy oppositeSignalPolicy,
            int maxConsecutiveLosses,
            int marketDataStaleSeconds,
            String journalCsvPath,
            ExitManagementConfig exitManagement) {
    }

    public record ReplayConfig(
            List<String> symbols,
            String from,
            String to,
            double initialEquityUsd,
            double takerFeeBps,
            String tradesCsvPath) {
    }

    public record ExitManagementConfig(
            boolean enabled,
            double breakEvenAfterR,
            double trailingStopActivationR,
            int trailingSwingLookbackBars,
            double trailingStopAtrBuffer,
            double extendTakeProfitActivationR,
            double extendTakeProfitStepR,
            double maxTakeProfitRiskReward) {
    }
}