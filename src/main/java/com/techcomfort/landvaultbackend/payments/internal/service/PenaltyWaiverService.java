package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.FinanceQueueRow;
import com.techcomfort.landvaultbackend.checkout.PayableTransaction;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.common.LatePaymentTerms;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.dto.PenaltyWaiversDto;
import com.techcomfort.landvaultbackend.payments.internal.domain.PenaltyWaiver;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PenaltyWaiverRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Penalty waivers (decided with the user, 2026-10-24: A, A, A, A). The
 * developer's finance forgives some or all of a late-payment penalty not yet
 * paid — reached on a late instalment, or carried onto one by a restructure.
 * A waiver is a fixed amount: subtracted wherever penalties are worked out
 * ({@link PlanProgress#penaltyDue}), so the buyer's standing, the next payment
 * and a restructure all see the reduced figure; a higher step reached later is
 * owed beyond it. No buyer request flow: the developer decides. See AGENTS.md,
 * "Instalment plans", "Penalty waivers".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PenaltyWaiverService {

    private final CheckoutApi checkoutApi;
    private final PlanStandings planStandings;
    private final PenaltyWaiverRepository waivers;
    private final PaymentRepository payments;
    private final AuditApi auditApi;
    private final IdentityApi identityApi;
    private final SettlementAlertSender alerts;
    private final BuyerNotifier buyerNotifier;

    @Transactional(readOnly = true)
    public PenaltyWaiversDto view(UUID transactionId) {
        Plan plan = plan(transactionId, company());
        return dto(plan, progress(plan), PlanStandings.today());
    }

    @Transactional
    public PenaltyWaiversDto waive(UUID transactionId, int sequence, BigDecimal amount, String reason) {
        TenantScope scope = company();
        Plan plan = plan(transactionId, scope);
        PayableTransaction purchase = plan.purchase();
        FinanceVerificationService.refuseOwnPurchase(purchase.buyerUserId(), scope.userId());
        if (!purchase.isOnPlan()) {
            throw new PaymentException.PenaltyNotWaivable("Penalties can be waived only on a running plan (this "
                    + "purchase is " + purchase.status() + ").");
        }
        // The successful payments locked — the same lock paying and verifying take — so two
        // waivers, or a waiver and a payment starting, can't both work from the same figure.
        payments.findLockedByTransactionIdAndStatus(transactionId, PaymentStatus.SUCCEEDED);
        PlanProgress progress = progress(plan);
        LocalDate today = PlanStandings.today();
        PlanProgress.InstalmentStanding standing = progress.standings(today).stream()
                .filter(s -> s.instalment().sequence() == sequence).findFirst()
                .orElseThrow(() -> new PaymentException.PenaltyNotWaivable("This plan has no instalment " + sequence
                        + "."));
        if (standing.standing() == PlanProgress.Standing.PAID
                || standing.standing() == PlanProgress.Standing.AWAITING_VERIFICATION) {
            throw new PaymentException.PenaltyNotWaivable("Instalment " + sequence + " is already paid — a penalty "
                    + "paid with it can't be waived.");
        }
        BigDecimal outstanding = progress.penaltyDue(standing.instalment(), today)
                .map(LatePaymentTerms.Penalty::amount).orElse(BigDecimal.ZERO);
        if (outstanding.signum() == 0) {
            throw new PaymentException.PenaltyNotWaivable("Instalment " + sequence + " carries no penalty to waive "
                    + "today.");
        }
        BigDecimal waived = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (waived.compareTo(outstanding) > 0) {
            throw new PaymentException.WaiverTooLarge(outstanding.setScale(2, RoundingMode.HALF_UP));
        }
        PenaltyWaiver waiver = waivers.saveAndFlush(PenaltyWaiver.builder()
                .transactionId(transactionId)
                .sellerTenantId(purchase.sellerTenantId())
                .buyerUserId(purchase.buyerUserId())
                .scheduleVersion(plan.schedule().version())
                .instalmentSequence(sequence)
                .amount(waived)
                .reason(reason.trim())
                .waivedBy(scope.userId())
                .build());
        BigDecimal stillOwed = outstanding.subtract(waived).setScale(2, RoundingMode.HALF_UP);
        auditApi.record(AuditEntryRequest.of(scope.userId(), "plan.penalty_waived", "penalty_waiver", waiver.getId(),
                purchase.sellerTenantId(), "NGN " + waived.toPlainString() + " of the penalty on instalment "
                        + sequence + " of " + purchase.reference() + " waived; NGN " + stillOwed.toPlainString()
                        + " still owed on it today. Reason: " + waiver.getReason()));
        log.info("Penalty waiver {} on plan {} instalment {} by {}", waiver.getId(), transactionId, sequence,
                scope.userId());
        identityApi.emailOf(purchase.buyerUserId()).ifPresent(to -> buyerNotifier.afterCommit("penalty waived",
                () -> alerts.penaltyWaived(new SettlementAlertSender.PenaltyWaivedAlert(to, purchase.reference(),
                        plan.schedule().planName(), plan.row().plotLabel() + ", " + plan.row().estateName(), sequence,
                        waived.toPlainString(), stillOwed.toPlainString(), waiver.getReason(),
                        buyerNotifier.purchaseUrl(transactionId)))));
        return dto(plan, progress(plan), today);
    }

    // --- reading ---

    private record Plan(PayableTransaction purchase, FinanceQueueRow row, PlanSchedule schedule) {
    }

    /** This company's instalment purchase, visible to the caller (another branch's is a 404), with its schedule. */
    private Plan plan(UUID transactionId, TenantScope scope) {
        PayableTransaction purchase = checkoutApi.planForSeller(transactionId, scope.tenantId())
                .orElseThrow(PaymentException.TransactionNotFound::new);
        FinanceQueueRow row = checkoutApi.onPlan(scope.tenantId(), List.of(transactionId)).stream().findFirst()
                .orElseThrow(PaymentException.TransactionNotFound::new);
        PlanSchedule schedule = checkoutApi.schedulesOf(List.of(transactionId)).get(transactionId);
        if (schedule == null) {
            throw new PaymentException.TransactionNotFound();
        }
        return new Plan(purchase, row, schedule);
    }

    private PlanProgress progress(Plan plan) {
        return planStandings.progressOf(Map.of(plan.purchase().id(), plan.schedule())).get(plan.purchase().id());
    }

    private PenaltyWaiversDto dto(Plan plan, PlanProgress progress, LocalDate today) {
        List<PenaltyWaiversDto.Penalty> penalties = plan.purchase().isOnPlan() ? progress.standings(today).stream()
                .filter(s -> s.standing() != PlanProgress.Standing.PAID
                        && s.standing() != PlanProgress.Standing.AWAITING_VERIFICATION)
                .flatMap(s -> progress.penaltyReached(s.instalment(), today).stream().map(reached ->
                        new PenaltyWaiversDto.Penalty(s.instalment().sequence(), s.instalment().dueDate(),
                                s.standing().wire(), reached.amount(), progress.waivedOn(s.instalment().sequence()),
                                progress.penaltyDue(s.instalment(), today).map(LatePaymentTerms.Penalty::amount)
                                        .orElse(BigDecimal.ZERO))))
                .toList() : List.of();
        List<PenaltyWaiversDto.Waiver> made = waivers.findByTransactionIdOrderByCreatedAtDesc(plan.purchase().id())
                .stream().map(w -> new PenaltyWaiversDto.Waiver(w.getId(), w.getInstalmentSequence(),
                        w.getScheduleVersion(), w.getAmount(), w.getReason(), w.getCreatedAt(), w.getWaivedBy()))
                .toList();
        return new PenaltyWaiversDto(plan.purchase().id(), plan.purchase().reference(), plan.purchase().status(),
                plan.purchase().currency().name(), penalties, made);
    }

    private static TenantScope company() {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        if (scope.tenantId() == null) {
            throw new PaymentException.CompanyScopeRequired();
        }
        return scope;
    }
}
