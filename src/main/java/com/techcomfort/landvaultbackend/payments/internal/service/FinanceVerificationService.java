package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.FinanceDecision;
import com.techcomfort.landvaultbackend.checkout.FinanceQueueRow;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.dto.FinanceDecisionDto;
import com.techcomfort.landvaultbackend.payments.dto.FinanceQueueItemDto;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * FV-1..FV-3: the selling company's finance staff verify a payment before the
 * plot is sold (decided with the user). A confirmed Paystack payment is a
 * signal, never allocation; this is the human step AGENTS.md's two-step
 * payment rule requires.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinanceVerificationService {

    private final CheckoutApi checkoutApi;
    private final IdentityApi identityApi;
    private final PaymentRepository payments;
    private final AuditApi auditApi;

    @Transactional(readOnly = true)
    public List<FinanceQueueItemDto> queue() {
        UUID tenantId = companyOf(currentScope());
        return checkoutApi.awaitingFinance(tenantId).stream().map(this::toItem).toList();
    }

    /**
     * FV-3: allocate. Checked first — a confirmed Paystack payment for exactly
     * the agreed amount must be on file — then checkout sells the plot,
     * verifies the transaction and converts the reservation in one transaction.
     */
    @Transactional
    public FinanceDecisionDto verify(UUID transactionId) {
        TenantScope scope = currentScope();
        UUID tenantId = companyOf(scope);
        FinanceQueueRow row = checkoutApi.awaitingFinance(tenantId).stream()
                .filter(r -> r.transactionId().equals(transactionId))
                .findFirst()
                .orElse(null);
        if (row == null) {
            // Not in this caller's queue. Find out why WITHOUT acting: read-only.
            String status = checkoutApi.financeStatusOf(transactionId, tenantId)
                    .orElseThrow(PaymentException.TransactionNotFound::new);
            throw "awaiting_finance".equals(status) ? new PaymentException.TransactionNotFound()
                    : new PaymentException.NotAwaitingFinance();
        }
        Optional<Payment> payment = confirmedPaymentFor(transactionId);
        if (payment.isEmpty() || !matches(payment.get(), row)) {
            throw new PaymentException.PaymentNotConfirmed();
        }
        FinanceDecision decision = checkoutApi.verifyAndAllocate(transactionId, tenantId, scope.userId());
        if (decision != FinanceDecision.DONE) {
            throw failure(decision);
        }
        log.info("Transaction {} verified by finance user {}; plot allocated", transactionId, scope.userId());
        return new FinanceDecisionDto(transactionId, "verified");
    }

    /** The plot goes back on sale; the money is flagged for refund (decided with the user — refunds are manual for now). */
    @Transactional
    public FinanceDecisionDto reject(UUID transactionId, String reason) {
        TenantScope scope = currentScope();
        UUID tenantId = companyOf(scope);
        FinanceDecision decision = checkoutApi.rejectPayment(transactionId, tenantId, scope.userId(), reason.trim());
        if (decision != FinanceDecision.DONE) {
            throw failure(decision);
        }
        for (Payment payment : payments.findLockedByTransactionIdAndStatus(transactionId, PaymentStatus.SUCCEEDED)) {
            payment.setReviewReason("Refund due: finance rejected this payment — " + reason.trim());
            payment.setRefundRequestedAt(Instant.now());
            payments.save(payment);
            auditApi.record(AuditEntryRequest.of(scope.userId(), "payment.refund_due", "payment", payment.getId(),
                    tenantId, "Payment " + payment.getReference() + " flagged for refund after finance rejected it."));
        }
        return new FinanceDecisionDto(transactionId, "rejected");
    }

    private FinanceQueueItemDto toItem(FinanceQueueRow row) {
        Optional<Payment> payment = confirmedPaymentFor(row.transactionId());
        FinanceQueueItemDto.GatewayRecord record = payment.map(p -> new FinanceQueueItemDto.GatewayRecord(
                p.getReference(), p.getAmount(), p.getCurrency().name(), p.getChannel(), p.getPaidAt(),
                p.getLast4(), p.getGatewayResponse())).orElse(null);
        return new FinanceQueueItemDto(row.transactionId(), row.transactionReference(), row.estateId(),
                row.estateName(), row.plotId(), row.plotLabel(), identityApi.emailOf(row.buyerUserId()).orElse(null),
                row.totalPrice(), row.currency().name(), row.awaitingSince(), record,
                payment.isPresent() && matches(payment.get(), row));
    }

    private Optional<Payment> confirmedPaymentFor(UUID transactionId) {
        return payments.findFirstByTransactionIdAndStatusOrderByCreatedAtDesc(transactionId, PaymentStatus.SUCCEEDED);
    }

    private static boolean matches(Payment payment, FinanceQueueRow row) {
        return payment.getAmount().compareTo(row.totalPrice()) == 0 && payment.getCurrency() == row.currency();
    }

    static RuntimeException failure(FinanceDecision decision) {
        return switch (decision) {
            case NOT_FOUND -> new PaymentException.TransactionNotFound();
            case NOT_AWAITING_FINANCE -> new PaymentException.NotAwaitingFinance();
            case PLOT_NOT_RESERVED -> new PaymentException.PlotNotReserved();
            case NOT_ABANDONED -> new PaymentException.LatePaymentNotFound();
            case PLOT_NOT_AVAILABLE -> new PaymentException.PlotNoLongerAvailable();
            case DONE -> new IllegalStateException("DONE is not a failure");
        };
    }

    private static UUID companyOf(TenantScope scope) {
        if (scope.tenantId() == null) {
            throw new PaymentException.CompanyScopeRequired();
        }
        return scope.tenantId();
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
