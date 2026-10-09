package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** The outcome of a finance decision. */
public record FinanceDecisionDto(
        UUID transactionId,
        @Schema(description = "verified (plot sold) or rejected (plot back on sale)") String status
) {
}
