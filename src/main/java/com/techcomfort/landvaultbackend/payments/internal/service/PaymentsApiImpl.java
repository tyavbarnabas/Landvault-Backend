package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.payments.PaymentsApi;
import com.techcomfort.landvaultbackend.payments.internal.domain.Refund;
import com.techcomfort.landvaultbackend.payments.internal.enums.RefundStatus;
import com.techcomfort.landvaultbackend.payments.internal.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentsApiImpl implements PaymentsApi {

    private final RefundRepository refunds;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Instant> refundedAt(Collection<UUID> paymentIds) {
        if (paymentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Instant> refunded = new HashMap<>();
        for (Refund refund : refunds.findAllByPaymentIdInOrderByCreatedAtDesc(paymentIds)) {
            if (refund.getStatus() == RefundStatus.PROCESSED) {
                // Paystack's own refunded_at when it gave one; otherwise when we recorded it processed.
                Instant at = refund.getRefundedAt() != null ? refund.getRefundedAt() : refund.getUpdatedAt();
                refunded.putIfAbsent(refund.getPaymentId(), at);
            }
        }
        return refunded;
    }
}
