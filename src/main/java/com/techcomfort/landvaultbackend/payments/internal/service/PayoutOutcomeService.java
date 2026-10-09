package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payout;
import com.techcomfort.landvaultbackend.payments.internal.enums.PayoutStatus;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackClient;
import com.techcomfort.landvaultbackend.payments.internal.repository.PayoutRepository;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * TR-3: how a payout actually ended. A {@code transfer.*} webhook or the
 * sweep only PROMPTS a check: we ask Paystack's verify API and adopt its
 * answer (decided with the user — never trust the message itself). Paystack
 * is the authority on money, so its latest verified word wins, even after a
 * "success" (decided with the user). Failures, reversals and anything
 * strange are flagged for a person. See AGENTS.md, "Payments", step 8c.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutOutcomeService {

    /** What a refresh did — the sweep counts these. */
    public enum Outcome { UPDATED, UNCHANGED, NEEDS_REVIEW, NOT_OURS }

    private static final Set<PayoutStatus> CLOSED = Set.of(PayoutStatus.FAILED, PayoutStatus.REVERSED,
            PayoutStatus.CANCELLED);

    private final PayoutRepository payouts;
    private final PaystackClient paystack;
    private final TenancyApi tenancyApi;
    private final IdentityApi identityApi;
    private final SettlementAlertSender alerts;
    private final AuditApi auditApi;

    @Value("${landvault.payouts.stuck-sending-after}")
    private Duration stuckSendingAfter;

    @Value("${landvault.payouts.stuck-pending-after}")
    private Duration stuckPendingAfter;

    /**
     * Payouts with no reply for a while, or accepted but not finished for a
     * while. In a transaction on purpose: {@code payouts} is RLS-policied, and
     * only a transaction carries the platform scope to the database — read
     * outside one, every row is hidden and the sweep silently finds nothing.
     */
    @Transactional(readOnly = true)
    public List<String> stuckReferences() {
        Instant now = Instant.now();
        return payouts.findStuck(PayoutStatus.SENDING, now.minus(stuckSendingAfter),
                PayoutStatus.PENDING, now.minus(stuckPendingAfter));
    }

    /** Asks Paystack about one of our payouts and records its answer. A reference that isn't ours is ignored. */
    @Transactional
    public Outcome refresh(String reference) {
        Optional<Payout> found = payouts.findByReferenceForUpdate(reference);
        if (found.isEmpty()) {
            return Outcome.NOT_OURS;
        }
        Payout payout = found.get();
        PaystackClient.Transfer transfer = paystack.verifyTransfer(reference);
        if (!transfer.found()) {
            return neverReachedPaystack(payout);
        }
        PayoutStatus paystackSays = PayoutStatus.fromPaystack(transfer.status());
        PayoutStatus current = payout.getStatus();
        if (paystackSays == current) {
            return recordTransferTime(payout, transfer);
        }
        if (CLOSED.contains(current) && paystackSays == PayoutStatus.SUCCESS) {
            return moneyWentAfterAll(payout, transfer);
        }
        if (current == PayoutStatus.SUCCESS && paystackSays != PayoutStatus.FAILED
                && paystackSays != PayoutStatus.REVERSED) {
            // Never step a success back to "not finished" on an ambiguous reply.
            return Outcome.UNCHANGED;
        }
        if (CLOSED.contains(current)) {
            return Outcome.UNCHANGED;
        }
        payout.setStatus(paystackSays);
        if (transfer.transferCode() != null) {
            payout.setTransferCode(transfer.transferCode());
        }
        if (transfer.transferredAt() != null) {
            payout.setTransferredAt(transfer.transferredAt());
        }
        if (transfer.message() != null && paystackSays != PayoutStatus.SUCCESS) {
            payout.setGatewayMessage(truncate(transfer.message()));
        }
        payouts.save(payout);
        audit(payout, "Paystack reports payout " + reference + " is now " + paystackSays.wire()
                + (current == PayoutStatus.SUCCESS ? " (it had been recorded as success)" : "") + ".");
        if (paystackSays == PayoutStatus.FAILED || paystackSays == PayoutStatus.REVERSED) {
            alert(payout, paystackSays.wire(), current == PayoutStatus.SUCCESS
                    ? "Paystack had reported this part as successful; it now says " + paystackSays.wire()
                    + ". The money is back in the balance and this part is owed again."
                    : "This part is owed again." + (transfer.message() == null ? "" : " Paystack: " + transfer.message()));
        }
        log.info("Payout {} moved {} -> {}", reference, current.wire(), paystackSays.wire());
        return Outcome.UPDATED;
    }

    /**
     * Paystack has no transfer with this reference. For a payout stuck in
     * {@code sending} past the threshold, the request never arrived, so no
     * money moved: it fails and the part is owed again. Anything else is left.
     */
    private Outcome neverReachedPaystack(Payout payout) {
        boolean stuck = payout.getStatus() == PayoutStatus.SENDING
                && payout.getCreatedAt().isBefore(Instant.now().minus(stuckSendingAfter));
        if (!stuck) {
            return Outcome.UNCHANGED;
        }
        payout.setStatus(PayoutStatus.FAILED);
        payout.setGatewayMessage("Paystack never received this request, so nothing was sent.");
        payouts.save(payout);
        audit(payout, "Payout " + payout.getReference() + " never reached Paystack; marked failed.");
        return Outcome.UPDATED;
    }

    /**
     * We had closed this payout, but Paystack says the money went. The truth
     * is recorded — unless another attempt for the same part is already live or
     * paid, in which case the part may have been paid twice: the row is left as
     * it is (the database allows one live attempt per part) and flagged.
     */
    private Outcome moneyWentAfterAll(Payout payout, PaystackClient.Transfer transfer) {
        String previous = payout.getStatus().wire();
        boolean anotherAttempt = payouts.existsOtherForPart(payout.getTransactionId(), payout.getPartNumber(),
                payout.getId(), PayoutStatus.LIVE);
        String reason;
        if (anotherAttempt) {
            reason = "Paystack reports this payout as successful, but it was " + previous + " and another attempt "
                    + "for the same part exists — this part may have been paid twice. Check with Paystack and "
                    + "recover any overpayment.";
        } else {
            payout.setStatus(PayoutStatus.SUCCESS);
            payout.setTransferredAt(transfer.transferredAt());
            reason = "Paystack reports this payout as successful after it was recorded as " + previous
                    + ". Recorded as success; confirm with Paystack.";
        }
        payout.setReviewReason(reason);
        payouts.save(payout);
        audit(payout, reason);
        alert(payout, anotherAttempt ? "possibly paid twice" : "paid after being marked " + previous, reason);
        log.warn("Payout {} needs review: {}", payout.getReference(), reason);
        return Outcome.NEEDS_REVIEW;
    }

    private Outcome recordTransferTime(Payout payout, PaystackClient.Transfer transfer) {
        if (transfer.transferredAt() != null && payout.getTransferredAt() == null) {
            payout.setTransferredAt(transfer.transferredAt());
            payouts.save(payout);
            return Outcome.UPDATED;
        }
        return Outcome.UNCHANGED;
    }

    private void audit(Payout payout, String detail) {
        // No person decided: Paystack did. Shown as "System".
        auditApi.record(AuditEntryRequest.of(null, "payout." + payout.getStatus().wire(), "payout", payout.getId(),
                payout.getTenantId(), detail));
    }

    private void alert(Payout payout, String status, String detail) {
        String company = tenancyApi.organizationNamesFor(Set.of(payout.getTenantId()))
                .getOrDefault(payout.getTenantId(), "a company");
        List<String> admins = identityApi.superAdminEmails();
        for (String to : admins) {
            alerts.payoutProblem(new SettlementAlertSender.PayoutProblemAlert(to, company, payout.getReference(),
                    "part " + payout.getPartNumber() + " of " + payout.getPartCount(),
                    payout.getAmount().toPlainString(), status, detail));
        }
    }

    private static String truncate(String message) {
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
