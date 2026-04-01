package com.example.futuresbot.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record SymbolRules(
        String symbol,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        BigDecimal tickSize,
        BigDecimal lotMinQty,
        BigDecimal lotMaxQty,
        BigDecimal lotStepSize,
        BigDecimal marketMinQty,
        BigDecimal marketMaxQty,
        BigDecimal marketStepSize,
        BigDecimal minNotional,
        BigDecimal triggerProtect) {
    public BigDecimal normalizePrice(BigDecimal price) {
        return normalizeWithBounds(price, minPrice, maxPrice, tickSize);
    }

    public BigDecimal normalizeMarketQuantity(BigDecimal quantity) {
        return normalizeWithBounds(quantity, marketMinQty, marketMaxQty, marketStepSize);
    }

    private BigDecimal normalizeWithBounds(BigDecimal value, BigDecimal min, BigDecimal max, BigDecimal step) {
        if (value == null || value.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal bounded = value;
        if (max != null && max.signum() > 0 && bounded.compareTo(max) > 0) {
            bounded = max;
        }
        if (min != null && bounded.compareTo(min) < 0) {
            return BigDecimal.ZERO;
        }
        if (step == null || step.signum() == 0) {
            return bounded;
        }

        BigDecimal offset = bounded.subtract(min);
        BigDecimal units = offset.divide(step, 0, RoundingMode.DOWN);
        BigDecimal normalized = min.add(units.multiply(step));
        return normalized.setScale(Math.max(step.scale(), 0), RoundingMode.DOWN);
    }
}