package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 5c: a plan's restructure requests — the open one, if any, and every
 * earlier one. The same shape for the buyer and the developer.
 */
@Schema(name = "PlanRestructures")
public record RestructureDto(
        UUID transactionId,
        String transactionReference,
        @Schema(description = "The purchase's status: `on_plan` while restructuring is possible.") String planStatus,
        @Schema(description = "The open request or proposal, if any.", nullable = true) Restructure open,
        @Schema(description = "Every request on this plan, newest first, including the open one.")
        List<Restructure> history
) {

    @Schema(name = "PlanRestructure")
    public record Restructure(
            UUID id,
            @Schema(description = "`requested`, `proposed`, `accepted`, `rejected`, `declined`, `cancelled`, "
                    + "`withdrawn` or `lapsed`.") String status,
            @Schema(description = "The buyer's reason.") String reason,
            @Schema(nullable = true) Integer preferredInstalments,
            Instant requestedAt,
            @Schema(description = "The developer's proposal, once made.", nullable = true) Proposal proposal,
            @Schema(nullable = true) Instant acceptedAt,
            @Schema(nullable = true) Instant closedAt,
            @Schema(description = "The developer's reason for a rejection, or why it closed.", nullable = true)
            String closeNote
    ) {
    }

    /**
     * What a proposal changes. The total owed never changes: {@code balance} is
     * spread over the new instalments, and {@code carriedPenalty} (penalties
     * already reached) is payable with the first one.
     */
    @Schema(name = "RestructureProposal")
    public record Proposal(
            @Schema(nullable = true) Instant proposedAt,
            @Schema(description = "The last day (Lagos) the buyer may accept; it lapses after.", nullable = true)
            LocalDate expiresOn,
            @Schema(description = "Instalments already paid; they stay paid, with their numbers.")
            int paidInstalments,
            int instalmentCount,
            @Schema(description = "`monthly` or `quarterly`.") String frequency,
            LocalDate firstDueDate,
            @Schema(description = "Still owed, penalties aside: what the new instalments add up to.")
            BigDecimal balance,
            @Schema(description = "Penalties already reached — kept, payable with the first new instalment.")
            BigDecimal carriedPenalty,
            String currency,
            List<NewInstalment> instalments,
            @Schema(description = "The restructure letter to accept (in the buyer's documents).", nullable = true)
            UUID letterId,
            @Schema(nullable = true) String letterNumber
    ) {
    }

    @Schema(name = "RestructuredInstalment")
    public record NewInstalment(int sequence, LocalDate dueDate, BigDecimal amount, BigDecimal carriedPenalty) {
    }
}
