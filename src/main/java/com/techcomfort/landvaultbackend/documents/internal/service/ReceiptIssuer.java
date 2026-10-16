package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.documents.internal.domain.Receipt;
import com.techcomfort.landvaultbackend.documents.internal.render.DocumentFormats;
import com.techcomfort.landvaultbackend.documents.internal.repository.ReceiptRepository;
import com.techcomfort.landvaultbackend.payments.PaymentSucceeded;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * RC-1: issues a receipt the moment Paystack confirms a payment, inside the
 * confirming transaction (a plain synchronous listener, MANDATORY — the
 * {@code PlotAllocated} pattern). A succeeded payment and its receipt commit
 * together or not at all. Never make this asynchronous or after-commit.
 * <p>
 * The receipt keeps a copy of everything it prints, read through
 * {@code landvault_receipt_details} (changeset 089): the confirmation runs with
 * no tenant scope, and the labels live in RLS-policied tables.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptIssuer {

    private final ReceiptRepository receipts;
    private final AuditApi auditApi;

    @PersistenceContext
    private EntityManager entityManager;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(PaymentSucceeded event) {
        // Once per payment: a confirmation is a no-op the second time, but be sure.
        if (receipts.existsByPaymentId(event.paymentId())) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager
                .createNativeQuery("SELECT * FROM landvault_receipt_details(:payment)")
                .setParameter("payment", event.paymentId())
                .getResultList();
        if (rows.isEmpty()) {
            // Loud on purpose: a confirmed payment with no receipt is the gap this exists to close.
            throw new IllegalStateException("No receipt details for succeeded payment " + event.paymentId());
        }
        Object[] r = rows.getFirst();
        Instant issuedAt = Instant.now();
        Receipt receipt = receipts.saveAndFlush(Receipt.builder()
                .number(number(issuedAt, receipts.nextNumber()))
                .verificationCode(VerificationCodes.generate())
                .paymentId(event.paymentId())
                .transactionId((UUID) r[0])
                .buyerUserId((UUID) r[1])
                .sellerTenantId((UUID) r[2])
                .amount((BigDecimal) r[3])
                .currency(Currency.valueOf((String) r[4]))
                .channel((String) r[5])
                .last4((String) r[6])
                .paymentReference((String) r[7])
                .paidAt(instant(r[8]))
                .buyerName((String) r[9])
                .sellerName((String) r[10])
                .sellerRcNumber((String) r[11])
                .plotId((UUID) r[12])
                .estateId((UUID) r[13])
                .estateName((String) r[14])
                .estateLocation(location((String) r[15], (String) r[16], (String) r[17]))
                .blockName((String) r[18])
                .plotNumber((String) r[19])
                .plotSizeSqm((BigDecimal) r[20])
                .corner(Boolean.TRUE.equals(r[21]))
                .issuedAt(issuedAt)
                .build());
        // HomeTrue issued it, on Paystack's word: a system entry.
        auditApi.record(AuditEntryRequest.of(null, "receipt.issued", "receipt", receipt.getId(),
                receipt.getSellerTenantId(), "Receipt " + receipt.getNumber() + " issued for payment "
                        + receipt.getPaymentReference() + " (" + DocumentFormats.money(receipt.getAmount(),
                        receipt.getCurrency()) + ")."));
        log.info("Receipt {} issued for payment {}", receipt.getNumber(), receipt.getPaymentReference());
    }

    /** {@code HT-RCT-2026-000123}: the year it was issued, in Nigeria, and a number that never repeats. */
    static String number(Instant issuedAt, long sequence) {
        return "HT-RCT-" + issuedAt.atZone(DocumentFormats.NIGERIA).getYear() + "-" + String.format("%06d", sequence);
    }

    private static String location(String area, String city, String state) {
        String joined = Stream.of(area, city, state).filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }

    /** A timestamptz from a native query: the JDBC driver and Hibernate disagree on the type across versions. */
    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case ZonedDateTime z -> z.toInstant();
            case Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
        };
    }
}
