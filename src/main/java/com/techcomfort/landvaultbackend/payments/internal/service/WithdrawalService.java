package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.PayableTransaction;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.checkout.VerifiedSale;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.dto.WithdrawalDto;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.Refund;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 5a: a buyer withdraws from their instalment plan, at any time and
 * without anyone's approval (decision 15), on the refund terms copied onto the
 * purchase. Each payment comes back less the developer's share — exactly what
 * HomeTrue holds in escrow for it ({@link PayoutTranches#shares}). A payment
 * Paystack confirmed but finance hasn't verified counts as if verified: the
 * buyer gets their part at once, and the developer's share waits for finance
 * (decided with the user). The buyer sees a quote, then confirms naming its
 * total; a Super Admin sends each refund. See AGENTS.md, "Instalment plans".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final CheckoutApi checkoutApi;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final IdentityApi identityApi;
    private final AuditApi auditApi;
    private final SettlementAlertSender alerts;
    private final BuyerNotifier buyerNotifier;
    private final RevocationNotices notices;

    /** The quote while the plan runs; what was settled once withdrawn. The buyer's own purchase only. */
    @Transactional(readOnly = true)
    public WithdrawalDto get(UUID transactionId) {
        PayableTransaction purchase = ownPurchase(transactionId);
        CheckoutApi.PlanEscrow escrow = escrowOf(purchase);
        if (purchase.isWithdrawn() || purchase.isRevoked()) {
            return settled(purchase, escrow);
        }
        return quote(purchase, escrow, payments.findAllByTransactionIdOrderByCreatedAtAsc(transactionId)).dto();
    }

    @Transactional
    public WithdrawalDto withdraw(UUID transactionId, BigDecimal quotedRefund, String reason) {
        PayableTransaction purchase = ownPurchase(transactionId);
        CheckoutApi.PlanEscrow escrow = escrowOf(purchase);
        // The payments locked first — the same order finance verifying one takes —
        // so a payment can't be verified or rejected while the figures are fixed.
        payments.findLockedByTransactionIdAndStatus(transactionId, PaymentStatus.SUCCEEDED);
        Quote quote = quote(purchase, escrow, payments.findAllByTransactionIdOrderByCreatedAtAsc(transactionId));
        if (quote.refund().compareTo(quotedRefund) != 0) {
            throw new PaymentException.WithdrawalQuoteChanged();
        }
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        if (!checkoutApi.withdraw(transactionId, purchase.buyerUserId(), why)) {
            throw new PaymentException.PlanNotWithdrawable(purchase.status());
        }
        Instant now = Instant.now();
        flagRefunds(purchase, quote, purchase.buyerUserId(), now, "You withdrew from your plan", "the buyer withdrew");
        // 5b: a notice of revocation running at the time is overtaken.
        notices.closeOnWithdrawal(transactionId);
        log.info("Buyer {} withdrew from plan {}: refund {}, developer keeps {}", purchase.buyerUserId(),
                transactionId, quote.refund(), quote.developerKeeps());
        tell(purchase, quote, why);
        return quote.withdrawn(now).dto();
    }

    /**
     * Each counted payment is owed back less the developer's share — the same
     * for a withdrawal and (5b) a revocation.
     *
     * @param why    what the buyer reads, e.g. "You withdrew from your plan"
     * @param whyLog what the audit entry says happened, e.g. "the buyer withdrew"
     */
    void flagRefunds(PayableTransaction purchase, Quote quote, UUID actor, Instant now, String why, String whyLog) {
        quote.shares().forEach((payment, share) -> {
            BigDecimal back = payment.getAmount().subtract(share);
            if (back.signum() <= 0) {
                return;
            }
            payment.setRefundAmount(back);
            payment.setRefundRequestedAt(now);
            payment.setRefundReason(why + ": returned on the declared terms"
                    + (share.signum() > 0 ? "; the developer keeps NGN " + share.toPlainString() + "." : "."));
            payments.save(payment);
            auditApi.record(AuditEntryRequest.of(actor, "payment.refund_due", "payment",
                    payment.getId(), purchase.sellerTenantId(), "Payment " + payment.getReference() + ": NGN "
                            + back.toPlainString() + " owed back after " + whyLog + "; the developer keeps NGN "
                            + share.toPlainString() + "."));
        });
    }

    /**
     * 5b: what a running plan would return if it ended now — the figures a
     * revocation settles on. The successful payments are locked first, as for
     * a withdrawal.
     */
    Quote quoteToEnd(PayableTransaction purchase) {
        CheckoutApi.PlanEscrow escrow = escrowOf(purchase);
        payments.findLockedByTransactionIdAndStatus(purchase.id(), PaymentStatus.SUCCEEDED);
        return quote(purchase, escrow, payments.findAllByTransactionIdOrderByCreatedAtAsc(purchase.id()));
    }

    /** 5b: what an ended plan settled on, as recorded on its payments. */
    WithdrawalDto settledRecord(PayableTransaction purchase) {
        return settled(purchase, escrowOf(purchase));
    }

    /** 5b: the same figures, read only — for the developer's screen and the notice email. */
    Quote preview(PayableTransaction purchase) {
        return quote(purchase, escrowOf(purchase), payments.findAllByTransactionIdOrderByCreatedAtAsc(purchase.id()));
    }

    // --- the figures ---

    /** @param shares what the developer keeps of each payment counted, in settlement order */
    record Quote(UUID transactionId, String status, Instant withdrawnAt, VerifiedSale.Escrow escrow,
                         Map<Payment, BigDecimal> shares, BigDecimal paid, BigDecimal refund,
                         BigDecimal developerKeeps, BigDecimal awaitingFinanceShare, String currency) {

        Quote withdrawn(Instant at) {
            return new Quote(transactionId, "withdrawn", at, escrow, shares, paid, refund, developerKeeps,
                    awaitingFinanceShare, currency);
        }

        WithdrawalDto dto() {
            List<WithdrawalDto.Line> lines = shares.entrySet().stream().map(e -> line(e.getKey(), e.getValue(),
                    e.getKey().getAmount().subtract(e.getValue()), null)).toList();
            return new WithdrawalDto(transactionId, status, withdrawnAt, terms(escrow), paid, refund, developerKeeps,
                    currency, lines);
        }
    }

    private Quote quote(PayableTransaction purchase, CheckoutApi.PlanEscrow escrow, List<Payment> all) {
        if (!purchase.isOnPlan()) {
            throw new PaymentException.PlanNotWithdrawable(purchase.status());
        }
        List<Payment> counted = PayoutTranches.countedOnWithdrawal(all);
        Map<Payment, BigDecimal> shares = PayoutTranches.shares(escrow.escrow(), escrow.landPrice(), counted);
        BigDecimal paid = sum(counted.stream().map(Payment::getAmount).toList());
        BigDecimal kept = sum(shares.values());
        BigDecimal awaiting = sum(shares.entrySet().stream().filter(e -> e.getKey().getFinanceVerifiedAt() == null)
                .map(Map.Entry::getValue).toList());
        return new Quote(purchase.id(), "on_plan", null, escrow.escrow(), shares, paid, paid.subtract(kept), kept,
                awaiting, purchase.currency().name());
    }

    /**
     * Once withdrawn, the record is what each payment was flagged with — not a
     * recomputation — so a later finance rejection (the share refunded too) shows.
     */
    private WithdrawalDto settled(PayableTransaction purchase, CheckoutApi.PlanEscrow escrow) {
        List<Payment> counted = payments.findAllByTransactionIdOrderByCreatedAtAsc(purchase.id()).stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED && p.getPurpose() != null)
                .filter(p -> p.getFinanceVerifiedAt() != null || p.getRefundRequestedAt() != null)
                .sorted(Comparator.comparing(Payment::getPaidAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder())))
                .toList();
        Map<UUID, Refund> newest = refunds.findAllByPaymentIdInOrderByCreatedAtDesc(
                        counted.stream().map(Payment::getId).toList()).stream()
                .collect(Collectors.toMap(Refund::getPaymentId, r -> r, (a, b) -> a));
        List<WithdrawalDto.Line> lines = counted.stream().map(p -> {
            BigDecimal back = p.getRefundRequestedAt() == null ? BigDecimal.ZERO : p.owedBack();
            return line(p, p.getAmount().subtract(back), back,
                    Optional.ofNullable(newest.get(p.getId())).map(r -> r.getStatus().wire()).orElse(null));
        }).toList();
        BigDecimal paid = sum(lines.stream().map(WithdrawalDto.Line::amount).toList());
        BigDecimal refund = sum(lines.stream().map(WithdrawalDto.Line::refund).toList());
        Instant endedAt = (purchase.isRevoked() ? checkoutApi.revokedAt(purchase.id())
                : checkoutApi.withdrawnAt(purchase.id())).orElse(null);
        return new WithdrawalDto(purchase.id(), purchase.status(), endedAt, terms(escrow.escrow()), paid, refund,
                paid.subtract(refund), purchase.currency().name(), lines);
    }

    private static WithdrawalDto.Line line(Payment p, BigDecimal developerKeeps, BigDecimal refund, String refundStatus) {
        return new WithdrawalDto.Line(p.getReference(), p.getPurpose().name().toLowerCase(Locale.ROOT),
                p.getInstalmentSequence(), p.getAmount(), p.getPenaltyAmount(), developerKeeps, refund,
                p.getFinanceVerifiedAt() == null && p.getReviewReason() == null, refundStatus);
    }

    private static WithdrawalDto.Terms terms(VerifiedSale.Escrow escrow) {
        return new WithdrawalDto.Terms(escrow.deductionPct(),
                escrow.appliesTo() == null ? null : escrow.appliesTo().name().toLowerCase(Locale.ROOT),
                escrow.penaltiesRefundable());
    }

    // --- telling people ---

    /** After commit; a failed email never undoes the withdrawal. */
    private void tell(PayableTransaction purchase, Quote quote, String reason) {
        String planName = Optional.ofNullable(checkoutApi.schedulesOf(List.of(purchase.id())).get(purchase.id()))
                .map(PlanSchedule::planName).orElse("instalment plan");
        String url = buyerNotifier.purchaseUrl(purchase.id());
        String awaiting = quote.awaitingFinanceShare().signum() > 0 ? quote.awaitingFinanceShare().toPlainString() : null;
        identityApi.emailOf(purchase.buyerUserId()).ifPresent(to -> buyerNotifier.afterCommit("plan withdrawn",
                () -> alerts.planWithdrawn(alert(to, false, purchase, planName, quote, null, null, url))));
        for (String to : identityApi.executiveDirectorEmails(purchase.sellerTenantId())) {
            buyerNotifier.afterCommit("plan withdrawn (developer)",
                    () -> alerts.planWithdrawn(alert(to, true, purchase, planName, quote, awaiting, reason, null)));
        }
    }

    private static SettlementAlertSender.WithdrawalAlert alert(String to, boolean developer, PayableTransaction purchase,
                                                               String planName, Quote quote, String awaiting,
                                                               String reason, String url) {
        return new SettlementAlertSender.WithdrawalAlert(to, developer, purchase.reference(), planName,
                quote.paid().toPlainString(), quote.refund().toPlainString(), quote.developerKeeps().toPlainString(),
                awaiting, reason, url);
    }

    // --- helpers ---

    private PayableTransaction ownPurchase(UUID transactionId) {
        return checkoutApi.transactionForBuyer(transactionId, currentScope().userId())
                .orElseThrow(PaymentException.TransactionNotFound::new);
    }

    private CheckoutApi.PlanEscrow escrowOf(PayableTransaction purchase) {
        return checkoutApi.escrowOf(purchase.id())
                .orElseThrow(() -> new PaymentException.PlanNotWithdrawable(purchase.status()));
    }

    private static BigDecimal sum(Collection<BigDecimal> amounts) {
        return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
