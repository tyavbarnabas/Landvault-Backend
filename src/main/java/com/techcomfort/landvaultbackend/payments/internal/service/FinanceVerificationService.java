package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.FinanceDecision;
import com.techcomfort.landvaultbackend.checkout.FinanceQueueRow;
import com.techcomfort.landvaultbackend.checkout.FlexiblePurchase;
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
    private final RevocationNotices revocationNotices;
    private final RestructureNotices restructureNotices;
    private final GainShareService gainShares;

    private static final List<PaymentPurpose> PLAN_PAYMENTS = List.of(PaymentPurpose.INSTALMENT, PaymentPurpose.BALANCE,
            PaymentPurpose.CONTRIBUTION);
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
        Map<UUID, FlexiblePurchase> flexible = checkoutApi.flexiblePlansOf(rows.keySet());
        planPayments.stream().filter(p -> rows.containsKey(p.getTransactionId()))
                .map(p -> flexible.containsKey(p.getTransactionId())
                        ? contributionItem(rows.get(p.getTransactionId()), p, flexible.get(p.getTransactionId()))
                        : planItem(rows.get(p.getTransactionId()), p, schedules.get(p.getTransactionId())))
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
            Optional<Contribution> contribution = contributionAwaiting(transactionId, tenantId);
            if (contribution.isPresent()) {
                return verifyContribution(contribution.get(), tenantId, scope.userId());
            }
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
        // Flexible 3b: a first payment of the whole plan total finishes it at once — the
        // provisional letter, then the final one, in this same transaction.
        boolean finished = row.isFlexible() && payment.get().getAmount().compareTo(row.flexiblePlanTotal()) == 0;
        if (finished) {
            FinanceDecision completed = checkoutApi.completePlan(transactionId, tenantId, scope.userId());
            if (completed != FinanceDecision.DONE) {
                throw failure(completed);
            }
        }
        boolean onPlan = row.isDeposit() && !finished;
        if (!onPlan) {
            // Phase 5: a completed sale settles earlier buyers' gain claims on the plot.
            gainShares.settleOn(transactionId, scope.userId());
        }
        log.info("Transaction {} verified by finance user {}; plot allocated", transactionId, scope.userId());
        Optional<AllocationLetterLookup.LetterRef> letter = letterLookup.letterFor(transactionId);
        tellBuyer(row, payment.get().getAmount(), true, null, letter, onPlan, finished ? row.flexiblePlanTotal() : null);
        return onPlan ? FinanceDecisionDto.depositVerified(transactionId, letter)
                : FinanceDecisionDto.verified(transactionId, letter);
    }

    /** The plot goes back on sale; the money is flagged for refund (decided with the user — refunds are manual for now). */
    @Transactional
    public FinanceDecisionDto reject(UUID transactionId, String reason) {
        TenantScope scope = currentScope();
        UUID tenantId = companyOf(scope);
        payments.findAllByTransactionIdIn(List.of(transactionId)).stream().findFirst()
                .ifPresent(p -> refuseOwnPurchase(p.getBuyerUserId(), scope.userId()));
        Optional<Contribution> contribution = contributionAwaiting(transactionId, tenantId);
        if (contribution.isPresent()) {
            return rejectContribution(contribution.get(), tenantId, scope.userId(), reason.trim());
        }
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
            tellBuyer(row.get(), returned, false, reason.trim(), Optional.empty(), false, null);
        }
        return FinanceDecisionDto.of(transactionId, "rejected");
    }

    /**
     * Sent after this decision commits; a failed email leaves the decision standing.
     * {@code paidInFull}: the plan total when this payment completed a plan, else null.
     */
    private void tellBuyer(FinanceQueueRow row, BigDecimal amount, boolean verified, String reason,
                           Optional<AllocationLetterLookup.LetterRef> letter, boolean deposit, BigDecimal paidInFull) {
        identityApi.emailOf(row.buyerUserId()).ifPresent(to -> buyerNotifier.afterCommit(
                verified ? "purchase verified" : "purchase rejected",
                () -> alerts.purchaseDecided(new SettlementAlertSender.PurchaseDecisionAlert(to, row.plotLabel(),
                        row.estateName(), amount.toPlainString(), verified, reason,
                        buyerNotifier.purchaseUrl(row.transactionId()),
                        letter.map(AllocationLetterLookup.LetterRef::number).orElse(null),
                        letter.map(l -> buyerNotifier.letterUrl(l.id())).orElse(null),
                        verified && deposit ? row.planName() : null, verified && deposit && row.isFlexible(),
                        paidInFull == null ? null : paidInFull.toPlainString()))));
    }

    private FinanceQueueItemDto toItem(FinanceQueueRow row) {
        Optional<Payment> payment = confirmedPaymentFor(row.transactionId());
        FinanceQueueItemDto.GatewayRecord record = payment.map(FinanceVerificationService::gateway).orElse(null);
        boolean matches = payment.isPresent() && matches(payment.get(), row);
        // Flexible: the buyer chose the amount, so within the plan's limits what was paid is what's expected.
        BigDecimal expected = row.isFlexible() && matches ? payment.get().getAmount() : row.amountDue();
        return new FinanceQueueItemDto(row.transactionId(), row.transactionReference(), row.estateId(),
                row.estateName(), row.plotId(), row.plotLabel(), identityApi.emailOf(row.buyerUserId()).orElse(null),
                expected, row.currency().name(), row.awaitingSince(), record, matches, row.letterApproved(),
                row.isFlexible() ? "contribution" : row.isDeposit() ? "deposit" : "full_price", row.planName(),
                null, null, null, null, BigDecimal.ZERO, List.of(), false, false,
                row.isFlexible() && matches ? row.flexiblePlanTotal().subtract(payment.get().getAmount()) : null,
                row.isFlexible() ? 1 : null, row.resale() == null ? null
                : new FinanceQueueItemDto.Resale(row.resale().developerReceives(), row.resale().sellerReceives()));
    }

    // ------------------------------------------------------------------ 3b: instalments

    /** A plan payment awaiting finance, with its purchase (as this caller sees it) and schedule. */
    private record PlanPayment(Payment payment, FinanceQueueRow row, PlanSchedule schedule, PlanProgress progress) {
    }

    private Optional<PlanPayment> planPaymentAwaiting(UUID transactionId, UUID tenantId) {
        Optional<FinanceQueueRow> row = checkoutApi.onPlan(tenantId, List.of(transactionId)).stream().findFirst();
        if (row.isEmpty() || row.get().isFlexible()) {
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
        if (pp.row().planEnded()) {
            // 5a/5b: the plan already ended (withdrawn or revoked) and the buyer's part is
            // owed back; verifying only makes the developer's share of this payment payable.
            auditApi.record(AuditEntryRequest.of(actor, "payment.instalment_verified", "payment", payment.getId(),
                    tenantId, describe(payment, pp.schedule()) + " for " + pp.row().transactionReference()
                            + " verified by finance after the plan ended (" + pp.row().planStatus()
                            + "); the developer's share is now payable."));
            return FinanceDecisionDto.of(payment.getTransactionId(), pp.row().planStatus());
        }
        PlanProgress after = PlanProgress.of(pp.schedule(),
                payments.findAllByTransactionIdOrderByCreatedAtAsc(payment.getTransactionId()));
        // 5b: a notice of revocation ends once the buyer has caught up.
        revocationNotices.clearIfCaughtUp(pp.row(), after);
        // 5c: a proposal was worked out on what was owed before this payment.
        restructureNotices.lapseProposalOnPayment(payment.getTransactionId());
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
        gainShares.settleOn(payment.getTransactionId(), actor);
        log.info("Plan for transaction {} paid in full; verified by finance user {}", payment.getTransactionId(), actor);
        Optional<AllocationLetterLookup.LetterRef> letter = letterLookup.letterFor(payment.getTransactionId());
        tellBuyer(pp.row(), payment.getAmount(), true, null, letter, false, pp.schedule().planTotal());
        return FinanceDecisionDto.verified(payment.getTransactionId(), letter);
    }

    /** A plan payment finance won't accept: refunded on its own; the plan carries on (the instalment stays owed). */
    private FinanceDecisionDto rejectPlanPayment(PlanPayment pp, UUID tenantId, UUID actor, String reason) {
        refuseOwnPurchase(pp.row().buyerUserId(), actor);
        Payment payment = pp.payment();
        payment.setReviewReason("Refund due: finance rejected this payment — " + reason);
        payment.setRefundRequestedAt(Instant.now());
        if (pp.row().planEnded()) {
            // 5a/5b: the buyer's part is already owed back; now the developer's share is too.
            payment.setRefundAmount(null);
            payment.setRefundReason("The developer's finance rejected this payment after your plan ended — "
                    + "returned in full.");
        }
        payments.save(payment);
        auditApi.record(AuditEntryRequest.of(actor, "payment.refund_due", "payment", payment.getId(), tenantId,
                describe(payment, pp.schedule()) + " " + payment.getReference()
                        + (pp.row().planEnded() ? " rejected by finance after the plan ended; flagged for a "
                        + "full refund." : " rejected by finance and flagged for refund; the plan continues.")));
        tellBuyer(pp.row(), payment.getAmount(), false, reason, Optional.empty(), false, null);
        return FinanceDecisionDto.of(payment.getTransactionId(), pp.row().planStatus());
    }

    /**
     * What a plan payment must be: its instalment from the schedule, or
     * everything still owed, plus the penalty it was charged when it started
     * (phase 4: fixed then, never recomputed here).
     */
    // ------------------------------------------------------------------ flexible contribution (3b)

    /** A later contribution awaiting finance, with its purchase (as this caller sees it) and where the plan stands. */
    private record Contribution(Payment payment, FinanceQueueRow row, FlexibleProgress progress) {
    }

    private Optional<Contribution> contributionAwaiting(UUID transactionId, UUID tenantId) {
        Optional<FinanceQueueRow> row = checkoutApi.onPlan(tenantId, List.of(transactionId)).stream()
                .filter(FinanceQueueRow::isFlexible).findFirst();
        FlexiblePurchase plan = checkoutApi.flexiblePlansOf(List.of(transactionId)).get(transactionId);
        if (row.isEmpty() || plan == null) {
            return Optional.empty();
        }
        FlexibleProgress progress = FlexibleProgress.of(plan, payments.findAllByTransactionIdOrderByCreatedAtAsc(
                transactionId));
        return progress.awaitingFinance().map(p -> new Contribution(p, row.get(), progress));
    }

    /**
     * Decision 1: finance verifies every contribution, against the plan's
     * limits as they stand now — no more than what's left, and at least the
     * minimum contribution unless it finishes the plan. The payment that
     * reaches the plan total completes it — the plot sold, the purchase
     * verified, the final letter — in this same transaction.
     */
    private FinanceDecisionDto verifyContribution(Contribution c, UUID tenantId, UUID actor) {
        refuseOwnPurchase(c.row().buyerUserId(), actor);
        Payment payment = c.payment();
        if (!contributionMatches(payment, c.row(), c.progress())) {
            throw new PaymentException.PaymentNotConfirmed();
        }
        markVerified(payment, actor);
        if (c.row().planEnded()) {
            // Phase 4: the buyer left (or the plan was ended) while this waited; the buyer's part is already
            // owed back, and verifying only makes the developer's share of it payable.
            auditApi.record(AuditEntryRequest.of(actor, "payment.contribution_verified", "payment", payment.getId(),
                    tenantId, "Contribution " + payment.getReference() + " for " + c.row().transactionReference()
                            + " verified by finance after the plan ended (" + c.row().planStatus()
                            + "); the developer's share is now payable."));
            return FinanceDecisionDto.of(payment.getTransactionId(), c.row().planStatus());
        }
        FlexibleProgress after = FlexibleProgress.of(c.progress().plan(),
                payments.findAllByTransactionIdOrderByCreatedAtAsc(payment.getTransactionId()));
        // Phase 4, decision 3: a verified payment ends an inactivity notice; finishing ends any notice.
        revocationNotices.clearOnContribution(payment.getTransactionId(), c.row().transactionReference(),
                c.row().planName(), c.row().plotLabel() + ", " + c.row().estateName(), after.complete());
        auditApi.record(AuditEntryRequest.of(actor, "payment.contribution_verified", "payment", payment.getId(),
                tenantId, "Contribution " + payment.getReference() + " of ₦" + payment.getAmount().toPlainString()
                        + " for " + c.row().transactionReference() + " verified by finance (₦"
                        + after.verified().toPlainString() + " paid, ₦" + after.remaining().toPlainString()
                        + " left)."));
        if (!after.complete()) {
            identityApi.emailOf(c.row().buyerUserId()).ifPresent(to -> buyerNotifier.afterCommit(
                    "contribution verified", () -> alerts.contributionVerified(new SettlementAlertSender.ContributionAlert(
                            to, c.row().plotLabel(), c.row().estateName(), payment.getAmount().toPlainString(),
                            after.verified().toPlainString(), after.remaining().toPlainString(),
                            buyerNotifier.purchaseUrl(payment.getTransactionId())))));
            return FinanceDecisionDto.of(payment.getTransactionId(), "on_plan");
        }
        FinanceDecision decision = checkoutApi.completePlan(payment.getTransactionId(), tenantId, actor);
        if (decision != FinanceDecision.DONE) {
            throw failure(decision);
        }
        gainShares.settleOn(payment.getTransactionId(), actor);
        log.info("Flexible plan for transaction {} paid in full; verified by finance user {}",
                payment.getTransactionId(), actor);
        Optional<AllocationLetterLookup.LetterRef> letter = letterLookup.letterFor(payment.getTransactionId());
        tellBuyer(c.row(), payment.getAmount(), true, null, letter, false, c.progress().plan().planTotal());
        return FinanceDecisionDto.verified(payment.getTransactionId(), letter);
    }

    /** A contribution finance won't accept: refunded on its own; the plan carries on. */
    private FinanceDecisionDto rejectContribution(Contribution c, UUID tenantId, UUID actor, String reason) {
        refuseOwnPurchase(c.row().buyerUserId(), actor);
        Payment payment = c.payment();
        payment.setReviewReason("Refund due: finance rejected this payment — " + reason);
        payment.setRefundRequestedAt(Instant.now());
        if (c.row().planEnded()) {
            // Phase 4: the buyer's part is already owed back; now the developer's share is too.
            payment.setRefundAmount(null);
            payment.setRefundReason("The developer's finance rejected this payment after your plan ended — "
                    + "returned in full.");
        }
        payments.save(payment);
        auditApi.record(AuditEntryRequest.of(actor, "payment.refund_due", "payment", payment.getId(), tenantId,
                "Contribution " + payment.getReference() + " rejected by finance and flagged for refund; the plan "
                        + "continues."));
        tellBuyer(c.row(), payment.getAmount(), false, reason, Optional.empty(), false, null);
        return FinanceDecisionDto.of(payment.getTransactionId(), c.row().planStatus());
    }

    private static boolean contributionMatches(Payment payment, FinanceQueueRow row, FlexibleProgress progress) {
        return payment.getPurpose() == PaymentPurpose.CONTRIBUTION && payment.getCurrency() == row.currency()
                && progress.allows(payment.getAmount());
    }

    private FinanceQueueItemDto contributionItem(FinanceQueueRow row, Payment p, FlexiblePurchase plan) {
        FlexibleProgress progress = FlexibleProgress.of(plan, payments.findAllByTransactionIdOrderByCreatedAtAsc(
                row.transactionId()));
        boolean matches = contributionMatches(p, row, progress);
        return new FinanceQueueItemDto(row.transactionId(), row.transactionReference(), row.estateId(),
                row.estateName(), row.plotId(), row.plotLabel(), identityApi.emailOf(row.buyerUserId()).orElse(null),
                matches ? p.getAmount() : null, row.currency().name(), p.getPaidAt(), gateway(p), matches,
                row.letterApproved(), "contribution", row.planName(), null, null, null, null, BigDecimal.ZERO,
                List.of(), row.planWithdrawn(), row.planRevoked(),
                matches ? progress.remaining().subtract(p.getAmount()) : null, progress.verifiedCount() + 1, null);
    }

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
                instalment.map(PlanSchedule.Instalment::dueDate).orElse(null),
                instalment.map(PlanSchedule.Instalment::amount).orElse(null), p.getPenaltyAmount(),
                penalties.findByPaymentIdIn(List.of(p.getId())).stream()
                        .sorted(Comparator.comparing(PaymentPenalty::getInstalmentSequence))
                        .map(l -> new FinanceQueueItemDto.PenaltyLine(l.getInstalmentSequence(), l.getMonthsLate(),
                                l.getPenaltyPct(), l.getAmount()))
                        .toList(), row.planWithdrawn(), row.planRevoked(), null, null, null);
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

    /**
     * The confirmed payment is what this purchase was waiting for: exactly the
     * amount due, or — the buyer's own figure on a flexible plan — between the
     * minimum to start and the plan total. The human check backs the gateway
     * and the limits the payment was started under.
     */
    private static boolean matches(Payment payment, FinanceQueueRow row) {
        if (payment.getCurrency() != row.currency()) {
            return false;
        }
        return row.isFlexible()
                ? payment.getPurpose() == PaymentPurpose.CONTRIBUTION
                && payment.getAmount().compareTo(row.amountDue()) >= 0
                && payment.getAmount().compareTo(row.flexiblePlanTotal()) <= 0
                : payment.getAmount().compareTo(row.amountDue()) == 0;
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
            case RESALE_NOT_YET -> new PaymentException.ResaleVerificationNotYet();
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
