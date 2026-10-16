package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.documents.internal.domain.Receipt;
import com.techcomfort.landvaultbackend.documents.internal.repository.ReceiptRepository;
import com.techcomfort.landvaultbackend.payments.ReceiptLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Payments' {@link ReceiptLookup}, so a purchase can name its receipt. Its own
 * bean depending on nothing but the repository: {@code DocumentService} needs
 * the portfolio, whose payment summaries need this — one class would be a
 * bean cycle.
 */
@Service
@RequiredArgsConstructor
public class PaymentReceiptLookup implements ReceiptLookup {

    private final ReceiptRepository receipts;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ReceiptRef> receiptsFor(Collection<UUID> paymentIds) {
        if (paymentIds.isEmpty()) {
            return Map.of();
        }
        return receipts.findAllByPaymentIdIn(paymentIds).stream()
                .collect(Collectors.toMap(Receipt::getPaymentId, r -> new ReceiptRef(r.getId(), r.getNumber())));
    }
}
