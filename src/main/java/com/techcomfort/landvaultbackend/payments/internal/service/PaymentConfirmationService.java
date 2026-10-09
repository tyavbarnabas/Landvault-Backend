package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.payments.dto.PaymentDto;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackClient;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * PY-3, PY-4, PY-6, PY-8: decide what happened to a payment — only ever by
 * asking Paystack, never because a browser came back. Safe to call any number
 * of times: the row is locked and a finished payment is never re-decided.
 * The webhook (next step) calls {@link #confirm} too. See AGENTS.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentConfirmationService {

    /** Paystack's final "no" states. Anything else (pending, abandoned, ongoing...) may still complete. */
    private static final Set<String> FAILED_STATES = Set.of("failed", "reversed");

    private final PaymentRepository payments;
    private final PaystackClient paystack;
    private final CheckoutApi checkoutApi;
    private final AuditApi auditApi;

    /** The return page asking "did it go through?" — only for the buyer's own payment. */
    @Transactional
    public PaymentDto confirmForBuyer(String reference) {
        UUID buyerId = currentScope().userId();
        Payment payment = payments.findByReferenceForUpdate(reference)
                .filter(p -> p.getBuyerUserId().equals(buyerId))
                .orElseThrow(PaymentException.PaymentNotFound::new);
        return PaymentService.toDto(confirm(payment));
    }

    /**
     * The webhook's route in: no buyer is calling, so no ownership check — the
     * caller has already proven the message came from Paystack. An unknown
     * reference changes nothing. Same lock, same {@link #confirm}.
     */
    @Transactional
    public void confirmFromGateway(String reference) {
        payments.findByReferenceForUpdate(reference).ifPresent(this::confirm);
    }

    /**
     * Asks Paystack and applies the answer. The payment must already be locked
     * by the caller. A finished payment is returned untouched — a second
     * confirmation is a no-op, not an error.
     */
    Payment confirm(Payment payment) {
        // Final verdicts are never re-decided. ABANDONED is not final: it means we
        // stopped waiting, not that Paystack said no — late money must still land.
        if (payment.getStatus() != PaymentStatus.INITIALIZED && payment.getStatus() != PaymentStatus.ABANDONED) {
            return payment;
        }
        PaystackClient.Verification verification = paystack.verify(payment.getReference());
        if (!verification.found()) {
            return payment;
        }

        if (verification.succeeded()) {
            payment.setChannel(verification.channel());
            payment.setGatewayResponse(verification.gatewayResponse());
            payment.setLast4(verification.last4());
            payment.setCardBin(verification.cardBin());
            payment.setPaidAt(verification.paidAt());
            payment.setVerifiedAt(Instant.now());
            if (matchesWhatWeAskedFor(payment, verification)) {
                payment.setStatus(PaymentStatus.SUCCEEDED);
                payments.saveAndFlush(payment);
                audit(payment, "payment.succeeded", "Paystack confirmed ₦" + payment.getAmount().toPlainString()
                        + " (" + verification.channel() + ").");
                if (!checkoutApi.recordPaymentReceived(payment.getTransactionId(), payment.getReference())) {
                    // Real money for a purchase no longer waiting for it — abandoned, its plot
                    // back on sale. Recorded truthfully; a person decides (decided with the user).
                    payment.setReviewReason("Paid after the purchase stopped waiting for payment (abandoned, or "
                            + "already paid by another attempt). Re-allocate the plot if it is still free, or refund.");
                    payments.saveAndFlush(payment);
                    audit(payment, "payment.needs_review", "Paystack confirmed this payment, but the purchase was "
                            + "no longer waiting for payment. Flagged for finance to re-allocate or refund.");
                    log.warn("Payment {} succeeded after its purchase stopped waiting; flagged for finance",
                            payment.getReference());
                }
            } else {
                // Money arrived, but not what was agreed: neither paid nor failed. A person decides.
                payment.setStatus(PaymentStatus.MISMATCHED);
                payments.saveAndFlush(payment);
                audit(payment, "payment.mismatched", "Paystack reported " + verification.amountKobo() + " kobo in "
                        + verification.currency() + " for reference " + verification.reference() + "; we asked for "
                        + payment.getAmountKobo() + " kobo in NGN. Not treated as paid; needs review.");
                log.warn("Payment {} mismatched: Paystack amount/currency differs from what was requested",
                        payment.getReference());
            }
        } else if (FAILED_STATES.contains(verification.paymentStatus())) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setGatewayResponse(verification.gatewayResponse());
            payment.setChannel(verification.channel());
            payment.setVerifiedAt(Instant.now());
            payments.saveAndFlush(payment);
            audit(payment, "payment.failed", "Paystack: " + verification.paymentStatus() + " — "
                    + verification.gatewayResponse() + ".");
        }
        // pending, abandoned, ongoing...: left as it was — a transfer can still land (decided with the user).
        return payment;
    }

    /** PY-8: the exact kobo, the currency and our own reference — a response for anything else is not this payment. */
    private static boolean matchesWhatWeAskedFor(Payment payment, PaystackClient.Verification verification) {
        return verification.amountKobo() == payment.getAmountKobo()
                && "NGN".equals(verification.currency())
                && payment.getReference().equals(verification.reference());
    }

    private void audit(Payment payment, String action, String detail) {
        // The verdict is Paystack's, not a person's: a system entry.
        auditApi.record(AuditEntryRequest.of(null, action, "payment", payment.getId(), payment.getSellerTenantId(),
                "Payment " + payment.getReference() + ": " + detail));
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
