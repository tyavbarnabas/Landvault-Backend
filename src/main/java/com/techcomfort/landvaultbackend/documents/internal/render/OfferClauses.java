package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.checkout.FlexiblePurchase;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.common.CommitmentCalculator;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.FlexiblePlanCalculator;
import com.techcomfort.landvaultbackend.common.InstalmentPlanCalculator;
import com.techcomfort.landvaultbackend.common.LatePaymentTerms;
import com.techcomfort.landvaultbackend.common.PaymentFrequency;
import com.techcomfort.landvaultbackend.common.PlanNames;
import com.techcomfort.landvaultbackend.common.RefundAppliesTo;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentFacts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * HomeTrue's fixed wording for an offer letter (OL): the opening, the price
 * and total-commitment clauses printed first, and the closing. Nothing here
 * is the developer's; every figure comes from the purchase and the declared
 * fees. See AGENTS.md, "Documents: the offer letter".
 */
public final class OfferClauses {

    public static final String SIGN_OFF = "We look forward to your acceptance.";

    /** Decided with the user: the same for every estate, for now. */
    public static final String CLOSING = "To accept this offer, press Accept in your HomeTrue account. Your "
            + "acceptance is recorded with its date and time, and payment can be made only after it, and only "
            + "through HomeTrue. This offer is not an allocation: the plot becomes yours only when your payment "
            + "is confirmed and your allocation letter is issued, and the offer lapses if the purchase is not "
            + "completed.";

    private OfferClauses() {
    }

    /** "Following your application of 3 October 2026 for Block A, Plot 7 of about 500 sqm at …". */
    public static String opening(String applicationDate, String plot, String estate, String location, String company) {
        return "Following your application of " + applicationDate + " for " + plot + " at " + estate
                + (location == null || location.isBlank() ? "" : ", " + location) + ", " + company
                + " is pleased to offer you the plot on the terms below.";
    }

    /**
     * Sell my plot (D20): the opening of an offer for a plot bought from its
     * present holder, named by initials only (D21).
     */
    public static String resaleOpening(String applicationDate, String plot, String estate, String location,
                                       String company, String sellerInitials) {
        return "Following your application of " + applicationDate + " for " + plot + " at " + estate
                + (location == null || location.isBlank() ? "" : ", " + location) + ", " + company
                + " is pleased to offer you the plot, which its present holder (" + sellerInitials
                + ") is selling through HomeTrue, on the terms below.";
    }

    /**
     * Sell my plot (D14, D20, D29, D30): a resale's offer, in place of
     * {@link #price}. HomeTrue's fixed wording: the price, paid in full; that
     * the plot passes from its present holder; the developer's consent where
     * the estate needs it; that the conditions below are the ones the plot was
     * first allocated on and its development deadline runs from then; and
     * what HomeTrue neither charges nor collects.
     * <p>
     * The conditions sentence always says that time limits count from the
     * first allocation, and that charges already paid aren't due again: a
     * developer's own wording ("within three months from the date of this
     * allocation", "payable on application") can't be rewritten, and would
     * otherwise read as starting again for the new buyer (found in the walk).
     *
     * @param firstAllocated when the plot was first allocated to the present holder; null if not on record
     */
    public static List<String> resale(BigDecimal price, Currency currency, String sellerInitials, boolean consented,
                                      LocalDate firstAllocated) {
        List<String> clauses = new ArrayList<>();
        clauses.add("That the price of the plot is " + DocumentFormats.money(price, currency) + " ("
                + AmountInWords.of(price, currency) + "), payable in full through HomeTrue. No instalment or "
                + "flexible plan is offered on a resale.");
        clauses.add("That this is a resale: the plot passes to you from its present holder (" + sellerInitials
                + "), who bought it from the developer. Its title is the estate's, as shown on HomeTrue.");
        if (consented) {
            clauses.add("That the developer has consented to this transfer, as the estate's terms require.");
        }
        String first = firstAllocated == null ? "the plot's first allocation"
                : "the plot's first allocation on " + DocumentFormats.date(firstAllocated);
        clauses.add("That the conditions below are those the plot was first allocated on, and pass to you "
                + "unchanged. Any time limit in them, such as for starting work, counts from " + first
                + ", not from the date of this letter; and a charge the present holder paid when they bought the "
                + "plot, such as an application fee, is not due again.");
        clauses.add("That HomeTrue collects only the price above. Yearly charges such as a facility fee, and any "
                + "other charge the present holder may owe the developer, are between you, the present holder and "
                + "the developer: ask the developer before you pay.");
        clauses.add("That fees for Governor's Consent or for registering the transfer, where they apply, are not "
                + "charged or paid by HomeTrue.");
        return clauses;
    }

    /**
     * The price as agreed when the plot was held, then — when fees were
     * declared — the total commitment: the price plus mandatory one-off
     * charges in the same currency. Recurring and other-currency charges are
     * named as outside it, never summed in (FD-2's rules, via
     * {@link CommitmentCalculator}).
     */
    public static List<String> price(BigDecimal basePrice, BigDecimal cornerPremiumPct, BigDecimal totalPrice,
                                     Currency currency, EstateDocumentFacts.Fees fees) {
        List<String> clauses = new ArrayList<>();
        String total = DocumentFormats.money(totalPrice, currency) + " (" + AmountInWords.of(totalPrice, currency) + ")";
        clauses.add(cornerPremiumPct == null
                ? "That the price of the plot is " + total + "."
                : "That the price of the plot is " + total + ": " + DocumentFormats.money(basePrice, currency)
                + " for the plot and a corner premium of " + cornerPremiumPct.stripTrailingZeros().toPlainString()
                + "%.");
        if (fees == null) {
            return clauses;
        }

        CommitmentCalculator.TierCommitment commitment = CommitmentCalculator.forTier(totalPrice, currency.name(),
                null, fees.items().stream().map(OfferClauses::declared).toList());
        StringBuilder clause = new StringBuilder();
        if (commitment.oneOffFees().max().signum() == 0) {
            clause.append("That there are no other one-off charges, so your total commitment is the price above.");
        } else {
            clause.append("That with the one-off charges set out below, your total commitment is ")
                    .append(amount(commitment.totalCommitment(), currency)).append(".");
        }
        if (commitment.recurringFees().max().signum() > 0) {
            clause.append(" Charges that recur, such as yearly fees, are in addition to this.");
        }
        if (!commitment.excludedCurrency().isEmpty()) {
            clause.append(" Charges in another currency are not included in it.");
        }
        clauses.add(clause.toString());
        return clauses;
    }

    /**
     * PL-3: an instalment buyer's offer, in place of {@link #price}: the full
     * price, then the chosen plan — total, premium, deposit, every instalment
     * and its due date — then the total commitment on the plan (one-off fees
     * included), then the declared late-payment steps on one instalment and
     * the 7-day grace period. Every figure is the purchase's own schedule.
     */
    public static List<String> plan(PlanSchedule schedule, BigDecimal basePrice, BigDecimal cornerPremiumPct,
                                    BigDecimal totalPrice, Currency currency, EstateDocumentFacts facts) {
        List<String> clauses = new ArrayList<>();
        String full = DocumentFormats.money(totalPrice, currency) + " (" + AmountInWords.of(totalPrice, currency) + ")";
        clauses.add(cornerPremiumPct == null
                ? "That the price of the plot, paid in full, is " + full + "."
                : "That the price of the plot, paid in full, is " + full + ": " + DocumentFormats.money(basePrice, currency)
                + " for the plot and a corner premium of " + cornerPremiumPct.stripTrailingZeros().toPlainString() + "%.");

        List<PlanSchedule.Instalment> instalments = schedule.instalments();
        BigDecimal regular = instalments.getFirst().amount();
        BigDecimal last = instalments.getLast().amount();
        clauses.add("That you have chosen " + PlanNames.phrase(schedule.planName()) + ": " + DocumentFormats.money(schedule.planTotal(),
                currency) + " in all" + (schedule.premium().signum() == 0 ? ""
                : " (" + DocumentFormats.money(schedule.premium(), currency) + " more than paying in full)")
                + ", made up of a deposit of " + DocumentFormats.money(schedule.deposit(), currency)
                + ", paid before the plot is held for you, then " + instalments.size() + " " + schedule.frequency()
                + " instalment" + (instalments.size() == 1 ? "" : "s") + " of " + DocumentFormats.money(regular, currency)
                + (last.compareTo(regular) == 0 ? "" : " (the last " + DocumentFormats.money(last, currency) + ")") + ".");
        clauses.add("That the instalments fall due " + DueDates.phrase(instalments.stream()
                .map(PlanSchedule.Instalment::dueDate).toList(), PaymentFrequency.fromValue(schedule.frequency())) + ".");

        if (facts.fees() != null) {
            clauses.add(planCommitment(schedule.planTotal(), currency, facts.fees()));
        }

        // Phase 4: the purchase's own late-payment terms, copied when it opened.
        List<LatePaymentTerms.Step> steps = schedule.lateTerms().steps();
        int grace = LatePaymentTerms.GRACE_DAYS;
        clauses.add(steps.isEmpty()
                ? "That an instalment unpaid " + grace + " days after its due date is in arrears; no "
                + "late-payment penalty is declared for this estate."
                : "That an instalment unpaid " + grace + " days after its due date is in arrears, and a "
                + "late-payment penalty applies to that instalment: " + String.join("; ", steps.stream()
                .map(step -> step.penaltyPct().stripTrailingZeros().toPlainString() + "% ("
                        + DocumentFormats.money(InstalmentPlanCalculator.penaltyOn(regular, step.penaltyPct()), currency)
                        + " on a " + DocumentFormats.money(regular, currency) + " instalment) once it is "
                        + step.monthsLate() + " month" + (step.monthsLate() == 1 ? "" : "s") + " late")
                .toList()) + ". The highest step reached applies; steps are not added together. A penalty is "
                + "paid with the instalment it is charged on" + (schedule.lateTerms().penaltiesRefundable()
                ? ", and is refunded on the same terms as your other payments if you withdraw."
                : ", and is not refunded if you withdraw."));
        return clauses;
    }

    /**
     * Flexible contribution, phase 2: a flexible buyer's offer, in place of
     * {@link #price}. The full price; the plan (total, premium, the minimum to
     * start, any minimum contribution, the date to finish by); the total
     * commitment on it; then both ways out in naira — leaving (refunded at
     * once from escrow, two worked figures, then the gain share with its
     * example) — and when a quiet plan may be ended. "Sell my plot" is not
     * offered until resale exists (found in the flexible-plan walkthrough). Every figure comes from the terms copied onto the purchase.
     * The last clause says these terms replace the estate's general
     * withdrawal and revocation terms printed below, which are written for
     * instalment plans.
     */
    public static List<String> flexible(FlexiblePurchase plan, BigDecimal basePrice, BigDecimal cornerPremiumPct,
                                        BigDecimal totalPrice, Currency currency, String company,
                                        EstateDocumentFacts facts) {
        List<String> clauses = new ArrayList<>();
        String full = DocumentFormats.money(totalPrice, currency) + " (" + AmountInWords.of(totalPrice, currency) + ")";
        clauses.add(cornerPremiumPct == null
                ? "That the price of the plot, paid in full, is " + full + "."
                : "That the price of the plot, paid in full, is " + full + ": " + DocumentFormats.money(basePrice, currency)
                + " for the plot and a corner premium of " + cornerPremiumPct.stripTrailingZeros().toPlainString() + "%.");

        String price = DocumentFormats.money(totalPrice, currency);
        StringBuilder chosen = new StringBuilder("That you have chosen " + PlanNames.phrase(plan.planName()) + ": "
                + DocumentFormats.money(plan.planTotal(), currency) + " in all" + (plan.premium().signum() == 0 ? ""
                : " (" + DocumentFormats.money(plan.premium(), currency) + " more than paying in full)")
                + ", paid in any amounts, at any time, with no schedule and no due dates. Your first payment must "
                + "be at least " + DocumentFormats.money(plan.minimumToStart(), currency) + "; the plot is held for "
                + "you once it is confirmed and verified.");
        if (plan.minimumContribution() != null) {
            chosen.append(" Each later payment must be at least ")
                    .append(DocumentFormats.money(plan.minimumContribution(), currency))
                    .append(", except the one that finishes the plan.");
        }
        if (plan.finishBy() != null) {
            chosen.append(" The plan must be finished by ").append(DocumentFormats.date(plan.finishBy())).append(".");
        }
        clauses.add(chosen.toString());

        if (facts.fees() != null) {
            clauses.add(planCommitment(plan.planTotal(), currency, facts.fees()));
        }

        List<FlexiblePlanCalculator.Exit> exits = FlexiblePlanCalculator.exits(plan.minimumToStart(), plan.planTotal(),
                totalPrice, plan.exitDeductionPct(), plan.exitAppliesTo());
        String deduction = plan.exitDeductionPct().stripTrailingZeros().toPlainString() + "%";
        clauses.add("That you may leave the plan at any time, without anyone's approval, and are refunded at once "
                + "from the money HomeTrue holds" + (plan.exitDeductionPct().signum() == 0
                ? ", in full."
                : ", less what " + company + " keeps: " + deduction + (plan.exitAppliesTo() == RefundAppliesTo.TOTAL_PRICE
                ? " of " + price + ", taken from your first payments."
                : " of what you have paid.")) + " For example, " + String.join(", and ", exits.stream()
                .map(e -> "after paying " + DocumentFormats.money(e.paid(), currency) + " you get back "
                        + DocumentFormats.money(e.youGetBack(), currency)).toList()) + ".");

        FlexiblePlanCalculator.Gain gain = FlexiblePlanCalculator.gain(totalPrice, plan.developerGainSharePct());
        String developerShare = gain.developerSharePct().stripTrailingZeros().toPlainString() + "%";
        clauses.add(gain.buyerSharePct().signum() == 0
                ? "That if, after you leave, HomeTrue completes a sale of the plot for more than " + price + ", "
                + company + " keeps all of the difference; your refund is the same whatever it sells for."
                : "That if, after you leave, HomeTrue completes a sale of the plot for more than " + price
                + ", you receive " + gain.buyerSharePct().stripTrailingZeros().toPlainString() + "% of the difference "
                + "and " + company + " keeps " + developerShare + ". For example, if it sold for "
                + DocumentFormats.money(gain.example().soldFor(), currency) + ", you would receive "
                + DocumentFormats.money(gain.example().youGet(), currency) + ". This is an example, not a forecast: "
                + "a sale for no more than " + price + " pays nothing extra, and never reduces your refund.");

        String months = plan.inactivityMonths() + " month" + (plan.inactivityMonths() == 1 ? "" : "s");
        StringBuilder quiet = new StringBuilder("That if you make no payment for " + months + ", "
                + company + " may give you " + plan.noticeDays() + " days' notice and then end the plan on the "
                + "terms for leaving, unless you make a payment within the notice.");
        if (plan.finishBy() != null) {
            quiet.append(" If the plan is not finished by ").append(DocumentFormats.date(plan.finishBy()))
                    .append(", the same notice may be given, and only finishing the plan stops it.");
        }
        clauses.add(quiet.toString());

        clauses.add("That these terms for leaving and for ending the plan take the place of the estate's general "
                + "withdrawal and revocation terms set out below, which apply to instalment plans.");
        return clauses;
    }

    /** The total commitment on a plan's total: one-off fees in; recurring and other-currency ones named outside. */
    private static String planCommitment(BigDecimal planTotal, Currency currency, EstateDocumentFacts.Fees fees) {
        CommitmentCalculator.TierCommitment commitment = CommitmentCalculator.forTier(planTotal,
                currency.name(), null, fees.items().stream().map(OfferClauses::declared).toList());
        StringBuilder clause = new StringBuilder(commitment.oneOffFees().max().signum() == 0
                ? "That there are no other one-off charges, so your total commitment on this plan is "
                + DocumentFormats.money(planTotal, currency) + "."
                : "That with the one-off charges set out below, your total commitment on this plan is "
                + amount(commitment.totalCommitment(), currency) + ".");
        if (commitment.recurringFees().max().signum() > 0) {
            clause.append(" Charges that recur, such as yearly fees, are in addition to this.");
        }
        if (!commitment.excludedCurrency().isEmpty()) {
            clause.append(" Charges in another currency are not included in it.");
        }
        return clause.toString();
    }

    private static String amount(CommitmentCalculator.Money money, Currency currency) {
        return money.isRange()
                ? "between " + DocumentFormats.money(money.min(), currency) + " and "
                + DocumentFormats.money(money.max(), currency)
                : DocumentFormats.money(money.min(), currency);
    }

    private static CommitmentCalculator.DeclaredFee declared(EstateDocumentFacts.Fee fee) {
        boolean single = fee.fixed() || fee.amountMin() == null;
        return new CommitmentCalculator.DeclaredFee(fee.type(), fee.label(), fee.currency().name(),
                single ? fee.amount() : fee.amountMin(), single ? fee.amount() : fee.amountMax(),
                fee.fixed(), fee.mandatory(), fee.dueTrigger());
    }
}
