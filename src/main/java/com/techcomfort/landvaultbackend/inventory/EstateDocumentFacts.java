package com.techcomfort.landvaultbackend.inventory;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.DueTrigger;
import com.techcomfort.landvaultbackend.common.FeeType;
import com.techcomfort.landvaultbackend.common.RefundAppliesTo;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What a generated letter needs to know about an estate: where it is, which
 * branch owns it (for the letterhead), and its current cost disclosure, with
 * the version of each part so a letter can record what it was written from.
 *
 * @param fees         null when the estate never declared a schedule (grandfathered listings);
 *                     an empty list when it declared there are no extra charges
 * @param refundTerms  null when none are declared
 * @param defaultTerms null when none are declared
 */
public record EstateDocumentFacts(
        UUID estateId,
        UUID tenantId,
        UUID branchId,
        String name,
        String area,
        String city,
        String state,
        Fees fees,
        RefundTerms refundTerms,
        DefaultTerms defaultTerms
) {

    public record Fees(int version, List<Fee> items) {
    }

    /** A fixed fee carries {@code amount}; a variable one {@code amountMin}/{@code amountMax} and why it varies. */
    public record Fee(FeeType type, String label, BigDecimal amount, BigDecimal amountMin, BigDecimal amountMax,
                      Currency currency, boolean fixed, String variationBasis, DueTrigger dueTrigger,
                      boolean refundable, boolean mandatory) {
    }

    public record RefundTerms(int version, BigDecimal deductionPct, int processingDays, RefundAppliesTo appliesTo,
                              List<FeeType> nonRefundableFeeTypes) {
    }

    /** @param penaltySteps the declared late-payment steps, earliest first; empty when none */
    public record DefaultTerms(int version, String revocationTrigger, Integer revocationNoticeDays,
                               String onRevocationRefund, Integer developmentDeadlineMonths,
                               boolean transferRequiresConsent, List<PenaltyStep> penaltySteps) {
    }

    public record PenaltyStep(int monthsLate, BigDecimal penaltyPct) {
    }
}
