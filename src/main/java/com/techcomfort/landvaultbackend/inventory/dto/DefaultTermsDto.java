package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** An estate's current default, revocation and transfer terms. */
@Schema(
        name = "DefaultTerms",
        description = """
                What happens if the buyer falls behind or fails a condition.

                Revocation (phase 5b): an instalment plan may be revoked once an instalment is \
                `revocationAfterMonths` months unpaid, after `noticeDaysGiven` days' notice; the buyer \
                is refunded exactly as on withdrawal.

                `developmentDeadlineMonths` is stored but **not tracked**: nothing watches the \
                clock or warns as it approaches. `transferRequiresConsent` is disclosed but **not \
                enforced** — there is no resale flow yet.""")
public record DefaultTermsDto(
        int version,
        @Schema(description = "Revocable once an instalment is unpaid this many whole months past its due date.")
        int revocationAfterMonths,
        @Schema(description = "As declared.", nullable = true) Integer revocationNoticeDays,
        @Schema(description = "The notice a buyer is actually given: as declared, never under HomeTrue's 14 days.")
        int noticeDaysGiven,
        @Schema(description = "Declared only; nothing surfaces it yet.", nullable = true)
        Integer developmentDeadlineMonths,
        @Schema(description = "Disclosed only; resale does not exist to enforce it.")
        boolean transferRequiresConsent,
        List<PenaltyTierDto> penaltyTiers,
        @Schema(description = "Whether late-payment penalties are refunded to a buyer who withdraws.")
        boolean penaltiesRefundable,
        @Schema(nullable = true) String notes
) {
}
