package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Phase 5c: the developer's new terms for what is still owed. HomeTrue works
 * out the figures; the total owed never changes.
 */
@Schema(name = "RestructureTermsRequest")
public record RestructureTermsRequest(
        @NotNull @Min(1) @Max(60)
        @Schema(description = "How many new instalments; with those already paid, at most 60 in all.", example = "12")
        Integer instalmentCount,
        @NotBlank @Schema(description = "`monthly` or `quarterly`.", example = "monthly") String frequency,
        @NotNull @Schema(description = "When the first new instalment is due: after today, within 3 months.",
                example = "2026-12-01")
        LocalDate firstDueDate
) {
}
