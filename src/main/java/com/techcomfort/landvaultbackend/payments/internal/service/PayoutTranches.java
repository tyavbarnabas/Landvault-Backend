package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.VerifiedSale;
import com.techcomfort.landvaultbackend.common.RefundAppliesTo;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentPurpose;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 3c: what a sale is owed, in tranches. An outright sale is one tranche, the
 * whole price. On an instalment plan HomeTrue holds each verified payment in
 * escrow except the developer's share — what they'd keep if the buyer withdrew
 * now, by the refund terms copied onto the purchase — and releases the rest
 * when the plan completes. So a buyer who withdraws on the declared terms is
 * refunded from money HomeTrue still holds. A late-payment penalty the
 * buyer wouldn't get back on withdrawal (phase 4) is paid out with its
 * payment's share in full. See AGENTS.md, "Instalment plans".
 */
final class PayoutTranches {

    static final String SALE = "SALE";
    static final String RELEASE = "RELEASE";
    static final String SHARE_PREFIX = "SHARE:";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PayoutTranches() {
    }

    /** One amount the seller is owed. {@code payment} is set for a share. */
    record Tranche(String key, BigDecimal amount, Payment payment) {
    }

    /** Every tranche owed on the sale, in the order they're paid, plus what escrow holds now. */
    record Owed(List<Tranche> tranches, BigDecimal heldInEscrow) {
    }

    static Owed of(VerifiedSale sale, List<Payment> payments) {
        if (sale.escrow() == null) {
            return new Owed(List.of(new Tranche(SALE, sale.totalPrice(), null)), null);
        }
        VerifiedSale.Escrow escrow = sale.escrow();
        List<Payment> verified = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED && p.getFinanceVerifiedAt() != null)
                .sorted(Comparator.comparing(Payment::getFinanceVerifiedAt).thenComparing(Payment::getCreatedAt,
                        Comparator.nullsLast(Comparator.<Instant>naturalOrder())))
                .toList();
        // TOTAL_PRICE: the deduction is a fixed sum on the land price, kept from
        // the first payments until it's reached; nothing more after that.
        BigDecimal cap = escrow.appliesTo() == RefundAppliesTo.TOTAL_PRICE
                ? pctOf(escrow.deductionPct(), sale.totalPrice()) : null;
        List<Tranche> tranches = new ArrayList<>();
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal shares = BigDecimal.ZERO;
        BigDecimal deducted = BigDecimal.ZERO; // towards the TOTAL_PRICE cap; penalties don't count
        for (Payment p : verified) {
            paid = paid.add(p.getAmount());
            // Phase 4: a penalty the buyer wouldn't get back on withdrawal isn't
            // held — it's the developer's in full; a refundable one is escrowed
            // like the rest of the payment.
            BigDecimal kept = escrow.penaltiesRefundable() ? BigDecimal.ZERO : p.getPenaltyAmount();
            BigDecimal escrowed = p.getAmount().subtract(kept);
            BigDecimal share;
            if (escrow.deductionPct() == null) {
                share = BigDecimal.ZERO;
            } else if (cap == null) {
                share = pctOf(escrow.deductionPct(), escrowed);
            } else {
                share = escrowed.min(cap.subtract(deducted).max(BigDecimal.ZERO));
            }
            deducted = deducted.add(share);
            share = share.add(kept);
            if (share.signum() > 0) {
                shares = shares.add(share);
                tranches.add(new Tranche(SHARE_PREFIX + p.getId(), share, p));
            }
        }
        BigDecimal held = paid.subtract(shares);
        if (escrow.planComplete()) {
            if (held.signum() > 0) {
                tranches.add(new Tranche(RELEASE, held, null));
            }
            held = BigDecimal.ZERO;
        }
        return new Owed(tranches, held);
    }

    /** Rounded down to the kobo: the developer is never paid a kobo more than they'd keep. */
    private static BigDecimal pctOf(BigDecimal pct, BigDecimal amount) {
        return amount.multiply(pct).divide(HUNDRED, 2, RoundingMode.DOWN);
    }

    /** The wire value: sale, share or release. */
    static String kind(String key) {
        return key.startsWith(SHARE_PREFIX) ? "share" : key.equals(RELEASE) ? "release" : "sale";
    }

    static UUID sharePaymentId(String key) {
        return key.startsWith(SHARE_PREFIX) ? UUID.fromString(key.substring(SHARE_PREFIX.length())) : null;
    }

    static String label(Tranche tranche) {
        return switch (kind(tranche.key())) {
            case "release" -> "Escrow release at plan completion";
            case "share" -> "Developer's share of the " + describe(tranche.payment());
            default -> "Full sale price";
        };
    }

    private static String describe(Payment p) {
        if (p.getPurpose() == PaymentPurpose.DEPOSIT) {
            return "deposit";
        }
        if (p.getPurpose() == PaymentPurpose.BALANCE) {
            return "balance payment";
        }
        return p.getPurpose() == PaymentPurpose.INSTALMENT ? "instalment " + p.getInstalmentSequence() : "payment";
    }
}
