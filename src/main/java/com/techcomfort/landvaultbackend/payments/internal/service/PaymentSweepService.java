package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PY-5: purchases nobody paid for. Paystack sends no webhook for an abandoned
 * payment, and a hold with a transaction behind it is never released by the
 * reservation sweep — so without this, every abandoned checkout keeps its plot
 * off the market forever. After the hold plus a grace period (decided with the
 * user: a transfer account lasts 30 minutes), every open payment is checked
 * with Paystack first — money may have arrived with its webhook lost — and only
 * if nothing was paid is the purchase abandoned and the plot released.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSweepService {

    private final CheckoutApi checkoutApi;
    private final PaymentRepository payments;
    private final PaymentConfirmationService confirmation;
    private final AuditApi auditApi;

    @Value("${landvault.payments.abandon-grace:15m}")
    private Duration abandonGrace;

    public enum Outcome { PAID, ABANDONED, LEFT_FOR_REVIEW, UNCHANGED }

    /** Which purchases are past their hold plus the grace period. */
    public List<UUID> candidates() {
        return checkoutApi.pendingTransactionsWithHoldExpiredBefore(Instant.now().minus(abandonGrace));
    }

    /**
     * One purchase, in its own transaction: a failure (Paystack down) rolls
     * back this one only, and it is tried again next sweep.
     */
    @Transactional
    public Outcome settle(UUID transactionId) {
        List<Payment> open = payments.findLockedByTransactionIdAndStatus(transactionId, PaymentStatus.INITIALIZED);
        for (Payment payment : open) {
            confirmation.confirm(payment);
        }
        if (open.stream().anyMatch(p -> p.getStatus() == PaymentStatus.SUCCEEDED)
                || payments.existsByTransactionIdAndStatus(transactionId, PaymentStatus.SUCCEEDED)) {
            return Outcome.PAID;
        }
        // Money arrived but not what was agreed: a person is already looking. Don't release the plot under them.
        if (payments.existsByTransactionIdAndStatus(transactionId, PaymentStatus.MISMATCHED)) {
            return Outcome.LEFT_FOR_REVIEW;
        }
        for (Payment payment : open) {
            if (payment.getStatus() == PaymentStatus.INITIALIZED) {
                payment.setStatus(PaymentStatus.ABANDONED);
                payment.setVerifiedAt(Instant.now());
                payments.save(payment);
                auditApi.record(AuditEntryRequest.of(null, "payment.abandoned", "payment", payment.getId(),
                        payment.getSellerTenantId(), "Payment " + payment.getReference()
                                + " never completed; Paystack confirmed no money arrived."));
            }
        }
        payments.flush();
        if (!checkoutApi.abandonTransaction(transactionId)) {
            return Outcome.UNCHANGED;
        }
        log.info("Purchase {} abandoned; its plot is back on sale", transactionId);
        return Outcome.ABANDONED;
    }
}
