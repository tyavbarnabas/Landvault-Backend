package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.FinanceDecision;
import com.techcomfort.landvaultbackend.checkout.FinanceQueueRow;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.AllocationLetterLookup;
import com.techcomfort.landvaultbackend.payments.dto.FinanceDecisionDto;
import com.techcomfort.landvaultbackend.payments.dto.FinanceQueueItemDto;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.PaymentPenalty;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentPurpose;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentPenaltyRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final AllocationLetterLookup letterLookup;
    private final IdentityApi identityApi;
    private final PaymentRepository payments;
    private final AuditApi auditApi;
    private final SettlementAlertSender alerts;
    private final BuyerNotifier buyerNotifier;
    private final PaymentPenaltyRepository penalties;

    private static final List<PaymentPurpose> PLAN_PAYMENTS = List.of(PaymentPurpose.INSTALMENT, PaymentPurpose.BALANCE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

    /**
     * Purchases awaiting finance (the full price or a plan's deposit), then —
     * 3b — instalments and pay-offs Paystack confirmed that finance hasn't
     * verified yet, on plans this caller can see (RLS: a branch officer sees
     * only their branch's).
     */
    @Transactional(readOnly = true)
    public List<FinanceQueueItemDto> queue() {
        UUID tenantId = companyOf(currentScope());
        List<FinanceQueueItemDto> items = new ArrayList<>(
                checkoutApi.awaitingFinance(tenantId).stream().map(this::toItem).toList());
        List<Payment> planPayments = payments
                .findAllBySellerTenantIdAndStatusAndPurposeInAndFinanceVerifiedAtIsNullAndReviewReasonIsNullOrderByPaidAtAsc(
                        tenantId, PaymentStatus.SUCCEEDED, PLAN_PAYMENTS);
        List<UUID> ids = planPayments.stream().map(Payment::getTransactionId).distinct().toList();
        Map<UUID, FinanceQueueRow> rows = checkoutApi.onPlan(tenantId, ids).stream()
                .collect(Collectors.toMap(FinanceQueueRow::transactionId, Function.identity()));
        Map<UUID, PlanSchedule> schedules = checkoutApi.schedulesOf(rows.keySet());
        planPayments.stream().filter(p -> rows.containsKey(p.getTransactionId()))
                .map(p -> planItem(rows.get(p.getTransactionId()), p, schedules.get(p.getTransactionId())))
                .forEach(items::add);
        return items;
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
            Optional<PlanPayment> planPayment = planPaymentAwaiting(transactionId, tenantId);
            if (planPayment.isPresent()) {
                return verifyPlanPayment(planPayment.get(), tenantId, scope.userId());
            }
            // Not in this caller's queue. Find out why WITHOUT acting: read-only.
            String status = checkoutApi.financeStatusOf(transactionId, tenantId)
                    .orElseThrow(PaymentException.TransactionNotFound::new);
            throw "awaiting_finance".equals(status) ? new PaymentException.TransactionNotFound()
                    : new PaymentException.NotAwaitingFinance();
        }
        refuseOwnPurchase(row.buyerUserId(), scope.userId());
        Optional<Payment> payment = confirmedPaymentFor(transactionId);
        if (payment.isEmpty() || !matches(payment.get(), row)) {
            throw new PaymentException.PaymentNotConfirmed();
        }
        FinanceDecision decision = checkoutApi.verifyAndAllocate(transactionId, tenantId, scope.userId());
        if (decision != FinanceDecision.DONE) {
            throw failure(decision);
        }
        markVerified(payment.get(), scope.userId());
        log.info("Transaction {} verified by finance user {}; plot allocated", transactionId, scope.userId());
        Optional<AllocationLetterLookup.LetterRef> letter = letterLookup.letterFor(transactionId);
        tellBuyer(row, payment.get().getAmount(), true, null, letter, row.isDeposit());
        return row.isDeposit() ? FinanceDecisionDto.depositVerified(transactionId, letter)
                : FinanceDecisionDto.verified(transactionId, letter);
    }

    /** The plot goes back on sale; the money is flagged for refund (decided with the user — refunds are manual for now). */
    @Transactional
    public FinanceDecisionDto reject(UUID transactionId, String reason) {
        TenantScope scope = currentScope();
        UUID tenantId = companyOf(scope);
        payments.findAllByTransactionIdIn(List.of(transactionId)).stream().findFirst()
                .ifPresent(p -> refuseOwnPurchase(p.getBuyerUserId(), scope.userId()));
        Optional<PlanPayment> planPayment = planPaymentAwaiting(transactionId, tenantId);
        if (planPayment.isPresent()) {
            return rejectPlanPayment(planPayment.get(), tenantId, scope.userId(), reason.trim());
        }
        // Read before deciding, for the buyer's email; absent means the decision below refuses anyway.
        Optional<FinanceQueueRow> row = checkoutApi.awaitingFinance(tenantId).stream()
                .filter(r -> r.transactionId().equals(transactionId))
                .findFirst();
        FinanceDecision decision = checkoutApi.rejectPayment(transactionId, tenantId, scope.userId(), reason.trim());
        if (decision != FinanceDecision.DONE) {
            throw failure(decision);
        }
        BigDecimal returned = BigDecimal.ZERO;
        for (Payment payment : payments.findLockedByTransactionIdAndStatus(transactionId, PaymentStatus.SUCCEEDED)) {
            returned = returned.add(payment.getAmount());
            payment.setReviewReason("Refund due: finance rejected this payment — " + reason.trim());
            payment.setRefundRequestedAt(Instant.now());
            payments.save(payment);
            auditApi.record(AuditEntryRequest.of(scope.userId(), "payment.refund_due", "payment", payment.getId(),
                    tenantId, "Payment " + payment.getReference() + " flagged for refund after finance rejected it."));
        }
        // Only money that actually arrived is promised back.
        if (row.isPresent() && returned.signum() > 0) {
            tellBuyer(row.get(), returned, false, reason.trim(), Optional.empty(), false);
        }
        return FinanceDecisionDto.of(transactionId, "rejected");
    }

    /** Sent after this decision commits; a failed email leaves the decision standing. */
    private void tellBuyer(FinanceQueueRow row, BigDecimal amount, boolean verified, String reason,
                           Optional<AllocationLetterLookup.LetterRef> letter, boolean deposit) {
        identityApi.emailOf(row.buyerUserId()).ifPresent(to -> buyerNotifier.afterCommit(
                verified ? "purchase verified" : "purchase rejected",
                () -> alerts.purchaseDecided(new SettlementAlertSender.PurchaseDecisionAlert(to, row.plotLabel(),
                        row.estateName(), amount.toPlainString(), verified, reason,
                        buyerNotifier.purchaseUrl(row.transactionId()),
                        letter.map(AllocationLetterLookup.LetterRef::number).orElse(null),
                        letter.map(l -> buyerNotifier.letterUrl(l.id())).orElse(null),
                        verified && deposit ? row.planName() : null))));
    }

    private FinanceQueueItemDto toItem(FinanceQueueRow row) {
        Optional<Payment> payment = confirmedPaymentFor(row.transactionId());
        FinanceQueueItemDto.GatewayRecord record = payment.map(FinanceVerificationService::gateway).orElse(null);
        return new FinanceQueueItemDto(row.transactionId(), row.transactionReference(), row.estateId(),
                row.estateName(), row.plotId(), row.plotLabel(), identityApi.emailOf(row.buyerUserId()).orElse(null),
                row.amountDue(), row.currency().name(), row.awaitingSince(), record,
                payment.isPresent() && matches(payment.get(), row), row.letterApproved(),
                row.isDeposit() ? "deposit" : "full_price", row.planName(), null, null, null, BigDecimal.ZERO, List.of(),
                false);
    }

    // ------------------------------------------------------------------ 3b: instalments

    /** A plan payment awaiting finance, with its purchase (as this caller sees it) and schedule. */
    private record PlanPayment(Payment payment, FinanceQueueRow row, PlanSchedule schedule, PlanProgress progress) {
    }

    private Optional<PlanPayment> planPaymentAwaiting(UUID transactionId, UUID tenantId) {
        Optional<FinanceQueueRow> row = checkoutApi.onPlan(tenantId, List.of(transactionId)).stream().findFirst();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        PlanSchedule schedule = checkoutApi.schedulesOf(List.of(transactionId)).get(transactionId);
        PlanProgress progress = PlanProgress.of(schedule, payments.findAllByTransactionIdOrderByCreatedAtAsc(transactionId));
        return progress.awaitingFinance().map(p -> new PlanPayment(p, row.get(), schedule, progress));
    }

    /**
     * Decision 5: finance verifies every instalment. The amount must be exactly
     * what the schedule says; the last one (or a pay-off) completes the plan —
     * the plot sold, the purchase verified, the final letter — in this same
     * transaction, so a refusal there undoes the verification too.
     */
    private FinanceDecisionDto verifyPlanPayment(PlanPayment pp, UUID tenantId, UUID actor) {
        refuseOwnPurchase(pp.row().buyerUserId(), actor);
        Payment payment = pp.payment();
        BigDecimal expected = expectedAmount(payment, pp.schedule(), pp.progress());
        if (expected == null || payment.getAmount().compareTo(expected) != 0 || payment.getCurrency() != pp.row().currency()) {
            throw new PaymentException.PaymentNotConfirmed();
        }
        markVerified(payment, actor);
        if (pp.row().planWithdrawn()) {
            // 5a: the buyer already left the plan and has their part back; verifying
            // only makes the developer's share of this payment payable (decided with the user).
            auditApi.record(AuditEntryRequest.of(actor, "payment.instalment_verified", "payment", payment.getId(),
                    tenantId, describe(payment, pp.schedule()) + " for " + pp.row().transactionReference()
                            + " verified by finance after the buyer withdrew; the developer's share is now payable."));
            return FinanceDecisionDto.of(payment.getTransactionId(), "withdrawn");
        }
        PlanProgress after = PlanProgress.of(pp.schedule(),
                payments.findAllByTransactionIdOrderByCreatedAtAsc(payment.getTransactionId()));
        auditApi.record(AuditEntryRequest.of(actor, "payment.instalment_verified", "payment", payment.getId(), tenantId,
                describe(payment, pp.schedule()) + " for " + pp.row().transactionReference() + " verified by finance ("
                        + after.instalmentsPaid() + " of " + pp.schedule().instalments().size() + " paid)."));
        if (!after.complete()) {
            log.info("{} for transaction {} verified by finance user {}", describe(payment, pp.schedule()),
                    payment.getTransactionId(), actor);
            identityApi.emailOf(pp.row().buyerUserId()).ifPresent(to -> buyerNotifier.afterCommit("instalment verified",
                    () -> alerts.instalmentVerified(new SettlementAlertSender.InstalmentAlert(to, pp.row().plotLabel(),
                            pp.row().estateName(), payment.getAmount().toPlainString(), after.instalmentsPaid(),
                            pp.schedule().instalments().size(),
                            after.next().map(i -> DATE.format(i.dueDate())).orElse(null),
                            after.next().map(i -> i.amount().toPlainString()).orElse(null),
                            buyerNotifier.purchaseUrl(payment.getTransactionId())))));
            return FinanceDecisionDto.of(payment.getTransactionId(), "on_plan");
        }
        FinanceDecision decision = checkoutApi.completePlan(payment.getTransactionId(), tenantId, actor);
        if (decision != FinanceDecision.DONE) {
            throw failure(decision);
        }
        log.info("Plan for transaction {} paid in full; verified by finance user {}", payment.getTransactionId(), actor);
        Optional<AllocationLetterLookup.LetterRef> letter = letterLookup.letterFor(payment.getTransactionId());
        tellBuyer(pp.row(), payment.getAmount(), true, null, letter, false);
        return FinanceDecisionDto.verified(payment.getTransactionId(), letter);
    }

    /** A plan payment finance won't accept: refunded on its own; the plan carries on (the instalment stays owed). */
    private FinanceDecisionDto rejectPlanPayment(PlanPayment pp, UUID tenantId, UUID actor, String reason) {
        refuseOwnPurchase(pp.row().buyerUserId(), actor);
        Payment payment = pp.payment();
        payment.setReviewReason("Refund due: finance rejected this payment — " + reason);
        payment.setRefundRequestedAt(Instant.now());
        if (pp.row().planWithdrawn()) {
            // 5a: the buyer's part is already owed back; now the developer's share is too.
            payment.setRefundAmount(null);
            payment.setRefundReason("The developer's finance rejected this payment after you withdrew — "
                    + "returned in full.");
        }
        payments.save(payment);
        auditApi.record(AuditEntryRequest.of(actor, "payment.refund_due", "payment", payment.getId(), tenantId,
                describe(payment, pp.schedule()) + " " + payment.getReference()
                        + (pp.row().planWithdrawn() ? " rejected by finance after the buyer withdrew; flagged for a "
                        + "full refund." : " rejected by finance and flagged for refund; the plan continues.")));
        tellBuyer(pp.row(), payment.getAmount(), false, reason, Optional.empty(), false);
        return FinanceDecisionDto.of(payment.getTransactionId(), pp.row().planWithdrawn() ? "withdrawn" : "on_plan");
    }

    /**
     * What a plan payment must be: its instalment from the schedule, or
     * everything still owed, plus the penalty it was charged when it started
     * (phase 4: fixed then, never recomputed here).
     */
    private static BigDecimal expectedAmount(Payment payment, PlanSchedule schedule, PlanProgress progress) {
        BigDecimal owed = payment.getPurpose() == PaymentPurpose.BALANCE ? progress.balance()
                : schedule.instalments().stream().filter(i -> i.sequence() == payment.getInstalmentSequence())
                .map(PlanSchedule.Instalment::amount).findFirst().orElse(null);
        return owed == null ? null : owed.add(payment.getPenaltyAmount());
    }

    private static String describe(Payment payment, PlanSchedule schedule) {
        return payment.getPurpose() == PaymentPurpose.BALANCE ? "Plan balance"
                : "Instalment " + payment.getInstalmentSequence() + " of " + schedule.instalments().size();
    }

    private FinanceQueueItemDto planItem(FinanceQueueRow row, Payment p, PlanSchedule schedule) {
        PlanProgress progress = PlanProgress.of(schedule, payments.findAllByTransactionIdOrderByCreatedAtAsc(
                row.transactionId()));
        BigDecimal expected = expectedAmount(p, schedule, progress);
        Optional<PlanSchedule.Instalment> instalment = schedule.instalments().stream()
                .filter(i -> Objects.equals(i.sequence(), p.getInstalmentSequence())).findFirst();
        return new FinanceQueueItemDto(row.transactionId(), row.transactionReference(), row.estateId(),
                row.estateName(), row.plotId(), row.plotLabel(), identityApi.emailOf(row.buyerUserId()).orElse(null),
                expected, row.currency().name(), p.getPaidAt(), gateway(p),
                expected != null && p.getAmount().compareTo(expected) == 0 && p.getCurrency() == row.currency(),
                row.letterApproved(), p.getPurpose() == PaymentPurpose.BALANCE ? "balance" : "instalment",
                row.planName(), p.getInstalmentSequence(), schedule.instalments().size(),
                instalment.map(PlanSchedule.Instalment::dueDate).orElse(null), p.getPenaltyAmount(),
                penalties.findByPaymentIdIn(List.of(p.getId())).stream()
                        .sorted(Comparator.comparing(PaymentPenalty::getInstalmentSequence))
                        .map(l -> new FinanceQueueItemDto.PenaltyLine(l.getInstalmentSequence(), l.getMonthsLate(),
                                l.getPenaltyPct(), l.getAmount()))
                        .toList(), row.planWithdrawn());
    }

    private void markVerified(Payment payment, UUID actor) {
        payment.setFinanceVerifiedAt(Instant.now());
        payment.setFinanceVerifiedBy(actor);
        payments.saveAndFlush(payment);
    }

    private static FinanceQueueItemDto.GatewayRecord gateway(Payment p) {
        return new FinanceQueueItemDto.GatewayRecord(p.getReference(), p.getAmount(), p.getCurrency().name(),
                p.getChannel(), p.getPaidAt(), p.getLast4(), p.getGatewayResponse());
    }

    private Optional<Payment> confirmedPaymentFor(UUID transactionId) {
        return payments.findFirstByTransactionIdAndStatusOrderByCreatedAtDesc(transactionId, PaymentStatus.SUCCEEDED);
    }

    private static boolean matches(Payment payment, FinanceQueueRow row) {
        return payment.getAmount().compareTo(row.amountDue()) == 0 && payment.getCurrency() == row.currency();
    }

    /**
     * Separation of duties: a staff member who also bought (a buyer role left
     * on a staff account) may never verify, reject, allocate or refund their
     * own purchase. Real staff accounts come from invitations and hold no
     * buyer role; this is the safety net for those that slipped through.
     */
    static void refuseOwnPurchase(UUID buyerUserId, UUID actor) {
        if (buyerUserId != null && buyerUserId.equals(actor)) {
            throw new PaymentException.OwnPurchase();
        }
    }

    static RuntimeException failure(FinanceDecision decision) {
        return switch (decision) {
            case NOT_FOUND -> new PaymentException.TransactionNotFound();
            case NOT_AWAITING_FINANCE -> new PaymentException.NotAwaitingFinance();
            case PLOT_NOT_RESERVED -> new PaymentException.PlotNotReserved();
            case NOT_ABANDONED -> new PaymentException.LatePaymentNotFound();
            case PLOT_NOT_AVAILABLE -> new PaymentException.PlotNoLongerAvailable();
            case LETTER_NOT_APPROVED -> new PaymentException.LetterNotApproved();
            case LATE_DEPOSIT_NOT_SUPPORTED -> new PaymentException.LateDepositNotSupported();
            case NOT_ON_PLAN -> new PaymentException.NotAwaitingFinance();
            case PLOT_NOT_ON_PLAN -> new PaymentException.PlotNotReserved();
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
