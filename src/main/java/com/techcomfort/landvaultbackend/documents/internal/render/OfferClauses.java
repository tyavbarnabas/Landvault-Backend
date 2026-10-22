package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.common.CommitmentCalculator;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.InstalmentPlanCalculator;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentFacts;

import java.math.BigDecimal;
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
    public static final int GRACE_DAYS = 7;

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
        clauses.add("That you have chosen the " + schedule.planName() + ": " + DocumentFormats.money(schedule.planTotal(),
                currency) + " in all" + (schedule.premium().signum() == 0 ? ""
                : " (" + DocumentFormats.money(schedule.premium(), currency) + " more than paying in full)")
                + ", made up of a deposit of " + DocumentFormats.money(schedule.deposit(), currency)
                + ", paid before the plot is held for you, then " + instalments.size() + " " + schedule.frequency()
                + " instalment" + (instalments.size() == 1 ? "" : "s") + " of " + DocumentFormats.money(regular, currency)
                + (last.compareTo(regular) == 0 ? "" : " (the last " + DocumentFormats.money(last, currency) + ")") + ".");
        clauses.add("That the instalments fall due on " + String.join(", ", instalments.stream()
                .map(i -> DocumentFormats.date(i.dueDate())).toList()) + ".");

        if (facts.fees() != null) {
            CommitmentCalculator.TierCommitment commitment = CommitmentCalculator.forTier(schedule.planTotal(),
                    currency.name(), null, facts.fees().items().stream().map(OfferClauses::declared).toList());
            StringBuilder clause = new StringBuilder(commitment.oneOffFees().max().signum() == 0
                    ? "That there are no other one-off charges, so your total commitment on this plan is "
                    + DocumentFormats.money(schedule.planTotal(), currency) + "."
                    : "That with the one-off charges set out below, your total commitment on this plan is "
                    + amount(commitment.totalCommitment(), currency) + ".");
            if (commitment.recurringFees().max().signum() > 0) {
                clause.append(" Charges that recur, such as yearly fees, are in addition to this.");
            }
            if (!commitment.excludedCurrency().isEmpty()) {
                clause.append(" Charges in another currency are not included in it.");
            }
            clauses.add(clause.toString());
        }

        List<EstateDocumentFacts.PenaltyStep> steps = facts.defaultTerms() == null ? List.of()
                : facts.defaultTerms().penaltySteps();
        clauses.add(steps.isEmpty()
                ? "That an instalment unpaid " + GRACE_DAYS + " days after its due date is in arrears; no "
                + "late-payment penalty is declared for this estate."
                : "That an instalment unpaid " + GRACE_DAYS + " days after its due date is in arrears, and a "
                + "late-payment penalty applies to that instalment: " + String.join("; ", steps.stream()
                .map(step -> step.penaltyPct().stripTrailingZeros().toPlainString() + "% ("
                        + DocumentFormats.money(InstalmentPlanCalculator.penaltyOn(regular, step.penaltyPct()), currency)
                        + " on a " + DocumentFormats.money(regular, currency) + " instalment) once it is "
                        + step.monthsLate() + " month" + (step.monthsLate() == 1 ? "" : "s") + " late")
                .toList()) + ". The highest step reached applies; steps are not added together.");
        return clauses;
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
