package com.techcomfort.landvaultbackend.resale.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Sell my plot, phase 1 (SP-1): whether one of the caller's plots may be
 * listed, why not, whether it would be an early sale, and the split at a price.
 * Read only — nothing is listed.
 */
@Schema(name = "ResaleEligibility")
public record ResaleEligibilityDto(
        UUID transactionId,
        String transactionReference,

        @Schema(description = "`paid_in_full`, `installment` or `flexible`; null when the purchase isn't one that "
                + "can be sold (not yet verified, or ended).", nullable = true)
        String kind,

        @Schema(description = "Every condition is met and the plot may be listed now.")
        boolean canList,

        @Schema(description = "Why it can't be listed now, every reason at once; empty when `canList`.")
        List<Reason> reasons,

        @Schema(description = "On a plan, before the profit thresholds: the price is HomeTrue's (the developer's "
                + "current price) and the seller gets back what they paid, no more. False past the thresholds and "
                + "when paid in full; null when `kind` is null.", nullable = true)
        Boolean earlySale,

        String currency,
        @Schema(description = "The price agreed when the purchase opened (land, corner premium included).")
        BigDecimal agreedPrice,
        @Schema(description = "The land share of what finance has verified: each plan payment carries premium in "
                + "proportion, and premium already paid stays with the developer. Penalties excluded. The agreed "
                + "price when paid in full. This is what the thresholds measure and what an early sale returns.")
        BigDecimal paid,
        @Schema(description = "What finance has verified in all, premium included, penalties excluded.")
        BigDecimal paidInAll,
        @Schema(description = "What the developer charges for this plot today; null when the estate isn't on the "
                + "marketplace.", nullable = true) BigDecimal developerPrice,

        @Schema(description = "The lowest price it may be listed at: the agreed price on a plan, null when paid in "
                + "full (any price above zero). On an early sale the only price is `developerPrice`.",
                nullable = true) BigDecimal lowestPrice,

        @Schema(description = "The two profit thresholds on a plan; null when paid in full.", nullable = true)
        Thresholds thresholds,

        @Schema(description = "The estate's terms say a transfer needs the developer's consent: a listing then "
                + "waits off the marketplace until the developer gives it. Null when the estate declares no default "
                + "terms (no consent needed).", nullable = true)
        Boolean consentRequired,

        @Schema(description = "The split at `price` (or, on an early sale or with no price given, at "
                + "`developerPrice`); null when there is no price to quote.", nullable = true)
        Quote quote,

        @Schema(description = "A sentence for the seller saying what selling now returns and, before the "
                + "thresholds, what keeps their share of a gain; null when nothing needs saying.", nullable = true)
        String advice,

        @Schema(description = "What HomeTrue doesn't charge, handle or withhold — said before listing (D30).")
        List<String> notHandled
) {

    /**
     * @param code `not_owned_yet`, `plan_ended`, `kyc_not_approved`, `estate_not_listed`, `in_arrears`,
     *             `notice_running`, `payment_awaiting_finance`, `restructure_open`, `past_finish_by` or
     *             `early_sale_below_agreed_price`
     */
    @Schema(name = "ResaleReason")
    public record Reason(
            @Schema(description = "`not_owned_yet`, `plan_ended`, `kyc_not_approved`, `estate_not_listed`, "
                    + "`in_arrears`, `notice_running`, `payment_awaiting_finance`, `restructure_open`, "
                    + "`past_finish_by` or `early_sale_below_agreed_price`.") String code,
            String message) {
    }

    @Schema(name = "ResaleThresholds")
    public record Thresholds(
            @Schema(description = "30% of the agreed price, verified by finance.") BigDecimal minimumPaid,
            boolean paidMet,
            @Schema(description = "What is still needed to reach `minimumPaid`; zero once met.")
            BigDecimal stillToPay,
            @Schema(description = "The day (Lagos) Paystack confirmed the first payment finance has verified — the "
                    + "clock runs from when the money arrived, not from finance's check.") LocalDate firstPaymentOn,
            @Schema(description = "Six months on from it: the time threshold.") LocalDate timeMetOn,
            boolean timeMet,
            @Schema(description = "Both met: the seller sets the price and keeps their share of a gain.")
            boolean met) {
    }

    @Schema(name = "ResaleQuote")
    public record Quote(
            BigDecimal price,
            @Schema(description = "Still owed on the agreed price, plus penalties owed; paid to the developer first.")
            BigDecimal developerBalance,
            @Schema(description = "Unpaid late-payment penalties included in `developerBalance`.")
            BigDecimal penaltiesOwed,
            @Schema(description = "The price less the agreed price; negative only when paid in full and sold below it.")
            BigDecimal gain,
            @Schema(description = "The developer's share of any gain: 100 on an early sale, the plan's declared share "
                    + "on a flexible plan, 0 otherwise.") BigDecimal developerGainSharePct,
            BigDecimal developerGainShare,
            @Schema(description = "A declared transfer fee; zero — none can be declared yet.") BigDecimal transferFee,
            BigDecimal developerReceives,
            BigDecimal sellerReceives,
            @Schema(description = "More than 50% above the developer's price: a Super Admin looks at the sale before "
                    + "anyone is paid out.") boolean reviewRequired) {
    }
}
