package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.Refund;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.enums.RefundStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackAmounts;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackClient;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    /**
     * {@code resend} = an earlier request's reply was lost; Paystack is asked
     * whether it has that refund (by its note) before it is sent again.
     */
    public record Prepared(UUID refundId, String paymentReference, long amountKobo, boolean resend, Instant createdAt) {

        /** Our own note on the refund at Paystack — what finds it again after a lost reply. */
        public String merchantNote() {
            return "HomeTrue refund " + refundId;
        }
    }

    @Transactional
    public Prepared prepare(String paymentReference, UUID actor) {
        Payment payment = payments.findByReferenceForUpdate(paymentReference)
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED && p.getRefundRequestedAt() != null)
                .orElseThrow(PaymentException.RefundNotDue::new);
        Optional<Refund> inFlight = refunds.findInFlightForUpdate(payment.getId());
        if (inFlight.isPresent()) {
            if (inFlight.get().getStatus() == RefundStatus.SENDING) {
                Refund sending = inFlight.get();
                return new Prepared(sending.getId(), paymentReference, sending.getAmountKobo(), true,
                        sending.getCreatedAt());
            }
            throw new PaymentException.RefundInProgress(inFlight.get().getStatus().wire());
        }
        // What is owed back (all of it, unless a withdrawal left the developer a
        // share), less what earlier refunds already returned. Under the payment's
        // row lock, so two Super Admins can't both send the same part.
        BigDecimal amount = remaining(payment);
        if (amount.signum() <= 0) {
            throw new PaymentException.RefundNotDue();
        }
        String reason = payment.getRefundReason() != null ? payment.getRefundReason()
                : payment.getReviewReason() != null ? payment.getReviewReason() : "Refund owed to the buyer";
        Refund refund = Refund.builder()
                .paymentId(payment.getId())
                .transactionId(payment.getTransactionId())
                .buyerUserId(payment.getBuyerUserId())
                .sellerTenantId(payment.getSellerTenantId())
                .amount(amount)
                .currency(Currency.NGN)
                .amountKobo(PaystackAmounts.toKobo(amount, Currency.NGN))
                .status(RefundStatus.SENDING)
                .reason(RefundRecorder.truncate(reason))
                .initiatedBy(actor)
                .build();
        try {
            refund = refunds.saveAndFlush(refund);
        } catch (DataIntegrityViolationException e) {
            throw new PaymentException.RefundInProgress("being sent");
        }
        auditApi.record(AuditEntryRequest.of(actor, "refund.started", "refund", refund.getId(),
                payment.getSellerTenantId(), "Refund of NGN " + amount.toPlainString() + " for payment "
                        + paymentReference + " started."));
        return new Prepared(refund.getId(), paymentReference, refund.getAmountKobo(), false, refund.getCreatedAt());
    }

    /** Owed back on this payment and not yet returned or on its way (failed refunds don't count). */
    BigDecimal remaining(Payment payment) {
        BigDecimal returned = refunds.findAllByPaymentIdInOrderByCreatedAtDesc(List.of(payment.getId())).stream()
                .filter(r -> r.getStatus() != RefundStatus.FAILED)
                .map(Refund::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return payment.owedBack().subtract(returned);
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
