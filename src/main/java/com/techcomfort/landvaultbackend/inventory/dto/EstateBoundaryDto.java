package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The result of adding a boundary: the surveyed area, and what overlap
 * detection found — reported here rather than left for the developer to
 * discover at publish time. Never names another company (CD-11).
 */
public record EstateBoundaryDto(
        UUID estateId,

        @Schema(description = "The boundary's area in square metres, computed by the server.")
        BigDecimal footprintAreaSqm,

        @Schema(description = "True when the new boundary overlaps another company's land. The estate "
                + "cannot be listed until that is resolved.")
        boolean publicationBlocked,

        @Schema(description = "Why publication is blocked, when it is. Never identifies the other party.")
        String blockReason,

        @Schema(description = "Overlaps with your own company's estates. These warn but never block.")
        int warningConflictCount
) {
}
