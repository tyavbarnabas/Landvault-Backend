package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.Refund;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.enums.RefundStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackClient;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The database half of starting a refund, each step its own short
 * transaction (called from {@link RefundService}, never from inside one) — the
 * same shape as {@link PayoutRecorder}: the row is committed as
 * {@code sending} before Paystack is asked.
 */
@Service
@RequiredArgsConstructor
public class RefundRecorder {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final AuditApi auditApi;

    /** {@code resend} = an earlier request's reply was lost; Paystack is asked again for the same payment. */
    public record Prepared(UUID refundId, String paymentReference, long amountKobo, boolean resend) {
    }

    @Transactional
    public Prepared prepare(String paymentReference, UUID actor) {
        Payment payment = payments.findByReferenceForUpdate(paymentReference)
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED && p.getRefundRequestedAt() != null)
                .orElseThrow(PaymentException.RefundNotDue::new);
        Optional<Refund> live = refunds.findLiveForUpdate(payment.getId(), RefundStatus.FAILED);
        if (live.isPresent()) {
            if (live.get().getStatus() == RefundStatus.SENDING) {
                return new Prepared(live.get().getId(), paymentReference, live.get().getAmountKobo(), true);
            }
            throw new PaymentException.RefundInProgress(live.get().getStatus().wire());
        }
        // The full amount paid (decided with the user): these buyers never got the plot.
        Refund refund = Refund.builder()
                .paymentId(payment.getId())
                .transactionId(payment.getTransactionId())
                .buyerUserId(payment.getBuyerUserId())
                .sellerTenantId(payment.getSellerTenantId())
                .amount(payment.getAmount())
                .currency(Currency.NGN)
                .amountKobo(payment.getAmountKobo())
                .status(RefundStatus.SENDING)
                .reason(payment.getReviewReason() == null ? "Refund owed to the buyer" : payment.getReviewReason())
                .initiatedBy(actor)
                .build();
        try {
            refund = refunds.saveAndFlush(refund);
        } catch (DataIntegrityViolationException e) {
            throw new PaymentException.RefundInProgress("being sent");
        }
        auditApi.record(AuditEntryRequest.of(actor, "refund.started", "refund", refund.getId(),
                payment.getSellerTenantId(), "Refund of NGN " + payment.getAmount().toPlainString() + " for payment "
                        + paymentReference + " started."));
        return new Prepared(refund.getId(), paymentReference, refund.getAmountKobo(), false);
    }

    /** Records Paystack's answer to the request: its refund id and first status. */
    @Transactional
    public Refund record(UUID refundId, PaystackClient.Refund answer, UUID actor) {
        Refund refund = refunds.findByIdForUpdate(refundId).orElseThrow(PaymentException.RefundNotFound::new);
        RefundStatus status = RefundStatus.fromPaystack(answer.status());
        refund.setStatus(status);
        if (answer.id() != null) {
            refund.setPaystackRefundId(answer.id());
        }
        refund.setGatewayMessage(truncate(answer.message()));
        refund.setExpectedAt(answer.expectedAt());
        refund.setRefundedAt(answer.refundedAt());
        refunds.save(refund);
        auditApi.record(AuditEntryRequest.of(actor, "refund." + status.wire(), "refund", refund.getId(),
                refund.getSellerTenantId(), "Paystack accepted the refund request; it is " + status.wire() + "."));
        return refund;
    }

    /** Paystack refused the first request, so no money moved: the refund fails and may be tried again. */
    @Transactional
    public Refund refused(UUID refundId, String paystackMessage, UUID actor) {
        Refund refund = refunds.findByIdForUpdate(refundId).orElseThrow(PaymentException.RefundNotFound::new);
        refund.setStatus(RefundStatus.FAILED);
        refund.setGatewayMessage(truncate(paystackMessage));
        refunds.save(refund);
        auditApi.record(AuditEntryRequest.of(actor, "refund.failed", "refund", refund.getId(),
                refund.getSellerTenantId(), "Paystack refused the refund: " + paystackMessage));
        return refund;
    }

    /** A resend Paystack refused: it may already have this refund, so nothing is closed — kept as sending. */
    @Transactional
    public void resendRefused(UUID refundId, String paystackMessage) {
        refunds.findByIdForUpdate(refundId).ifPresent(refund -> {
            refund.setGatewayMessage(truncate(paystackMessage));
            refunds.save(refund);
        });
    }

    static String truncate(String message) {
        return message == null || message.length() <= 500 ? message : message.substring(0, 500);
    }
}
