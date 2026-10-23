package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.common.DueTrigger;
import com.techcomfort.landvaultbackend.common.FeeType;
import com.techcomfort.landvaultbackend.common.RefundAppliesTo;
import com.techcomfort.landvaultbackend.common.RevocationTerms;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentFacts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The conditions an allocation letter writes itself, from the estate's cost
 * disclosure (DS-3): every figure the buyer was shown before paying, worded as
 * the letter's clauses, so the letter can never disagree with the disclosure.
 * Pure: the same facts always give the same words.
 */
public final class DisclosureClauses {

    /** Always the last clause (DS-4). */
    public static final String PAY_ONLY_THROUGH_HOMETRUE = "That all payments are made only through HomeTrue. "
            + "Neither HomeTrue nor the developer will ask the allottee to pay into a bank account named in a "
            + "letter, message or flyer.";

    private DisclosureClauses() {
    }

    public static List<String> of(EstateDocumentFacts facts) {
        List<String> clauses = new ArrayList<>();
        if (facts.fees() != null) {
            if (facts.fees().items().isEmpty()) {
                clauses.add("That no charges apply beyond the price of the land.");
            }
            for (EstateDocumentFacts.Fee fee : facts.fees().items()) {
                clauses.add(fee(fee));
            }
        }
        if (facts.refundTerms() != null) {
            clauses.add(refund(facts.refundTerms()));
        }
        EstateDocumentFacts.DefaultTerms terms = facts.defaultTerms();
        if (terms != null) {
            if (terms.developmentDeadlineMonths() != null) {
                clauses.add("That the allottee shall begin development within " + terms.developmentDeadlineMonths()
                        + " months of allocation.");
            }
            clauses.add(revocation(terms));
            if (terms.transferRequiresConsent()) {
                clauses.add("That the allottee may not sell, transfer or assign the plot without the developer's "
                        + "written consent.");
            }
        }
        clauses.add(PAY_ONLY_THROUGH_HOMETRUE);
        return clauses;
    }

    /** The disclosure parts the letter would have to leave out, for the developer to complete. */
    public static List<String> missing(EstateDocumentFacts facts) {
        List<String> missing = new ArrayList<>();
        if (facts.fees() == null) {
            missing.add("fees");
        }
        if (facts.refundTerms() == null) {
            missing.add("refund_terms");
        }
        if (facts.defaultTerms() == null) {
            missing.add("default_terms");
        }
        return missing;
    }

    static String fee(EstateDocumentFacts.Fee fee) {
        String name = feeName(fee);
        String amount = fee.fixed() || fee.amountMin() == null
                ? DocumentFormats.money(fee.amount(), fee.currency())
                : "between " + DocumentFormats.money(fee.amountMin(), fee.currency()) + " and "
                + DocumentFormats.money(fee.amountMax(), fee.currency())
                + (blank(fee.variationBasis()) ? "" : ", depending on " + trimEnd(fee.variationBasis()));
        if (fee.dueTrigger() == DueTrigger.ANNUAL) {
            return "That " + article(name, fee.mandatory()) + " of " + amount
                    + " is payable every year for as long as the allottee holds the plot.";
        }
        return "That " + article(name, fee.mandatory()) + " of " + amount + " is payable " + when(fee.dueTrigger())
                + (fee.refundable() ? "." : ", and is not refundable.");
    }

    static String refund(EstateDocumentFacts.RefundTerms terms) {
        String basis = terms.appliesTo() == RefundAppliesTo.AMOUNT_PAID ? "the amount paid" : "the total price";
        String kept = terms.nonRefundableFeeTypes().isEmpty() ? "" : "; the "
                + terms.nonRefundableFeeTypes().stream().map(DisclosureClauses::feeTypeName)
                .collect(Collectors.joining(" and ")) + " " + (terms.nonRefundableFeeTypes().size() == 1 ? "is" : "are")
                + " not refunded";
        if (terms.deductionPct() == null || terms.deductionPct().signum() == 0) {
            return "That if the allottee withdraws, the amount paid is refunded in full within "
                    + terms.processingDays() + " days" + kept + ".";
        }
        return "That if the allottee withdraws, " + percent(terms.deductionPct()) + " of " + basis
                + " is deducted and the balance refunded within " + terms.processingDays() + " days" + kept + ".";
    }

    static String revocation(EstateDocumentFacts.DefaultTerms terms) {
        // Phase 5b: HomeTrue's wording from the declared number — never the developer's own text.
        RevocationTerms revocation = terms.revocation();
        int months = revocation.afterMonths();
        return "That, for a plot bought on an instalment plan, the allocation may be revoked if an instalment "
                + "remains unpaid " + months + " month" + (months == 1 ? "" : "s") + " after its due date, after at "
                + "least " + revocation.noticeDays() + " days' written notice, during which paying what is overdue "
                + "stops it. If it is revoked, the allottee is refunded exactly what withdrawing from the plan "
                + "would return.";
    }

    private static String feeName(EstateDocumentFacts.Fee fee) {
        return fee.type() == FeeType.OTHER && !blank(fee.label()) ? fee.label().strip() : feeTypeName(fee.type());
    }

    private static String feeTypeName(FeeType type) {
        return switch (type) {
            case APPLICATION -> "application fee";
            case SETTING_OUT -> "setting-out fee";
            case INFRASTRUCTURE -> "infrastructure fee";
            case CONSTRUCTION_SUPERVISION -> "construction supervision fee";
            case FACILITY_MANAGEMENT -> "facility management fee";
            case SURVEY -> "survey fee";
            case LEGAL -> "legal fee";
            case OTHER -> "other charge";
        };
    }

    private static String when(DueTrigger trigger) {
        return switch (trigger) {
            case AT_APPLICATION -> "on application";
            case AT_ALLOCATION -> "on allocation";
            case ON_CONSTRUCTION_START -> "when construction starts";
            case ON_MILESTONE -> "at the agreed construction milestones";
            case ANNUAL -> "every year";
            case BEFORE_OCCUPATION -> "before the building is occupied";
        };
    }

    /** "an infrastructure fee", "a legal fee", "an optional survey fee". */
    private static String article(String name, boolean mandatory) {
        String phrase = mandatory ? name : "optional " + name;
        return ("aeiou".indexOf(Character.toLowerCase(phrase.charAt(0))) >= 0 ? "an " : "a ") + phrase;
    }

    private static String percent(BigDecimal pct) {
        return pct.stripTrailingZeros().toPlainString() + "%";
    }

    private static String trimEnd(String text) {
        String t = Objects.requireNonNullElse(text, "").strip();
        while (t.endsWith(".")) {
            t = t.substring(0, t.length() - 1).strip();
        }
        return t;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
