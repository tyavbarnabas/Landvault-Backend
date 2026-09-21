package com.techcomfort.landvaultbackend.inventory.dto;

import com.techcomfort.landvaultbackend.common.Currency;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * {@code POST /api/portal/estates/{id}/price-tiers} body.
 * <p>
 * {@code sizeSqm} is required for a {@code LAND_SIZE} tier and absent for a
 * {@code UNIT_TYPE} one, where {@code label} carries the meaning instead. The
 * database enforces that too, but the service checks first so the caller gets
 * a clean 400 rather than a constraint violation.
 * <p>
 * {@code price} is the developer's own figure for this tier — <strong>never
 * derived from a per-sqm rate</strong>. Per-sqm is a displayed comparison
 * computed for the UI, never an input.
 */
public record CreatePriceTierRequest(
        @NotNull String tierType,
        @Positive BigDecimal sizeSqm,
        @NotNull @Positive BigDecimal price,
        @NotNull Currency currency,
        String label
) {
}
