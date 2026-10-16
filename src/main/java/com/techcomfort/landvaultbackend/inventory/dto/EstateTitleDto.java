package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An estate's title as the developer declared it, and what HomeTrue decided.
 * {@code pending} means declared and not yet checked by anyone else — never
 * show it as verified.
 */
@Schema(name = "EstateTitleDto")
public record EstateTitleDto(
        UUID id,
        UUID estateId,
        @Schema(description = "`C of O`, `R of O`, `Governor's Consent` or `Gazette`.")
        String titleType,
        String titleNumber,
        LocalDate issuedDate,
        @Schema(description = "`pending` (declared, not yet checked), `verified` or `rejected` — decided by HomeTrue only.")
        String status,
        @Schema(description = "The title page, then the survey plan, then the deed when there is one.")
        List<TitleDocumentDto> documents,
        Instant submittedAt,
        @Schema(description = "Who submitted it. Null for titles recorded before review existed.")
        UUID submittedBy,
        Instant decidedAt,
        UUID decidedBy,
        @Schema(description = "`manual_review` or `registry_search` once verified; otherwise null.")
        String verificationSource,
        @Schema(description = "HomeTrue's note on the decision. Always present on a rejection.")
        String decisionNote
) {
}
