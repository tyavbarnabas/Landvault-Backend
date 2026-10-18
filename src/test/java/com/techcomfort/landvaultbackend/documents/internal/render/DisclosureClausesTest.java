package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.DueTrigger;
import com.techcomfort.landvaultbackend.common.FeeType;
import com.techcomfort.landvaultbackend.common.RefundAppliesTo;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentFacts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Modelled on the Double King letter the stories came from. */
class DisclosureClausesTest {

    private static EstateDocumentFacts.Fee fixed(FeeType type, String amount, DueTrigger due, boolean refundable) {
        return new EstateDocumentFacts.Fee(type, null, new BigDecimal(amount), null, null, Currency.NGN, true, null,
                due, refundable, true);
    }

    private static EstateDocumentFacts facts(EstateDocumentFacts.Fees fees, EstateDocumentFacts.RefundTerms refund,
                                             EstateDocumentFacts.DefaultTerms terms) {
        return new EstateDocumentFacts(UUID.randomUUID(), UUID.randomUUID(), null, "Double King Estate", "Lugbe",
                "Abuja", "Federal Capital Territory (Abuja)", fees, refund, terms);
    }

    @Test
    void theDisclosureBecomesTheLettersClausesWithPayingThroughHomeTrueLast() {
        var fees = new EstateDocumentFacts.Fees(2, List.of(
                fixed(FeeType.APPLICATION, "10000", DueTrigger.AT_APPLICATION, false),
                fixed(FeeType.INFRASTRUCTURE, "3500000", DueTrigger.AT_ALLOCATION, true),
                fixed(FeeType.FACILITY_MANAGEMENT, "150000", DueTrigger.ANNUAL, false)));
        var refund = new EstateDocumentFacts.RefundTerms(1, new BigDecimal("20.00"), 90, RefundAppliesTo.TOTAL_PRICE,
                List.of(FeeType.APPLICATION));
        var terms = new EstateDocumentFacts.DefaultTerms(1, "Failure to begin development within 24 months.", 30,
                "Money paid is refunded less the 20% deduction.", 24, true);

        assertThat(DisclosureClauses.of(facts(fees, refund, terms))).containsExactly(
                "That an application fee of ₦10,000.00 is payable on application, and is not refundable.",
                "That an infrastructure fee of ₦3,500,000.00 is payable on allocation.",
                "That a facility management fee of ₦150,000.00 is payable every year for as long as the allottee holds the plot.",
                "That if the allottee withdraws, 20% of the total price is deducted and the balance refunded within 90 days; the application fee is not refunded.",
                "That the allottee shall begin development within 24 months of allocation.",
                "That the allocation may be revoked for failure to begin development within 24 months, after 30 days' written notice. If it is, money paid is refunded less the 20% deduction.",
                "That the allottee may not sell, transfer or assign the plot without the developer's written consent.",
                DisclosureClauses.PAY_ONLY_THROUGH_HOMETRUE);
    }

    @Test
    void aVariableOrOptionalFeeSaysSo() {
        var fee = new EstateDocumentFacts.Fee(FeeType.SURVEY, null, null, new BigDecimal("100000"),
                new BigDecimal("250000"), Currency.NGN, false, "plot size.", DueTrigger.ON_CONSTRUCTION_START, true, false);
        assertThat(DisclosureClauses.fee(fee)).isEqualTo(
                "That an optional survey fee of between ₦100,000.00 and ₦250,000.00, depending on plot size is payable when construction starts.");
    }

    @Test
    void anEmptyScheduleIsADeclarationAndSilenceIsMissing() {
        assertThat(DisclosureClauses.of(facts(new EstateDocumentFacts.Fees(1, List.of()), null, null)))
                .containsExactly("That no charges apply beyond the price of the land.",
                        DisclosureClauses.PAY_ONLY_THROUGH_HOMETRUE);
        assertThat(DisclosureClauses.missing(facts(null, null, null)))
                .containsExactly("fees", "refund_terms", "default_terms");
    }
}
