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
        BigDecimal effectiveMinQty = maxPositive(lotMinQty, marketMinQty);
        BigDecimal effectiveMaxQty = minPositive(lotMaxQty, marketMaxQty);
        BigDecimal effectiveStepSize = maxPositive(lotStepSize, marketStepSize);
        return normalizeWithBounds(quantity, effectiveMinQty, effectiveMaxQty, effectiveStepSize);
    }

    private BigDecimal normalizeWithBounds(BigDecimal value, BigDecimal min, BigDecimal max, BigDecimal step) {
        if (value == null || value.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal effectiveMin = positiveOrZero(min);
        BigDecimal bounded = value;

        if (max != null && max.signum() > 0 && bounded.compareTo(max) > 0) {
            bounded = max;
        }
        if (bounded.compareTo(effectiveMin) < 0) {
            return BigDecimal.ZERO;
        }

        if (step == null || step.signum() <= 0) {
            return bounded.stripTrailingZeros();
        }

        BigDecimal strippedStep = step.stripTrailingZeros();
        int effectiveScale = Math.max(strippedStep.scale(), 0);

        BigDecimal offset = bounded.subtract(effectiveMin);
        BigDecimal units = offset.divide(step, 0, RoundingMode.DOWN);
        BigDecimal normalized = effectiveMin.add(units.multiply(step));

        if (normalized.compareTo(effectiveMin) < 0) {
            return BigDecimal.ZERO;
        }

        return normalized.setScale(effectiveScale, RoundingMode.DOWN);
    }

    private BigDecimal maxPositive(BigDecimal a, BigDecimal b) {
        BigDecimal pa = positiveOrZero(a);
        BigDecimal pb = positiveOrZero(b);
        return pa.max(pb);
    }

    private BigDecimal minPositive(BigDecimal a, BigDecimal b) {
        BigDecimal pa = positiveOrNull(a);
        BigDecimal pb = positiveOrNull(b);

        if (pa == null) {
            return pb;
        }
        if (pb == null) {
            return pa;
        }
        return pa.min(pb);
    }

    private BigDecimal positiveOrZero(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : BigDecimal.ZERO;
    }

    private BigDecimal positiveOrNull(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }
}