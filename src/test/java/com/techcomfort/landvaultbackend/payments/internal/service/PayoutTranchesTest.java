package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.VerifiedSale;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.RefundAppliesTo;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentPurpose;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutTranchesTest {

    private static final Instant T0 = Instant.parse("2026-10-01T09:00:00Z");

    @Test
    void anOutrightSaleIsOneTrancheOfTheWholePrice() {
        PayoutTranches.Owed owed = PayoutTranches.of(sale(null), List.of());

        assertThat(owed.tranches()).singleElement().satisfies(t -> {
            assertThat(t.key()).isEqualTo("SALE");
            assertThat(t.amount()).isEqualByComparingTo("20000000");
        });
        assertThat(owed.heldInEscrow()).isNull();
    }

    /** 20% of what is paid: each payment's share, the rest held. */
    @Test
    void amountPaidTakesItsShareOfEveryPayment() {
        Payment deposit = paid(PaymentPurpose.DEPOSIT, null, "6600000.00", 1);
        Payment first = paid(PaymentPurpose.INSTALMENT, 1, "7700000.00", 2);

        PayoutTranches.Owed owed = PayoutTranches.of(sale(escrow(false, "20.00", RefundAppliesTo.AMOUNT_PAID)),
                List.of(first, deposit));

        assertThat(owed.tranches()).extracting(PayoutTranches.Tranche::key)
                .as("in the order finance verified them")
                .containsExactly("SHARE:" + deposit.getId(), "SHARE:" + first.getId());
        assertThat(owed.tranches()).extracting(PayoutTranches.Tranche::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1320000"), new BigDecimal("1540000"));
        assertThat(owed.heldInEscrow()).isEqualByComparingTo("11440000");
        assertThat(PayoutTranches.label(owed.tranches().get(1))).isEqualTo("Developer's share of the instalment 1");
    }

    /** 20% of the ₦20M land price is ₦4M, kept from the first payments; nothing more after that. */
    @Test
    void totalPriceKeepsAFixedSumFromTheFirstPayments() {
        Payment deposit = paid(PaymentPurpose.DEPOSIT, null, "3000000.00", 1);
        Payment first = paid(PaymentPurpose.INSTALMENT, 1, "1500000.00", 2);
        Payment second = paid(PaymentPurpose.INSTALMENT, 2, "1500000.00", 3);

        PayoutTranches.Owed owed = PayoutTranches.of(sale(escrow(false, "20.00", RefundAppliesTo.TOTAL_PRICE)),
                List.of(deposit, first, second));

        assertThat(owed.tranches()).extracting(PayoutTranches.Tranche::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("3000000"), new BigDecimal("1000000"));
        assertThat(owed.heldInEscrow()).isEqualByComparingTo("2000000");
    }

    /** At completion, everything held is released; nothing is held after. */
    @Test
    void completionReleasesWhatWasHeld() {
        Payment deposit = paid(PaymentPurpose.DEPOSIT, null, "6600000.00", 1);
        Payment balance = paid(PaymentPurpose.BALANCE, null, "15400000.00", 2);

        PayoutTranches.Owed owed = PayoutTranches.of(sale(escrow(true, "20.00", RefundAppliesTo.TOTAL_PRICE)),
                List.of(deposit, balance));

        assertThat(owed.tranches()).extracting(PayoutTranches.Tranche::key)
                .containsExactly("SHARE:" + deposit.getId(), "RELEASE");
        assertThat(owed.tranches().getLast().amount()).isEqualByComparingTo("18000000");
        assertThat(owed.heldInEscrow()).isZero();
    }

    /** A purchase opened before escrow terms: nothing is paid out until the plan completes. */
    @Test
    void withoutTermsEverythingIsHeldUntilCompletion() {
        Payment deposit = paid(PaymentPurpose.DEPOSIT, null, "6600000.00", 1);

        assertThat(PayoutTranches.of(sale(escrow(false, null, null)), List.of(deposit)).tranches()).isEmpty();
        assertThat(PayoutTranches.of(sale(escrow(true, null, null)), List.of(deposit)).tranches())
                .singleElement().satisfies(t -> assertThat(t.amount()).isEqualByComparingTo("6600000"));
    }

    /** Unverified, failed and finance-rejected payments owe nothing yet. */
    @Test
    void onlyFinanceVerifiedPaymentsCount() {
        Payment waiting = paid(PaymentPurpose.INSTALMENT, 1, "7700000.00", 2);
        waiting.setFinanceVerifiedAt(null);
        Payment failed = paid(PaymentPurpose.INSTALMENT, 1, "7700000.00", 3);
        failed.setStatus(PaymentStatus.FAILED);

        assertThat(PayoutTranches.of(sale(escrow(false, "20.00", RefundAppliesTo.AMOUNT_PAID)),
                List.of(waiting, failed)).tranches()).isEmpty();
    }

    /** Rounded down: never a kobo more than the developer would keep. */
    @Test
    void aShareIsRoundedDownToTheKobo() {
        Payment odd = paid(PaymentPurpose.INSTALMENT, 1, "1000000.07", 1);

        assertThat(PayoutTranches.of(sale(escrow(false, "12.50", RefundAppliesTo.AMOUNT_PAID)), List.of(odd))
                .tranches().getFirst().amount()).isEqualByComparingTo("125000.00");
    }

    /**
     * Phase 4: a ₦100,000 penalty the buyer wouldn't get back goes to the
     * developer in full; only the instalment itself is shared and held.
     */
    @Test
    void aNonRefundablePenaltyIsPaidOutInFull() {
        Payment late = paid(PaymentPurpose.INSTALMENT, 1, "1100000.00", 1);
        late.setPenaltyAmount(new BigDecimal("100000.00"));

        PayoutTranches.Owed owed = PayoutTranches.of(sale(new VerifiedSale.Escrow(false, new BigDecimal("20.00"),
                RefundAppliesTo.AMOUNT_PAID, false)), List.of(late));

        assertThat(owed.tranches()).singleElement()
                .satisfies(t -> assertThat(t.amount()).isEqualByComparingTo("300000"));
        assertThat(owed.heldInEscrow()).isEqualByComparingTo("800000");
    }

    /** A refundable penalty is part of what the buyer would get back, so it's shared and held like the rest. */
    @Test
    void aRefundablePenaltyIsHeldLikeTheInstalment() {
        Payment late = paid(PaymentPurpose.INSTALMENT, 1, "1100000.00", 1);
        late.setPenaltyAmount(new BigDecimal("100000.00"));

        PayoutTranches.Owed owed = PayoutTranches.of(sale(escrow(false, "20.00", RefundAppliesTo.AMOUNT_PAID)),
                List.of(late));

        assertThat(owed.tranches().getFirst().amount()).isEqualByComparingTo("220000");
        assertThat(owed.heldInEscrow()).isEqualByComparingTo("880000");
    }

    /** TOTAL_PRICE: a non-refundable penalty is paid out on top and never counts towards the ₦4M deduction. */
    @Test
    void aNonRefundablePenaltyDoesNotUseUpTheFixedDeduction() {
        Payment deposit = paid(PaymentPurpose.DEPOSIT, null, "3000000.00", 1);
        Payment late = paid(PaymentPurpose.INSTALMENT, 1, "1600000.00", 2);
        late.setPenaltyAmount(new BigDecimal("100000.00"));
        Payment next = paid(PaymentPurpose.INSTALMENT, 2, "1500000.00", 3);

        PayoutTranches.Owed owed = PayoutTranches.of(sale(new VerifiedSale.Escrow(false, new BigDecimal("20.00"),
                RefundAppliesTo.TOTAL_PRICE, false)), List.of(deposit, late, next));

        assertThat(owed.tranches()).extracting(PayoutTranches.Tranche::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("3000000"), new BigDecimal("1100000"));
        assertThat(owed.heldInEscrow()).isEqualByComparingTo("2000000");
    }

    private static VerifiedSale sale(VerifiedSale.Escrow escrow) {
        return new VerifiedSale(UUID.randomUUID(), "LV-TX", UUID.randomUUID(), "Estate", "Plot 1",
                new BigDecimal("20000000.0000"), Currency.NGN, T0, escrow);
    }

    private static VerifiedSale.Escrow escrow(boolean complete, String pct, RefundAppliesTo appliesTo) {
        return new VerifiedSale.Escrow(complete, pct == null ? null : new BigDecimal(pct), appliesTo, true);
    }

    private static Payment paid(PaymentPurpose purpose, Integer sequence, String amount, int verifiedDay) {
        Payment p = Payment.builder()
                .id(UUID.randomUUID())
                .purpose(purpose)
                .instalmentSequence(sequence)
                .amount(new BigDecimal(amount))
                .status(PaymentStatus.SUCCEEDED)
                .build();
        p.setCreatedAt(T0);
        p.setFinanceVerifiedAt(T0.plusSeconds(86_400L * verifiedDay));
        return p;
    }
}
