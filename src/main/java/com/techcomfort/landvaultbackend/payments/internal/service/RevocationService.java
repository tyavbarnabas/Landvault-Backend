package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.FinanceQueueRow;
import com.techcomfort.landvaultbackend.checkout.PayableTransaction;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.checkout.PlanStanding;
import com.techcomfort.landvaultbackend.common.RevocationTerms;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.dto.RevocationDto;
import com.techcomfort.landvaultbackend.payments.dto.WithdrawalDto;
import com.techcomfort.landvaultbackend.payments.internal.domain.PlanRevocation;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.enums.RevocationStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PlanRevocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 5b: a developer revokes an instalment plan the buyer has stopped
 * paying (decided with the user, 2026-10-23). The Executive Director gives
 * notice once an instalment is unpaid as long as the purchase's terms say;
 * the buyer has the notice period to pay what is overdue; after the deadline,
 * if they still haven't, the director confirms. The plan ends exactly as a
 * withdrawal does: plot back on sale, the buyer's part of every payment owed
 * back, the developer's shares theirs. Nothing here is automatic. See
 * AGENTS.md, "Instalment plans", phase 5b.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RevocationService {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

    private final CheckoutApi checkoutApi;
    private final PlanStandings planStandings;
    private final PlanRevocationRepository revocations;
    private final PaymentRepository payments;
    private final WithdrawalService withdrawals;
    private final RevocationNotices notices;
    private final RestructureNotices restructureNotices;
    private final AuditApi auditApi;
    private final IdentityApi identityApi;
    private final SettlementAlertSender alerts;
    private final BuyerNotifier buyerNotifier;
    /** Phase 4 of flexible contribution: a quiet flexible plan, through the same routes. */
    private final FlexibleNoticeService flexibleNotices;

    @Transactional(readOnly = true)
    public RevocationDto get(UUID transactionId) {
        TenantScope scope = companyWide();
        if (flexibleNotices.handles(transactionId, scope)) {
            return flexibleNotices.get(transactionId, scope);
        }
        return view(plan(transactionId, scope), PlanStandings.today());
    }

    @Transactional
    public RevocationDto start(UUID transactionId, String reason) {
        TenantScope scope = companyWide();
        if (flexibleNotices.handles(transactionId, scope)) {
            return flexibleNotices.start(transactionId, scope, reason);
        }
        Plan plan = plan(transactionId, scope);
        PayableTransaction purchase = requireRunning(plan);
        RevocationTerms terms = requireTerms(plan);
        // The payments locked first — the order finance verifying one takes —
        // so the standing can't change while notice is given.
        payments.findLockedByTransactionIdAndStatus(transactionId, PaymentStatus.SUCCEEDED);
        Optional<PlanRevocation> open = revocations.findOpenLocked(transactionId);
        if (open.isPresent()) {
            throw new PaymentException.RevocationAlreadyNotified(open.get().getDeadline(), false);
        }
        LocalDate today = PlanStandings.today();
        PlanProgress progress = progress(plan);
        PlanProgress.InstalmentStanding trigger = progress.revocationTrigger(today)
                .orElseThrow(() -> new PaymentException.RevocationNotDue(notDue(progress, terms, today)));
        PlanRevocation notice = revocations.saveAndFlush(PlanRevocation.builder()
                .transactionId(transactionId)
                .sellerTenantId(scope.tenantId())
                .buyerUserId(purchase.buyerUserId())
                .status(RevocationStatus.NOTICE)
                .reason(reason.trim())
                .revocationAfterMonths(terms.afterMonths())
                .noticeDays(terms.noticeDays())
                .instalmentSequence(trigger.instalment().sequence())
                .instalmentDueDate(trigger.instalment().dueDate())
                .noticeGivenAt(Instant.now())
                .deadline(terms.deadline(today))
                .startedBy(scope.userId())
                .build());
        auditApi.record(AuditEntryRequest.of(scope.userId(), "plan.revocation_notice_given", "plan_revocation",
                notice.getId(), scope.tenantId(), "Notice of revocation given on " + purchase.reference()
                        + ": instalment " + trigger.instalment().sequence() + " (due " + trigger.instalment().dueDate()
                        + ") unpaid " + terms.afterMonths() + "+ months; the buyer has until " + notice.getDeadline()
                        + " to pay what is overdue. Reason: " + notice.getReason()));
        log.info("Revocation notice {} given on plan {} by {}", notice.getId(), transactionId, scope.userId());
        WithdrawalService.Quote ifRevoked = withdrawals.preview(purchase);
        PlanStanding.Arrears arrears = PlanStandings.standing(progress, today).arrears();
        tell(plan, SettlementAlertSender.RevocationStage.NOTICE, notice, notice.getReason(),
                arrears == null ? "0.00" : arrears.totalDue().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                ifRevoked.refund(),
                ifRevoked.developerKeeps());
        return view(plan, today);
    }

    @Transactional
    public RevocationDto cancel(UUID transactionId, String note) {
        TenantScope scope = companyWide();
        if (flexibleNotices.handles(transactionId, scope)) {
            return flexibleNotices.cancel(transactionId, scope, note);
        }
        Plan plan = plan(transactionId, scope);
        PlanRevocation notice = revocations.findOpenLocked(transactionId)
                .orElseThrow(PaymentException.NoOpenRevocation::new);
        String why = note == null || note.isBlank() ? null : note.trim();
        notices.close(notice, RevocationStatus.CANCELLED, scope.userId(), why);
        auditApi.record(AuditEntryRequest.of(scope.userId(), "plan.revocation_cancelled", "plan_revocation",
                notice.getId(), scope.tenantId(), "Notice of revocation on " + plan.purchase().reference()
                        + " withdrawn by the developer." + (why == null ? "" : " Note: " + why)));
        tell(plan, SettlementAlertSender.RevocationStage.CANCELLED, notice, why, null, null, null);
        return view(plan, PlanStandings.today());
    }

    @Transactional
    public RevocationDto confirm(UUID transactionId) {
        TenantScope scope = companyWide();
        if (flexibleNotices.handles(transactionId, scope)) {
            return flexibleNotices.confirm(transactionId, scope);
        }
        Plan plan = plan(transactionId, scope);
        PayableTransaction purchase = requireRunning(plan);
        RevocationTerms terms = requireTerms(plan);
        // Locks the payments first (as a withdrawal does), then the notice.
        WithdrawalService.Quote quote = withdrawals.quoteToEnd(purchase);
        PlanRevocation notice = revocations.findOpenLocked(transactionId)
                .orElseThrow(PaymentException.NoOpenRevocation::new);
        LocalDate today = PlanStandings.today();
        if (!today.isAfter(notice.getDeadline())) {
            throw new PaymentException.RevocationNoticeRunning(notice.getDeadline());
        }
        // Still behind? A payment waiting for finance counts as paid: verify or reject it first.
        PlanProgress progress = progress(plan);
        if (progress.revocationTrigger(today).isEmpty()) {
            throw new PaymentException.RevocationNotDue(progress.awaitingFinance().isPresent()
                    ? "The buyer has paid, and the payment is waiting for your finance team to verify it. Verify "
                    + "or reject it first; nothing is overdue long enough to revoke while it waits."
                    : "The buyer has caught up: nothing is unpaid " + terms.afterMonths() + " months past its due "
                    + "date any more. Withdraw the notice instead.");
        }
        if (!checkoutApi.revoke(transactionId, scope.tenantId(), scope.userId(), notice.getReason())) {
            throw new PaymentException.PlanNotRevocable(purchase.status());
        }
        Instant now = Instant.now();
        withdrawals.flagRefunds(purchase, quote, scope.userId(), now, "Your plan was revoked",
                "the developer revoked the plan");
        notices.close(notice, RevocationStatus.REVOKED, scope.userId(), null);
        restructureNotices.closeOnPlanEnded(transactionId, "the developer revoked it");
        log.info("Plan {} revoked by {}: refund {}, developer keeps {}", transactionId, scope.userId(),
                quote.refund(), quote.developerKeeps());
        tell(plan, SettlementAlertSender.RevocationStage.REVOKED, notice, null, null, quote.refund(),
                quote.developerKeeps());
        return view(plan(transactionId, scope), today);
    }

    // --- reading ---

    /** The purchase, its finance row (labels, under the caller's RLS) and its schedule. */
    private record Plan(PayableTransaction purchase, FinanceQueueRow row, PlanSchedule schedule) {
    }

    private Plan plan(UUID transactionId, TenantScope scope) {
        PayableTransaction purchase = checkoutApi.planForSeller(transactionId, scope.tenantId())
                .orElseThrow(PaymentException.TransactionNotFound::new);
        FinanceQueueRow row = checkoutApi.onPlan(scope.tenantId(), List.of(transactionId)).stream().findFirst()
                .orElse(null);
        PlanSchedule schedule = checkoutApi.schedulesOf(List.of(transactionId)).get(transactionId);
        if (schedule == null) {
            throw new PaymentException.PlanNotRevocable(purchase.status());
        }
        return new Plan(purchase, row, schedule);
    }

    private PlanProgress progress(Plan plan) {
        return planStandings.progressOf(Map.of(plan.purchase().id(), plan.schedule())).get(plan.purchase().id());
    }

    private RevocationDto view(Plan plan, LocalDate today) {
        PayableTransaction purchase = plan.purchase();
        RevocationTerms terms = plan.schedule().lateTerms().revocation();
        List<PlanRevocation> all = revocations.findByTransactionIdOrderByCreatedAtDesc(purchase.id());
        boolean running = purchase.isOnPlan();
        PlanProgress progress = progress(plan);
        PlanStanding.Arrears arrears = running ? PlanStandings.standing(progress, today).arrears() : null;
        RevocationDto.Settlement settlement = null;
        if (running) {
            WithdrawalService.Quote q = withdrawals.preview(purchase);
            settlement = new RevocationDto.Settlement(q.paid(), q.refund(), q.developerKeeps(), q.currency());
        } else if (purchase.isRevoked() || purchase.isWithdrawn()) {
            WithdrawalDto settled = withdrawals.settledRecord(purchase);
            settlement = new RevocationDto.Settlement(settled.paid(), settled.refund(), settled.developerKeeps(),
                    settled.currency());
        }
        PlanRevocation open = all.stream().filter(PlanRevocation::isOpen).findFirst().orElse(null);
        return new RevocationDto(purchase.id(), purchase.reference(), "installment", purchase.status(),
                terms == null ? 0 : terms.afterMonths(), terms == null ? 0 : terms.noticeDays(),
                running && terms != null ? progress.oldestLate(today)
                        .map(s -> terms.revocableFrom(s.instalment().dueDate())).orElse(null) : null,
                running && open == null && progress.revocationTrigger(today).isPresent(),
                arrears == null ? BigDecimal.ZERO : arrears.totalDue(),
                open == null ? null : notice(open), settlement, all.stream().map(RevocationService::notice).toList(),
                null);
    }

    static RevocationDto.Notice notice(PlanRevocation r) {
        return new RevocationDto.Notice(r.getId(), r.getStatus().wire(), r.getReason(), r.getNoticeGivenAt(),
                r.getDeadline(), r.getDeadline().plusDays(1), r.getInstalmentSequence(), r.getTrigger().wire(),
                r.getClosedAt(), r.getCloseNote());
    }

    // --- rules ---

    private static PayableTransaction requireRunning(Plan plan) {
        if (!plan.purchase().isOnPlan()) {
            throw new PaymentException.PlanNotRevocable(plan.purchase().status());
        }
        return plan.purchase();
    }

    private static RevocationTerms requireTerms(Plan plan) {
        RevocationTerms terms = plan.schedule().lateTerms().revocation();
        if (terms == null) {
            throw new PaymentException.RevocationTermsMissing();
        }
        return terms;
    }

    private static String notDue(PlanProgress progress, RevocationTerms terms, LocalDate today) {
        String rule = "A plan can be revoked once an instalment is unpaid " + terms.afterMonths()
                + " months after its due date.";
        return progress.oldestLate(today)
                .map(s -> rule + " The oldest overdue instalment (" + s.instalment().sequence() + ", due "
                        + s.instalment().dueDate() + ") reaches that on " + terms.revocableFrom(s.instalment().dueDate())
                        + " if it is still unpaid.")
                .orElse(rule + " Nothing is overdue now (a payment waiting for your finance to verify counts as paid).");
    }

    /** Revocation is a company decision: never from a branch-narrowed view. */
    static TenantScope companyWide() {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        if (scope.tenantId() == null || scope.branchId() != null) {
            throw new PaymentException.RevocationRequiresCompanyWideScope();
        }
        return scope;
    }

    // --- telling the buyer ---

    private static String naira(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** After commit; a failed email never undoes the step. */
    private void tell(Plan plan, SettlementAlertSender.RevocationStage stage, PlanRevocation notice, String note,
                      String totalDue, BigDecimal refund, BigDecimal developerKeeps) {
        String plot = plan.row() == null ? "your plot" : plan.row().plotLabel() + ", " + plan.row().estateName();
        identityApi.emailOf(plan.purchase().buyerUserId()).ifPresent(to -> buyerNotifier.afterCommit(
                "revocation " + stage.name().toLowerCase(Locale.ROOT), () -> alerts.planRevocation(
                        new SettlementAlertSender.RevocationAlert(to, stage, plan.purchase().reference(),
                                plan.schedule().planName(), plot, notice.getRevocationAfterMonths(),
                                DATE.format(notice.getDeadline()), totalDue, naira(refund), naira(developerKeeps), note,
                                buyerNotifier.purchaseUrl(plan.purchase().id())))));
    }
}
