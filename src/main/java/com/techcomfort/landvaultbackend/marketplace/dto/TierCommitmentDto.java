package com.techcomfort.landvaultbackend.marketplace.dto;

import com.techcomfort.landvaultbackend.common.Currency;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * What one price tier actually commits a buyer to — the figure this whole
 * feature exists to put in front of them while the decision is still open.
 */
@Schema(
        name = "TierCommitment",
        description = """
                The true cost of this tier, not the advertised one.

                Two estates at ₦6,000,000 are not the same offer when one carries ₦4,000,000 of \
                fees, and only the platform is in a position to make that visible.

                **`totalCommitment` is the land price plus mandatory one-off charges.** Recurring \
                charges (`recurringFees`, typically an annual facility fee) are reported separately \
                rather than folded in — one year of a perpetual charge is an arbitrary thing to add \
                to a purchase price. Genuinely avoidable charges are in `optionalFees`, also \
                outside the total.

                **`totalCommitmentIfCorner`** is the same figure for a corner plot, which really \
                does cost more. Null when the estate charges no corner premium.

                **`totalExcludesOtherCurrencyFees`** means at least one declared fee is in a \
                different currency from this tier and has therefore been left out of every total \
                here — amounts in different currencies are never summed. The excluded charges are \
                still listed in the estate's fee breakdown.""")
public record TierCommitmentDto(
        @Schema(description = "The advertised figure, and the only one most listings show anywhere.")
        BigDecimal landPrice,

        Currency currency,

        @Schema(description = "Mandatory charges falling due once.") MoneyRangeDto oneOffFees,

        @Schema(description = "Land price plus one-off fees.") MoneyRangeDto totalCommitment,

        @Schema(description = "Null when the estate charges no corner premium.", nullable = true)
        MoneyRangeDto totalCommitmentIfCorner,

        @Schema(description = "Charges that repeat, e.g. annual facility management. Outside the total.")
        MoneyRangeDto recurringFees,

        @Schema(description = "Charges the buyer can genuinely avoid. Outside the total.")
        MoneyRangeDto optionalFees,

        @Schema(description = "True when a fee in another currency was left out of every total above.")
        boolean totalExcludesOtherCurrencyFees,

        @Schema(description = "False only when the advertised price really is the whole cost.")
        boolean hasAdditionalCost
) {
}
