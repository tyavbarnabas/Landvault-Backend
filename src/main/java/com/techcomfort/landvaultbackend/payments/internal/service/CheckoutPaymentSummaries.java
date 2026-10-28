package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.PaymentSummary;
import com.techcomfort.landvaultbackend.checkout.PlanPayment;
import com.techcomfort.landvaultbackend.checkout.PlanRefund;
import com.techcomfort.landvaultbackend.checkout.FlexiblePurchase;
import com.techcomfort.landvaultbackend.checkout.FlexibleStanding;
import com.techcomfort.landvaultbackend.checkout.PaymentSummaryProvider;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.checkout.PlanStanding;
import com.techcomfort.landvaultbackend.payments.ReceiptLookup;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.Refund;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.enums.RefundStatus;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Checkout's {@link PaymentSummaryProvider} — the inverted interface: checkout
 * declares it, payments fills it in. For each purchase, the payment that
 * matters to the buyer (decided with the user): the successful attempt if
 * there is one, otherwise the most recent, with its receipt when it has one.
 * Batched — three queries per page.
 */
@Service
@RequiredArgsConstructor
public class CheckoutPaymentSummaries implements PaymentSummaryProvider {

    private static final String LATE_PAYMENT = "late_payment";
    private static final String REFUND_DUE = "refund_due";

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final ReceiptLookup receiptLookup;
    private final PlanStandings planStandings;
    private final FlexibleStandings flexibleStandings;

    @Override
    public Map<UUID, PlanStanding> standingsFor(Map<UUID, PlanSchedule> schedules) {
        return planStandings.standingsOf(schedules, PlanStandings.today());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, FlexibleStanding> flexibleStandingsFor(Map<UUID, FlexiblePurchase> plans) {
        return flexibleStandings.standingsOf(plans);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<PlanPayment>> succeededPaymentsFor(Collection<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return Map.of();
        }
        List<Payment> paid = payments.findAllByTransactionIdIn(transactionIds).stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED && p.getReviewReason() == null)
                .sorted(Comparator.comparing(Payment::getCreatedAt))
                .toList();
        Map<UUID, ReceiptLookup.ReceiptRef> receipts = receiptLookup.receiptsFor(paid.stream().map(Payment::getId).toList());
        return paid.stream().collect(Collectors.groupingBy(Payment::getTransactionId, Collectors.mapping(p -> {
            ReceiptLookup.ReceiptRef receipt = receipts.get(p.getId());
            return new PlanPayment(p.getReference(), p.getPurpose().name().toLowerCase(Locale.ROOT),
                    p.getInstalmentSequence(), p.getAmount(), p.getPaidAt(), p.getFinanceVerifiedAt(),
                    receipt == null ? null : receipt.id(), p.getPenaltyAmount());
        }, Collectors.toList())));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PaymentSummary> summariesFor(Collection<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Payment> chosen = payments.findAllByTransactionIdIn(transactionIds).stream()
                .collect(Collectors.groupingBy(Payment::getTransactionId)).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> choose(e.getValue())));
        Map<UUID, Refund> latestRefund = refunds.findAllByPaymentIdInOrderByCreatedAtDesc(
                        chosen.values().stream().map(Payment::getId).toList()).stream()
                .collect(Collectors.toMap(Refund::getPaymentId, r -> r, (newest, older) -> newest));
        Map<UUID, ReceiptLookup.ReceiptRef> receipts = receiptLookup.receiptsFor(
                chosen.values().stream().map(Payment::getId).toList());
        Map<UUID, PaymentSummary> summaries = new HashMap<>();
        chosen.forEach((transactionId, p) -> {
            Refund refund = latestRefund.get(p.getId());
            ReceiptLookup.ReceiptRef receipt = receipts.get(p.getId());
            boolean underReview = p.getReviewReason() != null;
            summaries.put(transactionId, new PaymentSummary(p.getReference(), p.getStatus().wire(),
                    p.getStatus() == PaymentStatus.SUCCEEDED ? p.getAmount() : null, p.getPaidAt(), underReview,
                    !underReview ? null : p.getRefundRequestedAt() != null ? REFUND_DUE : LATE_PAYMENT,
                    refund == null ? null : refund.getStatus().wire(),
                    receipt == null ? null : receipt.id(), receipt == null ? null : receipt.number()));
        });
        return summaries;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PlanRefund> refundsFor(Collection<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Payment>> owedBack = payments.findAllByTransactionIdIn(transactionIds).stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED && p.getRefundRequestedAt() != null)
                .collect(Collectors.groupingBy(Payment::getTransactionId));
        Map<UUID, List<Refund>> refundsByPayment = refunds.findAllByPaymentIdInOrderByCreatedAtDesc(
                        owedBack.values().stream().flatMap(List::stream).map(Payment::getId).toList()).stream()
                .collect(Collectors.groupingBy(Refund::getPaymentId));
        Map<UUID, PlanRefund> out = new HashMap<>();
        owedBack.forEach((transactionId, paid) -> {
            BigDecimal amount = paid.stream().map(Payment::owedBack).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<Refund> sent = paid.stream().flatMap(p -> refundsByPayment.getOrDefault(p.getId(), List.of()).stream())
                    .toList();
            BigDecimal refunded = sent.stream().filter(r -> r.getStatus() == RefundStatus.PROCESSED)
                    .map(Refund::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean needsAccount = sent.stream().anyMatch(r -> r.getStatus() == RefundStatus.NEEDS_ATTENTION
                    && r.getAccountSubmittedAt() == null);
            String status = amount.signum() == 0 ? "none"
                    : refunded.compareTo(amount) >= 0 ? "refunded"
                    : refunded.signum() > 0 ? "partly_refunded" : "pending";
            out.put(transactionId, new PlanRefund(amount, refunded, status, needsAccount));
        });
        return out;
    }

    /** The newest successful attempt if any (a plan has many), otherwise the newest. */
    private static Payment choose(List<Payment> attempts) {
        Comparator<Payment> newest = Comparator.comparing(Payment::getCreatedAt);
        return attempts.stream().filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED).max(newest)
                .orElseGet(() -> attempts.stream().max(newest).orElseThrow());
    }
}
