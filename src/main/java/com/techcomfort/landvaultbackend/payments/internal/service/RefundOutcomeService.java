package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.Refund;
import com.techcomfort.landvaultbackend.payments.internal.enums.RefundStatus;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackClient;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * How a refund actually ended. A {@code refund.*} webhook or the sweep only
 * PROMPTS a check: Paystack's refund is fetched and its answer recorded, as
 * for payments and payouts. Processed clears the payment's "refund due"
 * flag; needs-attention asks the buyer for an account; failed alerts the
 * Super Admins (and the payment is owed again).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundOutcomeService {

    public enum Outcome { UPDATED, UNCHANGED, NOT_OURS }

    private static final Set<RefundStatus> OPEN = Set.of(RefundStatus.PENDING, RefundStatus.PROCESSING);

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaystackClient paystack;
    private final IdentityApi identityApi;
    private final SettlementAlertSender alerts;
    private final AuditApi auditApi;

    @Value("${landvault.refunds.stuck-after}")
    private Duration stuckAfter;

    /** For the webhook: Paystack names the PAYMENT (transaction_reference), not our refund. */
    @Transactional
    public Outcome refreshForPayment(String paymentReference) {
        Optional<Payment> payment = payments.findByReference(paymentReference);
        if (payment.isEmpty()) {
            return Outcome.NOT_OURS;
        }
        return refunds.findLiveForUpdate(payment.get().getId(), RefundStatus.FAILED)
                .map(this::refresh).orElse(Outcome.NOT_OURS);
    }

    @Transactional
    public Outcome refresh(UUID refundId) {
        return refunds.findByIdForUpdate(refundId).map(this::refresh).orElse(Outcome.NOT_OURS);
    }

    /** Refunds accepted by Paystack but not finished for a while. In a transaction, like every read. */
    @Transactional(readOnly = true)
    public List<UUID> stuck() {
        Instant cutoff = Instant.now().minus(stuckAfter);
        // SENDING has no Paystack id to ask about yet (a person resends it), so only accepted refunds are swept.
        return refunds.findStuck(RefundStatus.SENDING, Instant.EPOCH, OPEN, cutoff);
    }

    private Outcome refresh(Refund refund) {
        if (refund.getPaystackRefundId() == null || refund.getStatus() == RefundStatus.PROCESSED) {
            return Outcome.UNCHANGED;
        }
        PaystackClient.Refund answer = paystack.fetchRefund(refund.getPaystackRefundId());
        RefundStatus paystackSays = RefundStatus.fromPaystack(answer.status());
        RefundStatus current = refund.getStatus();
        if (paystackSays == current) {
            return Outcome.UNCHANGED;
        }
        if (current == RefundStatus.FAILED) {
            // Closed on our side; Paystack moving it on means a person should look.
            alertAdmins(refund, "changed after failing", "Paystack now reports this refund as "
                    + paystackSays.wire() + " after it had failed. Check it in the Paystack dashboard.");
            return Outcome.UNCHANGED;
        }
        refund.setStatus(paystackSays);
        if (answer.refundedAt() != null) {
            refund.setRefundedAt(answer.refundedAt());
        }
        if (answer.expectedAt() != null) {
            refund.setExpectedAt(answer.expectedAt());
        }
        refunds.save(refund);
        auditApi.record(AuditEntryRequest.of(null, "refund." + paystackSays.wire(), "refund", refund.getId(),
                refund.getSellerTenantId(), "Paystack reports the refund is now " + paystackSays.wire() + "."));
        switch (paystackSays) {
            case PROCESSED -> payments.findById(refund.getPaymentId()).ifPresent(payment -> {
                // Refunded: nothing is owed any more. refund_requested_at stays as history.
                payment.setReviewReason(null);
                payments.save(payment);
            });
            case NEEDS_ATTENTION -> identityApi.emailOf(refund.getBuyerUserId()).ifPresent(to -> payments
                    .findById(refund.getPaymentId()).ifPresent(payment -> alerts.refundNeedsAccount(
                            new SettlementAlertSender.RefundNeedsAccountAlert(to, refund.getAmount().toPlainString(),
                                    payment.getReference()))));
            case FAILED -> alertAdmins(refund, "failed", "The refund failed; the payment is owed again and "
                    + "can be refunded once more.");
            default -> { }
        }
        log.info("Refund {} moved {} -> {}", refund.getId(), current.wire(), paystackSays.wire());
        return Outcome.UPDATED;
    }

    private void alertAdmins(Refund refund, String status, String detail) {
        String reference = payments.findById(refund.getPaymentId()).map(Payment::getReference).orElse("?");
        for (String to : identityApi.superAdminEmails()) {
            alerts.refundProblem(new SettlementAlertSender.RefundProblemAlert(to, reference,
                    refund.getAmount().toPlainString(), status, detail));
        }
    }
}
