package com.techcomfort.landvaultbackend.payments.dto;

import com.techcomfort.landvaultbackend.payments.AllocationLetterLookup;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Optional;
import java.util.UUID;

/** The outcome of a finance decision. */
public record FinanceDecisionDto(
        UUID transactionId,
        @Schema(description = "verified (plot sold), on_plan (an instalment plan's deposit or instalment verified; the "
                + "plot is held on the plan), rejected (plot back on sale), refund_due (late money sent to refund) or withdrawn (a payment on a plan the "
                + "buyer withdrew from: verifying releases the developer's share; rejecting returns it to the buyer)")
        String status,
        @Schema(description = "The buyer's allocation letter, issued with the sale — or, for a "
                + "deposit (`on_plan`), the provisional letter. Null otherwise.", nullable = true)
        UUID allocationLetterId,
        @Schema(description = "Its printed number, e.g. HT-ALC-2026-000002. HT-PAL-… for a provisional letter. Null when no "
                + "letter was issued.", nullable = true)
        String allocationLetterNumber
) {

    public static FinanceDecisionDto of(UUID transactionId, String status) {
        return new FinanceDecisionDto(transactionId, status, null, null);
    }

    public static FinanceDecisionDto verified(UUID transactionId, Optional<AllocationLetterLookup.LetterRef> letter) {
        return withLetter(transactionId, "verified", letter);
    }

    /** A plan's deposit verified: the plot is held on the plan, with its provisional letter. */
    public static FinanceDecisionDto depositVerified(UUID transactionId,
                                                     Optional<AllocationLetterLookup.LetterRef> letter) {
        return withLetter(transactionId, "on_plan", letter);
    }

    private static FinanceDecisionDto withLetter(UUID transactionId, String status,
                                                 Optional<AllocationLetterLookup.LetterRef> letter) {
        return new FinanceDecisionDto(transactionId, status,
                letter.map(AllocationLetterLookup.LetterRef::id).orElse(null),
                letter.map(AllocationLetterLookup.LetterRef::number).orElse(null));
    }
}
