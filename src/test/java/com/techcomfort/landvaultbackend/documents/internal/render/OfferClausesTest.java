package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.DueTrigger;
import com.techcomfort.landvaultbackend.common.FeeType;
import com.techcomfort.landvaultbackend.common.LatePaymentTerms;
import com.techcomfort.landvaultbackend.common.RevocationTerms;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentFacts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class OfferClausesTest {

    @Test
    void thePriceIsStatedInFiguresAndWordsWithTheCornerPremiumNamed() {
        assertThat(OfferClauses.price(new BigDecimal("20000000"), new BigDecimal("12.50"),
                new BigDecimal("22500000"), Currency.NGN, null))
                .containsExactly("That the price of the plot is ₦22,500,000.00 (Twenty-Two Million, Five Hundred "
                        + "Thousand Naira Only): ₦20,000,000.00 for the plot and a corner premium of 12.5%.");
    }

    /** Double King's own arithmetic: land plus one-off fees; the yearly fee is named outside the total. */
    @Test
    void theTotalCommitmentAddsOneOffFeesAndKeepsRecurringOnesOut() {
        EstateDocumentFacts.Fees fees = new EstateDocumentFacts.Fees(1, List.of(
                fee(FeeType.APPLICATION, "10000", DueTrigger.AT_APPLICATION),
                fee(FeeType.INFRASTRUCTURE, "3500000", DueTrigger.ON_CONSTRUCTION_START),
                fee(FeeType.FACILITY_MANAGEMENT, "50000", DueTrigger.ANNUAL)));

        List<String> clauses = OfferClauses.price(new BigDecimal("6000000"), null, new BigDecimal("6000000"),
                Currency.NGN, fees);

        assertThat(clauses.get(1)).isEqualTo("That with the one-off charges set out below, your total commitment is "
                + "₦9,510,000.00. Charges that recur, such as yearly fees, are in addition to this.");
    }

    @Test
    void anEmptyScheduleSaysThereIsNothingMore() {
        assertThat(OfferClauses.price(new BigDecimal("6000000"), null, new BigDecimal("6000000"), Currency.NGN,
                new EstateDocumentFacts.Fees(1, List.of())).get(1))
                .isEqualTo("That there are no other one-off charges, so your total commitment is the price above.");
    }

    private static EstateDocumentFacts.Fee fee(FeeType type, String amount, DueTrigger due) {
        return new EstateDocumentFacts.Fee(type, null, new BigDecimal(amount), null, null, Currency.NGN, true, null,
                due, false, true);
    }

    private static PlanSchedule schedule(LatePaymentTerms late) {
        return new PlanSchedule(UUID.randomUUID(), "12-month plan", "monthly",
                new BigDecimal("16500000"), new BigDecimal("1500000"), new BigDecimal("4950000"),
                IntStream.rangeClosed(1, 12).mapToObj(n -> new PlanSchedule.Instalment(n,
                        LocalDate.of(2026, 10, 22).plusMonths(n), new BigDecimal("962500"))).toList(), late);
    }

    private static final LatePaymentTerms TWO_STEPS = new LatePaymentTerms(List.of(
            new LatePaymentTerms.Step(3, new BigDecimal("10")), new LatePaymentTerms.Step(1, new BigDecimal("5"))),
            false);

    /**
     * PL-3, the agreed example: ₦15M, 30% deposit, 12 monthly, 10% premium; fees added; steps on one instalment.
     * Phase 4: the steps are the purchase's own copy (not the estate's current terms), and whether they're refunded.
     */
    @Test
    void anInstalmentOfferStatesThePlanEveryDueDateTheCommitmentAndThePenalties() {
        PlanSchedule schedule = schedule(TWO_STEPS);
        EstateDocumentFacts facts = new EstateDocumentFacts(UUID.randomUUID(), UUID.randomUUID(), null, "Lugbe",
                null, null, null,
                new EstateDocumentFacts.Fees(1, List.of(fee(FeeType.APPLICATION, "10000", DueTrigger.AT_APPLICATION),
                        fee(FeeType.INFRASTRUCTURE, "1500000", DueTrigger.ON_CONSTRUCTION_START),
                        fee(FeeType.FACILITY_MANAGEMENT, "60000", DueTrigger.ANNUAL))),
                null,
                new EstateDocumentFacts.DefaultTerms(1, new RevocationTerms(3, 30), null,
                        false, List.of(new EstateDocumentFacts.PenaltyStep(2, new BigDecimal("50")))));

        List<String> clauses = OfferClauses.plan(schedule, new BigDecimal("15000000"), null,
                new BigDecimal("15000000"), Currency.NGN, facts);

        assertThat(clauses).containsExactly(
                "That the price of the plot, paid in full, is ₦15,000,000.00 (Fifteen Million Naira Only).",
                "That you have chosen the 12-month plan: ₦16,500,000.00 in all (₦1,500,000.00 more than paying in "
                        + "full), made up of a deposit of ₦4,950,000.00, paid before the plot is held for you, then 12 "
                        + "monthly instalments of ₦962,500.00.",
                "That the instalments fall due on 22 November 2026, 22 December 2026, 22 January 2027, "
                        + "22 February 2027, 22 March 2027, 22 April 2027, 22 May 2027, 22 June 2027, 22 July 2027, "
                        + "22 August 2027, 22 September 2027, 22 October 2027.",
                "That with the one-off charges set out below, your total commitment on this plan is ₦18,010,000.00. "
                        + "Charges that recur, such as yearly fees, are in addition to this.",
                "That an instalment unpaid 7 days after its due date is in arrears, and a late-payment penalty "
                        + "applies to that instalment: 5% (₦48,125.00 on a ₦962,500.00 instalment) once it is 1 month "
                        + "late; 10% (₦96,250.00 on a ₦962,500.00 instalment) once it is 3 months late. The highest "
                        + "step reached applies; steps are not added together. A penalty is paid with the instalment "
                        + "it is charged on, and is not refunded if you withdraw.");
    }

    @Test
    void refundablePenaltiesSaySoAndNoStepsMeansNoPenalty() {
        EstateDocumentFacts facts = new EstateDocumentFacts(UUID.randomUUID(), UUID.randomUUID(), null, "Lugbe",
                null, null, null, null, null, null);

        assertThat(OfferClauses.plan(schedule(new LatePaymentTerms(TWO_STEPS.steps(), true)),
                new BigDecimal("15000000"), null, new BigDecimal("15000000"), Currency.NGN, facts).getLast())
                .endsWith("A penalty is paid with the instalment it is charged on, and is refunded on the same terms "
                        + "as your other payments if you withdraw.");
        assertThat(OfferClauses.plan(schedule(LatePaymentTerms.NONE), new BigDecimal("15000000"), null,
                new BigDecimal("15000000"), Currency.NGN, facts).getLast())
                .isEqualTo("That an instalment unpaid 7 days after its due date is in arrears; no late-payment penalty "
                        + "is declared for this estate.");
    }
}
