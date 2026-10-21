package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.DueTrigger;
import com.techcomfort.landvaultbackend.common.FeeType;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentFacts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

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
}
