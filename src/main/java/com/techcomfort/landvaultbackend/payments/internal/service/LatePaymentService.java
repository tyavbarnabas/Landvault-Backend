package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.AbandonedSale;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.FinanceDecision;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.dto.FinanceDecisionDto;
import com.techcomfort.landvaultbackend.payments.dto.LatePaymentDto;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Late money: a payment Paystack confirmed after its purchase had been
 * abandoned (step 6 records it truthfully and flags it). The selling
 * company's finance decides (decided with the user): allocate — only while the
 * plot is still for sale, at the price the buyer agreed and paid — or refund,
 * which hands it to LandVault's refund flow. The buyer is told either way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LatePaymentService {

    private final PaymentRepository payments;
    private final CheckoutApi checkoutApi;
    private final IdentityApi identityApi;
    private final SettlementAlertSender alerts;
    private final AuditApi auditApi;

    @Transactional(readOnly = true)
    public List<LatePaymentDto> list() {
        UUID tenantId = company(currentScope());
        Map<UUID, Payment> late = payments.findAllBySellerTenantIdAndStatusAndReviewReasonIsNotNullAndRefundRequestedAtIsNull(
                        tenantId, PaymentStatus.SUCCEEDED).stream()
                .collect(Collectors.toMap(Payment::getTransactionId, Function.identity(), (a, b) -> a));
        if (late.isEmpty()) {
            return List.of();
        }
        return checkoutApi.abandonedSales(tenantId, late.keySet()).stream()
                .map(sale -> toDto(sale, late.get(sale.transactionId()))).toList();
    }

    /**
     * Allocate: Paystack's confirmed amount must equal the agreed price (the
     * step-7 check), then the plot is sold and the purchase verified, together
     * or not at all, only if the plot is still for sale.
     */
    @Transactional
    public FinanceDecisionDto allocate(UUID transactionId) {
        TenantScope scope = currentScope();
        UUID tenantId = company(scope);
        Payment payment = latePayment(transactionId, tenantId);
        AbandonedSale sale = sale(transactionId, tenantId);
        if (!matches(payment, sale)) {
            throw new PaymentException.PaymentNotConfirmed();
        }
        FinanceDecision decision = checkoutApi.allocateAbandoned(transactionId, tenantId, scope.userId());
        if (decision != FinanceDecision.DONE) {
            throw FinanceVerificationService.failure(decision);
        }
        payment.setReviewReason(null);
        payments.save(payment);
        auditApi.record(AuditEntryRequest.of(scope.userId(), "payment.late_allocated", "payment", payment.getId(),
                tenantId, "Late payment " + payment.getReference() + " accepted; " + sale.plotLabel() + " allocated."));
        tell(payment, sale, true, null);
        log.info("Late payment {} allocated by {}", payment.getReference(), scope.userId());
        return new FinanceDecisionDto(transactionId, "verified");
    }

    /** Refund: the payment becomes owed back and joins LandVault's refunds-due list. Nothing about the plot changes. */
    @Transactional
    public FinanceDecisionDto refund(UUID transactionId, String reason) {
        TenantScope scope = currentScope();
        UUID tenantId = company(scope);
        Payment payment = latePayment(transactionId, tenantId);
        AbandonedSale sale = sale(transactionId, tenantId);
        payment.setRefundRequestedAt(Instant.now());
        payment.setReviewReason("Refund due: late payment not allocated — " + reason.trim());
        payments.save(payment);
        auditApi.record(AuditEntryRequest.of(scope.userId(), "payment.refund_due", "payment", payment.getId(), tenantId,
                "Late payment " + payment.getReference() + " to be refunded: " + reason.trim()));
        tell(payment, sale, false, reason.trim());
        return new FinanceDecisionDto(transactionId, "refund_due");
    }

    // --- helpers ---

    private Payment latePayment(UUID transactionId, UUID tenantId) {
        return payments.findLockedByTransactionIdAndStatus(transactionId, PaymentStatus.SUCCEEDED).stream()
                .filter(p -> tenantId.equals(p.getSellerTenantId()))
                .filter(p -> p.getReviewReason() != null && p.getRefundRequestedAt() == null)
                .findFirst()
                .orElseThrow(PaymentException.LatePaymentNotFound::new);
    }

    /** The purchase must still be abandoned and visible to this officer (row-level security walls a branch). */
    private AbandonedSale sale(UUID transactionId, UUID tenantId) {
        return checkoutApi.abandonedSales(tenantId, List.of(transactionId)).stream().findFirst()
                .orElseThrow(PaymentException.LatePaymentNotFound::new);
    }

    private void tell(Payment payment, AbandonedSale sale, boolean allocated, String reason) {
        identityApi.emailOf(payment.getBuyerUserId()).ifPresent(to -> alerts.latePaymentResolved(
                new SettlementAlertSender.LatePaymentAlert(to, sale.plotLabel(), sale.estateName(),
                        payment.getAmount().toPlainString(), allocated, reason)));
    }

    private LatePaymentDto toDto(AbandonedSale sale, Payment p) {
        return new LatePaymentDto(sale.transactionId(), sale.transactionReference(), sale.estateName(),
                sale.plotLabel(), identityApi.fullNameOf(p.getBuyerUserId()).orElse(null),
                identityApi.emailOf(p.getBuyerUserId()).orElse(null), p.getReference(), sale.totalPrice(),
                p.getAmount(), p.getCurrency().name(), p.getChannel(), p.getPaidAt(), sale.plotAvailable(),
                matches(p, sale));
    }

    private static boolean matches(Payment payment, AbandonedSale sale) {
        return payment.getAmount().compareTo(sale.totalPrice()) == 0 && payment.getCurrency() == sale.currency();
    }

    private static UUID company(TenantScope scope) {
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
