package com.techcomfort.landvaultbackend.inventory.dto;

import java.time.Instant;
import java.util.UUID;

/** An estate's declared state and whether a Super Admin has verified it by hand (SB-1). */
public record EstateStateOverrideDto(
        UUID estateId,
        String state,
        String stateCode,
        Instant overriddenAt,
        UUID overriddenBy,
        String reason
) {
}
