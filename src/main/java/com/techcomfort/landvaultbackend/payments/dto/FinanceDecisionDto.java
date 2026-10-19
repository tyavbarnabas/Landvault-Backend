package com.techcomfort.landvaultbackend.payments.dto;

import com.techcomfort.landvaultbackend.payments.AllocationLetterLookup;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Optional;
import java.util.UUID;

/** The outcome of a finance decision. */
public record FinanceDecisionDto(
        UUID transactionId,
        @Schema(description = "verified (plot sold), rejected (plot back on sale) or refund_due (late money sent to refund)")
        String status,
        @Schema(description = "The buyer's allocation letter, issued with the sale. Set when `status` is `verified`, "
                + "null otherwise.", nullable = true)
        UUID allocationLetterId,
        @Schema(description = "Its printed number, e.g. HT-ALC-2026-000002. Null unless `verified`.", nullable = true)
        String allocationLetterNumber
) {

    public static FinanceDecisionDto of(UUID transactionId, String status) {
        return new FinanceDecisionDto(transactionId, status, null, null);
    }

    public static FinanceDecisionDto verified(UUID transactionId, Optional<AllocationLetterLookup.LetterRef> letter) {
        return new FinanceDecisionDto(transactionId, "verified",
                letter.map(AllocationLetterLookup.LetterRef::id).orElse(null),
                letter.map(AllocationLetterLookup.LetterRef::number).orElse(null));
    }
}
