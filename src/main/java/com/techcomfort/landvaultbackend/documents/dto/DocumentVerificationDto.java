package com.techcomfort.landvaultbackend.documents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * What the public verify page may say about a document: that HomeTrue issued
 * it, and what it is about. Never an amount, and never the holder's name,
 * address or phone — only initials.
 */
@Schema(name = "DocumentVerification")
public record DocumentVerificationDto(
        @Schema(description = "`valid` or `refunded` (the payment was later returned). Unknown codes are a 404.")
        String status,
        @Schema(description = "`receipt` or `allocation_letter`") String documentType,
        String number,
        Instant issuedAt,
        @Schema(description = "Always `HomeTrue` for a generated document.") String issuedBy,
        @Schema(description = "The developer the payment or allocation was for.") String developer,
        String estate,
        @Schema(nullable = true) String location,
        @Schema(description = "e.g. `Block A, Plot 7`") String plot,
        @Schema(description = "The holder's initials only, e.g. `T. B. S.`") String holderInitials,
        @Schema(nullable = true) Instant refundedAt
) {
}
