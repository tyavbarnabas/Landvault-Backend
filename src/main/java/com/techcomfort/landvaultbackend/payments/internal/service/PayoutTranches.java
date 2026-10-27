package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.VerifiedSale;
import com.techcomfort.landvaultbackend.common.RefundAppliesTo;
import com.techcomfort.landvaultbackend.payments.internal.domain.GainShareClaim;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentPurpose;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 3c: what a sale is owed, in tranches. An outright sale is one tranche, the
 * whole price. On an instalment plan HomeTrue holds each verified payment in
 * escrow except the developer's share — what they'd keep if the buyer withdrew
 * now, by the refund terms copied onto the purchase — and releases the rest
 * when the plan completes. So a buyer who withdraws on the declared terms is
 * refunded from money HomeTrue still holds. A late-payment penalty the
 * buyer wouldn't get back on withdrawal (phase 4) is paid out with its
 * payment's share in full. On a withdrawal (5a) the same shares say what the
 * buyer gets back. See AGENTS.md, "Instalment plans".
 */
final class PayoutTranches {

    static final String SALE = "SALE";
    static final String RELEASE = "RELEASE";
    static final String SHARE_PREFIX = "SHARE:";
    static final String GAIN_PREFIX = "GAIN:";

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
        return of(sale, payments, List.of());
    }

    /**
     * Flexible contribution, phase 5: with {@code gains} — earlier buyers'
     * shares of the gain owed out of this sale — taken off the developer's
     * whole-price tranche (outright) or release (a plan, at completion) and
     * paid as their own tranches after the developer's. Settlement capped
     * them at what that tranche was, so it never goes below zero.
     */
    static Owed of(VerifiedSale sale, List<Payment> payments, List<GainShareClaim> gains) {
        Owed owed = withoutGains(sale, payments);
        if (gains.isEmpty()) {
            return owed;
        }
        BigDecimal deducted = sum(gains.stream().map(GainShareClaim::getBuyerAmount).toList());
        List<Tranche> tranches = new ArrayList<>();
        for (Tranche t : owed.tranches()) {
            if (t.key().equals(SALE) || t.key().equals(RELEASE)) {
                BigDecimal left = t.amount().subtract(deducted);
                if (left.signum() > 0) {
                    tranches.add(new Tranche(t.key(), left, null));
                }
            } else {
                tranches.add(t);
            }
        }
        gains.forEach(g -> tranches.add(new Tranche(g.trancheKey(), g.getBuyerAmount(), null)));
        return new Owed(tranches, owed.heldInEscrow());
    }

    /** What the developer would be owed from this sale's whole-price tranche or release; zero when it has none. */
    static BigDecimal developerRemainder(VerifiedSale sale, List<Payment> payments) {
        return withoutGains(sale, payments).tranches().stream()
                .filter(t -> t.key().equals(SALE) || t.key().equals(RELEASE))
                .map(Tranche::amount).findFirst().orElse(BigDecimal.ZERO);
    }

    private static Owed withoutGains(VerifiedSale sale, List<Payment> payments) {
        if (sale.escrow() == null) {
            return new Owed(List.of(new Tranche(SALE, sale.totalPrice(), null)), null);
        }
        VerifiedSale.Escrow escrow = sale.escrow();
        List<Payment> verified = verified(payments);
        Map<Payment, BigDecimal> shares = shares(escrow, sale.totalPrice(), verified);
        List<Tranche> tranches = new ArrayList<>();
        shares.forEach((p, share) -> {
            if (share.signum() > 0) {
                tranches.add(new Tranche(SHARE_PREFIX + p.getId(), share, p));
            }
        });
        BigDecimal paid = sum(verified.stream().map(Payment::getAmount).toList());
        BigDecimal held = paid.subtract(sum(shares.values()));
        if (escrow.ended()) {
            // 5a/5b: withdrawn or revoked — what was held goes back to the buyer, never to the developer.
            // Still held for them: their share of a payment finance hasn't
            // verified yet — paid out once it is (decided with the user).
            Map<Payment, BigDecimal> withAwaiting = shares(escrow, sale.totalPrice(), countedOnWithdrawal(payments));
            return new Owed(tranches, sum(withAwaiting.values()).subtract(sum(shares.values())));
        }
        if (escrow.planComplete()) {
            if (held.signum() > 0) {
                tranches.add(new Tranche(RELEASE, held, null));
            }
            held = BigDecimal.ZERO;
        }
        return new Owed(tranches, held);
    }

    /** Finance-verified succeeded payments, in the order they were verified. */
    static List<Payment> verified(List<Payment> payments) {
        return payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED && p.getFinanceVerifiedAt() != null)
                .sorted(Comparator.comparing(Payment::getFinanceVerifiedAt).thenComparing(Payment::getCreatedAt,
                        Comparator.nullsLast(Comparator.<Instant>naturalOrder())))
                .toList();
    }

    /**
     * 5a: the payments a withdrawal settles — every verified one, then any
     * Paystack confirmed that finance hasn't verified yet (and hasn't
     * rejected), counted as if verified next (decided with the user: the buyer
     * gets their part at once; the developer's share waits for finance). That
     * is also the order finance verifying it later produces, so the figures
     * never move.
     */
    static List<Payment> countedOnWithdrawal(List<Payment> payments) {
        List<Payment> counted = new ArrayList<>(verified(payments));
        payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED && p.getFinanceVerifiedAt() == null
                        && p.getReviewReason() == null && p.getPurpose() != PaymentPurpose.OUTRIGHT)
                .sorted(Comparator.comparing(Payment::getPaidAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
                        .thenComparing(Payment::getCreatedAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder())))
                .forEach(counted::add);
        return counted;
    }

    /**
     * The developer's share of each payment, in the order given: what they
     * keep if the buyer withdraws. {@code landPrice} is the TOTAL_PRICE basis.
     */
    static Map<Payment, BigDecimal> shares(VerifiedSale.Escrow escrow, BigDecimal landPrice, List<Payment> ordered) {
        // TOTAL_PRICE: the deduction is a fixed sum on the land price, kept from
        // the first payments until it's reached; nothing more after that.
        BigDecimal cap = escrow.appliesTo() == RefundAppliesTo.TOTAL_PRICE && escrow.deductionPct() != null
                ? pctOf(escrow.deductionPct(), landPrice) : null;
        Map<Payment, BigDecimal> shares = new LinkedHashMap<>();
        BigDecimal deducted = BigDecimal.ZERO; // towards the TOTAL_PRICE cap; penalties don't count
        for (Payment p : ordered) {
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
            shares.put(p, share.add(kept));
        }
        return shares;
    }

    private static BigDecimal sum(Collection<BigDecimal> amounts) {
        return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Rounded down to the kobo: the developer is never paid a kobo more than they'd keep. */
    private static BigDecimal pctOf(BigDecimal pct, BigDecimal amount) {
        return amount.multiply(pct).divide(HUNDRED, 2, RoundingMode.DOWN);
    }

    /** The wire value: sale, share, release or (phase 5) gain. */
    static String kind(String key) {
        return key.startsWith(SHARE_PREFIX) ? "share" : key.equals(RELEASE) ? "release"
                : key.startsWith(GAIN_PREFIX) ? "gain" : "sale";
    }

    static UUID gainClaimId(String key) {
        return key.startsWith(GAIN_PREFIX) ? UUID.fromString(key.substring(GAIN_PREFIX.length())) : null;
    }

    static UUID sharePaymentId(String key) {
        return key.startsWith(SHARE_PREFIX) ? UUID.fromString(key.substring(SHARE_PREFIX.length())) : null;
    }

    static String label(Tranche tranche) {
        return switch (kind(tranche.key())) {
            case "release" -> "Escrow release at plan completion";
            case "gain" -> "Gain share for an earlier buyer of this plot";
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
        if (p.getPurpose() == PaymentPurpose.CONTRIBUTION) {
            return "contribution";
        }
        return p.getPurpose() == PaymentPurpose.INSTALMENT ? "instalment " + p.getInstalmentSequence() : "payment";
    }
}
