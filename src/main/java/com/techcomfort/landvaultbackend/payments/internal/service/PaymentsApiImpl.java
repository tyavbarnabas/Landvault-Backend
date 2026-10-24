package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.payments.PaymentsApi;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.PlanRestructure;
import com.techcomfort.landvaultbackend.payments.internal.domain.Refund;
import com.techcomfort.landvaultbackend.payments.internal.enums.RefundStatus;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PlanRestructureRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PaymentsApiImpl implements PaymentsApi {

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PlanRestructureRepository restructures;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> restructureLetterStatuses(Collection<UUID> letterIds) {
        return letterIds.isEmpty() ? Map.of() : restructures.findByLetterIdIn(letterIds).stream()
                .collect(Collectors.toMap(PlanRestructure::getLetterId, r -> r.getStatus().wire()));
    }

    /**
     * When each payment was returned IN FULL — the newest processed refund once
     * the processed refunds add up to the whole payment. Phase 5a: a payment
     * returned only in part (the developer kept their declared share) is not
     * "refunded"; its receipt stays valid.
     */
    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Instant> refundedAt(Collection<UUID> paymentIds) {
        if (paymentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, BigDecimal> amounts = payments.findAllById(paymentIds).stream()
                .collect(Collectors.toMap(Payment::getId, Payment::getAmount));
        Map<UUID, BigDecimal> returned = new HashMap<>();
        Map<UUID, Instant> latest = new HashMap<>();
        for (Refund refund : refunds.findAllByPaymentIdInOrderByCreatedAtDesc(paymentIds)) {
            if (refund.getStatus() == RefundStatus.PROCESSED) {
                returned.merge(refund.getPaymentId(), refund.getAmount(), BigDecimal::add);
                // Paystack's own refunded_at when it gave one; otherwise when we recorded it processed.
                Instant at = refund.getRefundedAt() != null ? refund.getRefundedAt() : refund.getUpdatedAt();
                latest.merge(refund.getPaymentId(), at, (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        Map<UUID, Instant> refunded = new HashMap<>();
        latest.forEach((paymentId, at) -> {
            BigDecimal amount = amounts.get(paymentId);
            if (amount != null && returned.get(paymentId).compareTo(amount) >= 0) {
                refunded.put(paymentId, at);
            }
        });
        return refunded;
    }
}
