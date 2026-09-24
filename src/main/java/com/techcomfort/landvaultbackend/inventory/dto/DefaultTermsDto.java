package com.techcomfort.landvaultbackend.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** An estate's current default, revocation and transfer terms. */
@Schema(
        name = "DefaultTerms",
        description = """
                What happens if the buyer falls behind or fails a condition.

                `onRevocationRefund` is required because both source letters were silent on it — \
                "your allocation may be revoked" without saying what happens to money already paid \
                is not a disclosure.

                `developmentDeadlineMonths` is stored but **not tracked**: nothing watches the \
                clock or warns as it approaches. `transferRequiresConsent` is disclosed but **not \
                enforced** — there is no resale flow yet.""")
public record DefaultTermsDto(
        int version,
        String revocationTrigger,
        @Schema(nullable = true) Integer revocationNoticeDays,
        @Schema(description = "What happens to money already paid.") String onRevocationRefund,
        @Schema(description = "Declared only; nothing surfaces it yet.", nullable = true)
        Integer developmentDeadlineMonths,
        @Schema(description = "Disclosed only; resale does not exist to enforce it.")
        boolean transferRequiresConsent,
        List<PenaltyTierDto> penaltyTiers,
        @Schema(nullable = true) String notes
) {
}
