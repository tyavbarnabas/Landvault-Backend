package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.internal.domain.PlanRestructure;
import com.techcomfort.landvaultbackend.payments.internal.enums.RestructureStatus;
import com.techcomfort.landvaultbackend.payments.internal.repository.PlanRestructureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

/**
 * Phase 5c: closing an open restructure when something else changes the plan
 * under it — a payment verified (a proposal's figures no longer hold), or the
 * plan ending (withdrawn or revoked). Its own bean so finance verification,
 * withdrawal and revocation can use it without depending on
 * {@link RestructureService}. Runs in the caller's transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestructureNotices {

    private final PlanRestructureRepository restructures;
    private final AuditApi auditApi;
    private final IdentityApi identityApi;
    private final SettlementAlertSender alerts;
    private final BuyerNotifier buyerNotifier;

    /**
     * Finance verified a plan payment: a proposal was worked out on what was
     * owed before it, so it lapses (the buyer is told and may ask again). A
     * request still waiting for the developer is untouched — they decide on
     * the plan as it now is.
     */
    void lapseProposalOnPayment(UUID transactionId) {
        restructures.findOpenLocked(transactionId).filter(r -> r.getStatus() == RestructureStatus.PROPOSED)
                .ifPresent(r -> {
                    String why = "a payment was verified since, so what is owed changed";
                    r.close(RestructureStatus.LAPSED, null, "Lapsed: " + why + ".");
                    restructures.saveAndFlush(r);
                    auditApi.record(AuditEntryRequest.of(null, "plan.restructure_lapsed", "plan_restructure",
                            r.getId(), r.getSellerTenantId(), "Restructure proposal on " + r.getTransactionReference()
                                    + " lapsed: " + why + "."));
                    tellBuyer(r, SettlementAlertSender.RestructureStage.LAPSED, why);
                });
    }

    /** The plan ended (withdrawn or revoked): an open request or proposal can't go anywhere. */
    void closeOnPlanEnded(UUID transactionId, String how) {
        restructures.findOpenLocked(transactionId).ifPresent(r -> {
            boolean proposed = r.getStatus() == RestructureStatus.PROPOSED;
            r.close(proposed ? RestructureStatus.LAPSED : RestructureStatus.CANCELLED, null,
                    "The plan ended: " + how + ".");
            restructures.saveAndFlush(r);
            auditApi.record(AuditEntryRequest.of(null, "plan.restructure_closed", "plan_restructure", r.getId(),
                    r.getSellerTenantId(), "Open restructure on " + transactionId + " closed: the plan ended (" + how
                            + ")."));
            log.info("Restructure {} closed: the plan ended ({})", r.getId(), how);
        });
    }

    void tellBuyer(PlanRestructure r, SettlementAlertSender.RestructureStage stage, String note) {
        identityApi.emailOf(r.getBuyerUserId()).ifPresent(to -> buyerNotifier.afterCommit(
                "restructure " + stage.name().toLowerCase(Locale.ROOT), () -> alerts.planRestructure(
                        new SettlementAlertSender.RestructureAlert(to, false, stage, r.getTransactionReference(),
                                r.getPlanName(), r.getPlotLabel() + ", " + r.getEstateName(), null, note,
                                r.getExpiresOn() == null ? null : RevocationService.DATE.format(r.getExpiresOn()),
                                r.getLetterNumber(), buyerNotifier.purchaseUrl(r.getTransactionId())))));
    }
}
