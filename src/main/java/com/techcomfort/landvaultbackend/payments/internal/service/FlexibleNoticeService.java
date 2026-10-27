package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.FinanceQueueRow;
import com.techcomfort.landvaultbackend.checkout.FlexiblePurchase;
import com.techcomfort.landvaultbackend.checkout.FlexibleStanding;
import com.techcomfort.landvaultbackend.checkout.PayableTransaction;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.dto.RevocationDto;
import com.techcomfort.landvaultbackend.payments.dto.WithdrawalDto;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.PlanRevocation;
import com.techcomfort.landvaultbackend.payments.internal.enums.NoticeTrigger;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.enums.RevocationStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PlanRevocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Flexible contribution, phase 4 (decided with the user, 2026-10-27): the
 * Executive Director gives notice on a quiet flexible plan — no payment
 * Paystack confirmed for the declared inactivity period, or past its date to
 * finish by — through the same routes and record as revocation (decision 1).
 * The buyer has the declared notice; a verified payment ends an inactivity
 * notice, only finishing ends a finish-by one. After the deadline, if the
 * reason still holds, the director ends the plan exactly as leaving would:
 * the same escrow refund (and, in phase 5, the same gain share). Nothing here
 * is automatic. Called by {@link RevocationService}, which owns the scope check.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlexibleNoticeService {

    private final CheckoutApi checkoutApi;
    private final PaymentRepository payments;
    private final PlanRevocationRepository revocations;
    private final WithdrawalService withdrawals;
    private final RevocationNotices notices;
    private final AuditApi auditApi;
    private final IdentityApi identityApi;
    private final SettlementAlertSender alerts;
    private final BuyerNotifier buyerNotifier;
    private final GainShareService gainShares;

    /** The purchase is this company's flexible plan (any status), visible to the caller. */
    boolean handles(UUID transactionId, TenantScope scope) {
        return checkoutApi.planForSeller(transactionId, scope.tenantId()).filter(PayableTransaction::isFlexible)
                .isPresent();
    }

    RevocationDto get(UUID transactionId, TenantScope scope) {
        return view(purchase(transactionId, scope), scope);
    }

    RevocationDto start(UUID transactionId, TenantScope scope, String reason) {
        PayableTransaction purchase = running(purchase(transactionId, scope));
        FlexiblePurchase plan = purchase.flexible();
        // The payments locked first — the order finance verifying one takes.
        payments.findLockedByTransactionIdAndStatus(transactionId, PaymentStatus.SUCCEEDED);
        Optional<PlanRevocation> open = revocations.findOpenLocked(transactionId);
        if (open.isPresent()) {
            throw new PaymentException.RevocationAlreadyNotified(open.get().getDeadline());
        }
        LocalDate today = PlanStandings.today();
        List<Payment> all = payments.findAllByTransactionIdOrderByCreatedAtAsc(transactionId);
        LocalDate last = FlexibleStandings.lastPaymentOn(all);
        NoticeTrigger trigger = FlexibleStandings.trigger(plan, FlexibleProgress.of(plan, all), last, today)
                .orElseThrow(() -> new PaymentException.RevocationNotDue(notDue(plan, last)));
        PlanRevocation notice = revocations.saveAndFlush(PlanRevocation.builder()
                .transactionId(transactionId)
                .sellerTenantId(scope.tenantId())
                .buyerUserId(purchase.buyerUserId())
                .status(RevocationStatus.NOTICE)
                .reason(reason.trim())
                .trigger(trigger)
                .revocationAfterMonths(plan.inactivityMonths())
                .noticeDays(plan.noticeDays())
                .noticeGivenAt(Instant.now())
                .deadline(today.plusDays(plan.noticeDays()))
                .startedBy(scope.userId())
                .build());
        auditApi.record(AuditEntryRequest.of(scope.userId(), "plan.revocation_notice_given", "plan_revocation",
                notice.getId(), scope.tenantId(), "Notice given on flexible plan " + purchase.reference() + " ("
                        + (trigger == NoticeTrigger.FINISH_BY ? "past its date to finish by, " + plan.finishBy()
                        : "no payment since " + last + ", " + plan.inactivityMonths() + "+ months") + "); the buyer has "
                        + "until " + notice.getDeadline() + ". Reason: " + notice.getReason()));
        log.info("Quiet-plan notice {} given on flexible plan {} by {}", notice.getId(), transactionId, scope.userId());
        WithdrawalService.Quote ifEnded = withdrawals.preview(purchase);
        tell(purchase, scope, SettlementAlertSender.RevocationStage.NOTICE, notice, notice.getReason(), all,
                ifEnded.refund(), ifEnded.developerKeeps());
        return view(purchase, scope);
    }

    RevocationDto cancel(UUID transactionId, TenantScope scope, String note) {
        PayableTransaction purchase = purchase(transactionId, scope);
        PlanRevocation notice = revocations.findOpenLocked(transactionId)
                .orElseThrow(PaymentException.NoOpenRevocation::new);
        String why = note == null || note.isBlank() ? null : note.trim();
        notices.close(notice, RevocationStatus.CANCELLED, scope.userId(), why);
        auditApi.record(AuditEntryRequest.of(scope.userId(), "plan.revocation_cancelled", "plan_revocation",
                notice.getId(), scope.tenantId(), "Notice on flexible plan " + purchase.reference()
                        + " withdrawn by the developer." + (why == null ? "" : " Note: " + why)));
        tell(purchase, scope, SettlementAlertSender.RevocationStage.CANCELLED, notice, why,
                payments.findAllByTransactionIdOrderByCreatedAtAsc(transactionId), null, null);
        return view(purchase, scope);
    }

    RevocationDto confirm(UUID transactionId, TenantScope scope) {
        PayableTransaction purchase = running(purchase(transactionId, scope));
        FlexiblePurchase plan = purchase.flexible();
        // Locks the payments first (as leaving does), then the notice.
        WithdrawalService.Quote quote = withdrawals.quoteToEnd(purchase);
        PlanRevocation notice = revocations.findOpenLocked(transactionId)
                .orElseThrow(PaymentException.NoOpenRevocation::new);
        LocalDate today = PlanStandings.today();
        if (!today.isAfter(notice.getDeadline())) {
            throw new PaymentException.RevocationNoticeRunning(notice.getDeadline());
        }
        List<Payment> all = payments.findAllByTransactionIdOrderByCreatedAtAsc(transactionId);
        FlexibleProgress progress = FlexibleProgress.of(plan, all);
        if (progress.awaitingFinance().isPresent()) {
            throw new PaymentException.RevocationNotDue("The buyer has paid, and the payment is waiting for your "
                    + "finance team to verify it. Verify or reject it first.");
        }
        if (FlexibleStandings.trigger(plan, progress, FlexibleStandings.lastPaymentOn(all), today).isEmpty()) {
            throw new PaymentException.RevocationNotDue("The plan isn't quiet any more: the buyer has paid since. "
                    + "Withdraw the notice instead.");
        }
        if (!checkoutApi.revoke(transactionId, scope.tenantId(), scope.userId(), notice.getReason())) {
            throw new PaymentException.PlanNotRevocable(purchase.status());
        }
        withdrawals.flagRefunds(purchase, quote, scope.userId(), Instant.now(), "Your plan was ended",
                "the developer ended the quiet plan");
        // Phase 5: ended on the terms for leaving — so the gain claim too.
        gainShares.open(purchase, scope.userId());
        notices.close(notice, RevocationStatus.REVOKED, scope.userId(), null);
        log.info("Quiet flexible plan {} ended by {}: refund {}, developer keeps {}", transactionId, scope.userId(),
                quote.refund(), quote.developerKeeps());
        tell(purchase, scope, SettlementAlertSender.RevocationStage.REVOKED, notice, null, all, quote.refund(),
                quote.developerKeeps());
        return view(purchase(transactionId, scope), scope);
    }

    // --- reading ---

    private PayableTransaction purchase(UUID transactionId, TenantScope scope) {
        return checkoutApi.planForSeller(transactionId, scope.tenantId()).filter(PayableTransaction::isFlexible)
                .orElseThrow(PaymentException.TransactionNotFound::new);
    }

    private static PayableTransaction running(PayableTransaction purchase) {
        if (!purchase.isOnPlan()) {
            throw new PaymentException.PlanNotRevocable(purchase.status());
        }
        return purchase;
    }

    private RevocationDto view(PayableTransaction purchase, TenantScope scope) {
        FlexiblePurchase plan = purchase.flexible();
        List<PlanRevocation> history = revocations.findByTransactionIdOrderByCreatedAtDesc(purchase.id());
        boolean running = purchase.isOnPlan();
        FlexibleStanding standing = null;
        RevocationDto.Settlement settlement = null;
        if (running) {
            standing = FlexibleStandings.standing(plan, payments.findAllByTransactionIdOrderByCreatedAtAsc(purchase.id()),
                    history.stream().filter(PlanRevocation::isOpen).findFirst().orElse(null), PlanStandings.today());
            WithdrawalService.Quote q = withdrawals.preview(purchase);
            settlement = new RevocationDto.Settlement(q.paid(), q.refund(), q.developerKeeps(), q.currency());
        } else if (purchase.isRevoked()) {
            WithdrawalDto settled = withdrawals.settledRecord(purchase);
            settlement = new RevocationDto.Settlement(settled.paid(), settled.refund(), settled.developerKeeps(),
                    settled.currency());
        }
        return new RevocationDto(purchase.id(), purchase.reference(), purchase.status(), plan.inactivityMonths(),
                plan.noticeDays(), standing == null ? null : noticeFrom(standing), standing != null
                && standing.noticeAllowedNow(), BigDecimal.ZERO,
                history.stream().filter(PlanRevocation::isOpen).findFirst().map(RevocationService::notice).orElse(null),
                settlement, history.stream().map(RevocationService::notice).toList(), standing);
    }

    /** The first day notice may be given: the day it counts as quiet, or the day after its date to finish by. */
    private static LocalDate noticeFrom(FlexibleStanding standing) {
        LocalDate afterFinish = standing.finishBy() == null ? null : standing.finishBy().plusDays(1);
        return afterFinish != null && afterFinish.isBefore(standing.quietFrom()) ? afterFinish : standing.quietFrom();
    }

    private static String notDue(FlexiblePurchase plan, LocalDate last) {
        return "A flexible plan can be given notice once no payment has come in for " + plan.inactivityMonths()
                + " months — this one from " + last.plusMonths(plan.inactivityMonths()) + ", its last payment being on "
                + last + (plan.finishBy() == null ? "" : " — or after its date to finish by, " + plan.finishBy())
                + ". A payment waiting for your finance to verify counts.";
    }

    // --- telling the buyer ---

    /** After commit; a failed email never undoes the step. */
    private void tell(PayableTransaction purchase, TenantScope scope, SettlementAlertSender.RevocationStage stage,
                      PlanRevocation notice, String note, List<Payment> all, BigDecimal refund,
                      BigDecimal developerKeeps) {
        FlexiblePurchase plan = purchase.flexible();
        Optional<FinanceQueueRow> row = checkoutApi.onPlan(scope.tenantId(), List.of(purchase.id())).stream()
                .findFirst();
        String plot = row.map(r -> r.plotLabel() + ", " + r.estateName()).orElse("your plot");
        FlexibleProgress progress = FlexibleProgress.of(plan, all);
        identityApi.emailOf(purchase.buyerUserId()).ifPresent(to -> buyerNotifier.afterCommit(
                "quiet plan " + stage.name().toLowerCase(Locale.ROOT), () -> alerts.quietPlan(
                        new SettlementAlertSender.QuietPlanAlert(to, stage, purchase.reference(), plan.planName(), plot,
                                notice.getTrigger().wire(), plan.inactivityMonths(),
                                RevocationService.DATE.format(FlexibleStandings.lastPaymentOn(all)),
                                plan.finishBy() == null ? null : RevocationService.DATE.format(plan.finishBy()),
                                naira(progress.remaining()), RevocationService.DATE.format(notice.getDeadline()),
                                naira(refund), naira(developerKeeps), note,
                                buyerNotifier.purchaseUrl(purchase.id())))));
    }

    private static String naira(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
