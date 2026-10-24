package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PaymentFrequency;
import com.techcomfort.landvaultbackend.payments.RestructureLetterRequest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 5c: the restructure letter's wording — HomeTrue's, the same for every
 * developer, like the offer letter's. It states the new instalments exactly,
 * that the total owed is unchanged, that penalties already reached are kept,
 * and that it takes effect only when the buyer accepts it in time.
 */
public final class RestructureClauses {

    public static final String SIGN_OFF = OfferClauses.SIGN_OFF;

    private RestructureClauses() {
    }

    public static String opening(String requestDate, String offerNumber, String plot, String estate, String location,
                                 String company) {
        return "Further to your request of " + requestDate + " to change the payment schedule of your instalment plan "
                + "for " + plot + " at " + estate + (location == null || location.isBlank() ? "" : ", " + location)
                + ", " + company + " agrees to restructure the balance of the plan as set out below"
                + (offerNumber == null ? "." : ", amending offer letter " + offerNumber + ".");
    }

    public static List<String> clauses(RestructureLetterRequest r) {
        Currency currency = Currency.valueOf(r.currency());
        List<PlanSchedule.Instalment> fresh = r.newInstalments();
        PlanSchedule.Instalment first = fresh.getFirst();
        PlanSchedule.Instalment last = fresh.getLast();
        List<String> clauses = new ArrayList<>();
        clauses.add((r.paidInstalments() == 0 ? "That nothing paid so far changes"
                : "That the " + r.paidInstalments() + " instalment" + (r.paidInstalments() == 1 ? "" : "s")
                + " already paid remain paid") + ", and the total owed under your plan is unchanged at "
                + DocumentFormats.money(r.planTotal(), currency) + ".");
        String each = fresh.size() == 1 ? "one instalment of " + DocumentFormats.money(first.amount(), currency)
                : fresh.size() + " " + r.frequency() + " instalments of " + DocumentFormats.money(first.amount(), currency)
                + (last.amount().compareTo(first.amount()) == 0 ? ""
                : " (the last " + DocumentFormats.money(last.amount(), currency) + ")");
        clauses.add("That the balance of " + DocumentFormats.money(r.balance(), currency) + " is payable in " + each
                + ", due " + DueDates.phrase(fresh.stream().map(PlanSchedule.Instalment::dueDate).toList(),
                PaymentFrequency.fromValue(r.frequency())) + " (instalments " + first.sequence() + " to "
                + last.sequence() + ").");
        if (r.carriedPenalty().signum() > 0) {
            clauses.add("That late-payment penalties of " + DocumentFormats.money(r.carriedPenalty(), currency)
                    + " reached before this restructure are kept, and are payable with instalment " + first.sequence()
                    + " on " + DocumentFormats.date(first.dueDate()) + ".");
        }
        clauses.add("That lateness and any late-payment penalty are counted from the new due dates, on the terms of "
                + "your offer letter, which are otherwise unchanged — including what is refunded if you withdraw or "
                + "the plan is revoked.");
        clauses.add(DisclosureClauses.PAY_ONLY_THROUGH_HOMETRUE);
        return clauses;
    }

    public static String closing(LocalDate expiresOn) {
        return "To accept, press Accept in your HomeTrue account by the end of " + DocumentFormats.date(expiresOn)
                + ". Until you do, your current schedule continues; if you don't, this letter lapses and you may ask "
                + "again. Accepting is recorded with its date and time.";
    }
}
