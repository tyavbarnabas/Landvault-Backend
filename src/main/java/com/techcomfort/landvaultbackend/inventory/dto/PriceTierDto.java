package com.techcomfort.landvaultbackend.inventory.dto;

import com.techcomfort.landvaultbackend.common.Currency;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code sizeSqm} is null for a {@code UNIT_TYPE} tier, where {@code label} carries the meaning. */
public record PriceTierDto(
        UUID id,
        UUID estateId,
        String tierType,
        BigDecimal sizeSqm,
        BigDecimal price,
        Currency currency,
        String label
) {
}
