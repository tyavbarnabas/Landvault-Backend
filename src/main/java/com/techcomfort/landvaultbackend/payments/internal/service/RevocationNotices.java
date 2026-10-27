package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.FinanceQueueRow;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.internal.domain.PlanRevocation;
import com.techcomfort.landvaultbackend.payments.internal.enums.NoticeTrigger;
import com.techcomfort.landvaultbackend.payments.internal.enums.RevocationStatus;
import com.techcomfort.landvaultbackend.payments.internal.repository.PlanRevocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * Phase 5b: closing a notice of revocation when the buyer, not the developer,
 * ends it — by catching up or by withdrawing. Its own bean so withdrawal and
 * finance verification can use it without depending on {@link RevocationService}
 * (which depends on withdrawal). Runs in the caller's transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RevocationNotices {

    private final PlanRevocationRepository revocations;
    private final AuditApi auditApi;
    private final IdentityApi identityApi;
    private final SettlementAlertSender alerts;
    private final BuyerNotifier buyerNotifier;

    /** The buyer withdrew while a notice ran: it is overtaken, and says so. */
    void closeOnWithdrawal(UUID transactionId) {
        revocations.findOpenLocked(transactionId).ifPresent(notice -> {
            close(notice, RevocationStatus.WITHDRAWN, null, "The buyer withdrew from the plan.");
            auditApi.record(AuditEntryRequest.of(null, "plan.revocation_withdrawn", "plan_revocation", notice.getId(),
                    notice.getSellerTenantId(), "Notice of revocation on " + transactionId
                            + " closed: the buyer withdrew from the plan while it ran."));
        });
    }

    /**
     * Finance verified a plan payment: if nothing is overdue long enough to
     * revoke any more — or the plan is paid off — the notice ends and the
     * buyer is told. A payment merely waiting for finance never clears it.
     */
    void clearIfCaughtUp(FinanceQueueRow row, PlanProgress after) {
        clearIfCaughtUp(row.transactionId(), row.transactionReference(), row.planName(),
                row.plotLabel() + ", " + row.estateName(), after);
    }

    /** Phase 5c too: an accepted restructure puts every instalment on new dates, so nothing is late. */
    void clearIfCaughtUp(UUID transactionId, String reference, String planName, String plot, PlanProgress after) {
        LocalDate today = PlanStandings.today();
        if (!after.complete() && after.revocationTrigger(today).isPresent()) {
            return;
        }
        revocations.findOpenLocked(transactionId).ifPresent(notice -> {
            close(notice, RevocationStatus.CLEARED, null, "The buyer caught up.");
            auditApi.record(AuditEntryRequest.of(null, "plan.revocation_cleared", "plan_revocation", notice.getId(),
                    notice.getSellerTenantId(), "Notice of revocation on " + reference
                            + " ended: nothing is overdue long enough to revoke any more."));
            log.info("Revocation notice {} cleared: the buyer caught up", notice.getId());
            identityApi.emailOf(notice.getBuyerUserId()).ifPresent(to -> buyerNotifier.afterCommit(
                    "revocation cleared", () -> alerts.planRevocation(new SettlementAlertSender.RevocationAlert(to,
                            SettlementAlertSender.RevocationStage.CLEARED, reference, planName, plot,
                            notice.getRevocationAfterMonths(), RevocationService.DATE.format(notice.getDeadline()),
                            null, null, null, null, buyerNotifier.purchaseUrl(transactionId)))));
        });
    }

    /**
     * Flexible contribution, phase 4 (decision 3): finance verified a payment
     * on a flexible plan. An inactivity notice ends with it; a finish-by notice
     * doesn't — only finishing the plan ends that one ({@code finished}).
     */
    void clearOnContribution(UUID transactionId, String reference, String planName, String plot, boolean finished) {
        revocations.findOpenLocked(transactionId)
                .filter(notice -> finished || notice.getTrigger() == NoticeTrigger.INACTIVITY)
                .ifPresent(notice -> {
                    String why = finished ? "The plan was paid in full." : "The buyer made a payment.";
                    close(notice, RevocationStatus.CLEARED, null, why);
                    auditApi.record(AuditEntryRequest.of(null, "plan.revocation_cleared", "plan_revocation",
                            notice.getId(), notice.getSellerTenantId(), "Notice on flexible plan " + reference
                                    + " ended: " + why.toLowerCase(Locale.ROOT)));
                    log.info("Quiet-plan notice {} cleared: {}", notice.getId(), why);
                    identityApi.emailOf(notice.getBuyerUserId()).ifPresent(to -> buyerNotifier.afterCommit(
                            "quiet plan cleared", () -> alerts.quietPlan(new SettlementAlertSender.QuietPlanAlert(to,
                                    SettlementAlertSender.RevocationStage.CLEARED, reference, planName, plot,
                                    notice.getTrigger().wire(), notice.getRevocationAfterMonths(), null, null, null,
                                    RevocationService.DATE.format(notice.getDeadline()), null, null, why,
                                    buyerNotifier.purchaseUrl(transactionId)))));
                });
    }

    void close(PlanRevocation notice, RevocationStatus status, UUID by, String note) {
        notice.setStatus(status);
        notice.setClosedAt(Instant.now());
        notice.setClosedBy(by);
        notice.setCloseNote(note);
        revocations.saveAndFlush(notice);
    }
}
