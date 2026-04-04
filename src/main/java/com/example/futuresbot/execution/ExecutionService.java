package com.example.futuresbot.execution;

import com.example.futuresbot.exchange.ExchangeGateway;
import com.example.futuresbot.exchange.SymbolRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

public final class ExecutionService {
    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    public PlacementResult execute(OrderPlan plan, ExchangeGateway exchangeGateway) {
        SymbolRules rules = exchangeGateway.symbolRules(plan.symbol());

        BigDecimal quantity = rules.normalizeMarketQuantity(plan.quantity());
        if (quantity.signum() <= 0) {
            return PlacementResult.rejected(plan.symbol(), plan.signalType(), "Rounded quantity is zero");
        }

        BigDecimal notional = quantity.multiply(plan.entryPrice()).setScale(8, RoundingMode.HALF_UP);
        if (notional.compareTo(rules.minNotional()) < 0) {
            return PlacementResult.rejected(
                    plan.symbol(),
                    plan.signalType(),
                    "Rounded notional " + notional + " is below exchange minimum " + rules.minNotional());
        }

        BigDecimal stopTriggerPrice = rules.normalizePrice(plan.stopPrice());
        BigDecimal takeProfitTriggerPrice = rules.normalizePrice(plan.takeProfitPrice());

        if (stopTriggerPrice.signum() <= 0 || takeProfitTriggerPrice.signum() <= 0) {
            return PlacementResult.rejected(plan.symbol(), plan.signalType(), "Rounded trigger price is zero");
        }

        log.info(
                "Entry placement symbol={} type={} rawQty={} normalizedQty={} normalizedStop={} normalizedTp={}",
                plan.symbol(),
                plan.signalType(),
                plan.quantity().toPlainString(),
                quantity.toPlainString(),
                stopTriggerPrice.toPlainString(),
                takeProfitTriggerPrice.toPlainString());

        String entryClientOrderId = clientId("ENTRY");
        String stopClientAlgoId = clientId("STOP");
        String takeProfitClientAlgoId = clientId("TP");

        String actualEntryClientOrderId = exchangeGateway.placeEntryMarketOrder(
                plan.symbol(),
                plan.signalType(),
                quantity,
                entryClientOrderId);

        String actualStopClientAlgoId = exchangeGateway.placeProtectiveAlgoOrder(
                plan.symbol(),
                plan.signalType(),
                stopTriggerPrice,
                false,
                stopClientAlgoId);

        String actualTakeProfitClientAlgoId = exchangeGateway.placeProtectiveAlgoOrder(
                plan.symbol(),
                plan.signalType(),
                takeProfitTriggerPrice,
                true,
                takeProfitClientAlgoId);

        return PlacementResult.accepted(
                plan.symbol(),
                plan.signalType(),
                quantity,
                stopTriggerPrice,
                takeProfitTriggerPrice,
                actualEntryClientOrderId,
                actualStopClientAlgoId,
                actualTakeProfitClientAlgoId);
    }

    private String clientId(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        return "BOT_" + prefix + "_" + suffix;
    }
}