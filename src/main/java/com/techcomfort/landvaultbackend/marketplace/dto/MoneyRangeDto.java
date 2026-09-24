package com.techcomfort.landvaultbackend.marketplace.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * An amount that may not be a single number.
 * <p>
 * {@code min} equals {@code max} for anything fixed. When they differ the
 * figure is genuinely a range, and <strong>must be shown as one</strong> —
 * never as a midpoint, which is a number nobody quoted and nobody is bound
 * by.
 */
@Schema(
        name = "MoneyRange",
        description = "An amount. `min` equals `max` when it is fixed; when they differ, show the "
                + "range — never a midpoint.")
public record MoneyRangeDto(BigDecimal min, BigDecimal max, boolean isRange) {
}
