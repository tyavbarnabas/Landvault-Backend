package com.techcomfort.landvaultbackend.checkout.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.AbandonedSale;
import com.techcomfort.landvaultbackend.checkout.AllocationLetterGate;
import com.techcomfort.landvaultbackend.checkout.OfferLetters;
import com.techcomfort.landvaultbackend.checkout.OfferSummary;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.FinanceDecision;
import com.techcomfort.landvaultbackend.checkout.FinanceQueueRow;
import com.techcomfort.landvaultbackend.checkout.PayableTransaction;
import com.techcomfort.landvaultbackend.checkout.PendingPurchase;
import com.techcomfort.landvaultbackend.checkout.PlanRevoked;
import com.techcomfort.landvaultbackend.checkout.PlanWithdrawn;
import com.techcomfort.landvaultbackend.checkout.PlotAllocated;
import com.techcomfort.landvaultbackend.checkout.RunningPlan;
import com.techcomfort.landvaultbackend.checkout.VerifiedSale;
import com.techcomfort.landvaultbackend.checkout.internal.domain.Reservation;
import com.techcomfort.landvaultbackend.checkout.internal.domain.Transaction;
import com.techcomfort.landvaultbackend.checkout.internal.domain.TransactionInstalment;
import com.techcomfort.landvaultbackend.checkout.internal.enums.PaymentPlan;
import com.techcomfort.landvaultbackend.checkout.internal.enums.ReservationStatus;
import com.techcomfort.landvaultbackend.checkout.internal.enums.TransactionStatus;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.RefundAppliesTo;
import com.techcomfort.landvaultbackend.checkout.internal.repository.ReservationRepository;
import com.techcomfort.landvaultbackend.checkout.internal.repository.TransactionInstalmentRepository;
import com.techcomfort.landvaultbackend.checkout.internal.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@link CheckoutApi}'s implementation. {@code transactions} is buyer-owned and not RLS-policied (AGENTS.md). */
@Service
@RequiredArgsConstructor
public class CheckoutApiImpl implements CheckoutApi {

    private final TransactionRepository transactions;
    private final ReservationRepository reservations;
    private final ReservationService reservationService;
    private final PlotLockGateway plotLockGateway;
    private final AuditApi auditApi;
    private final ApplicationEventPublisher events;
    private final AllocationLetterGate letterGate;
    private final OfferLetters offerLetters;
    private final TransactionService transactionService;
    private final EntityManager entityManager;
    private final TransactionInstalmentRepository instalmentRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<PayableTransaction> transactionForBuyer(UUID transactionId, UUID buyerUserId) {
        return transactions.findByIdAndBuyerUserId(transactionId, buyerUserId).map(CheckoutApiImpl::toPayable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinanceQueueRow> onPlan(UUID tenantId, Collection<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Boolean> letters = new HashMap<>();
        return transactions.findOnPlan(tenantId, transactionIds).stream().map(r -> new FinanceQueueRow(
                (UUID) r[0], (String) r[1], (UUID) r[2], (String) r[3], (UUID) r[4],
                r[5] == null ? "Plot " + r[6] : "Block " + r[5] + ", Plot " + r[6],
                (UUID) r[7], (BigDecimal) r[8], Currency.valueOf((String) r[9]), (Instant) r[10],
                letters.computeIfAbsent((UUID) r[2], letterGate::letterReady),
                (BigDecimal) r[11], (String) r[12], "WITHDRAWN".equals(r[13]), "REVOKED".equals(r[13]))).toList();
    }

    @Override
    @Transactional
    public boolean withdraw(UUID transactionId, UUID buyerUserId, String reason) {
        Transaction transaction = transactions.findByIdAndBuyerUserId(transactionId, buyerUserId).orElse(null);
        if (transaction == null) {
            return false;
        }
        // The reservation's row lock first, like every other change to this purchase.
        reservations.findLockedById(transaction.getReservationId()).orElseThrow();
        if (transactions.withdraw(transactionId, reason) != 1) {
            return false;
        }
        // From here every step must happen: an exception rolls the withdrawal back.
        if (!plotLockGateway.releaseWithdrawn(transactionId, buyerUserId.toString())) {
            throw new IllegalStateException("Plot " + transaction.getPlotId() + " of withdrawn purchase "
                    + transactionId + " was not on the plan");
        }
        auditApi.record(AuditEntryRequest.of(buyerUserId, "transaction.withdrawn", "transaction", transactionId,
                transaction.getSellerTenantId(), "Buyer withdrew from plan " + transaction.getPlanName() + " on "
                        + transaction.getReference() + "; plot " + transaction.getPlotId() + " is back on sale."
                        + (reason == null ? "" : " Reason given: " + reason)));
        events.publishEvent(new PlanWithdrawn(transactionId, buyerUserId, transaction.getPlotId(),
                transaction.getSellerTenantId()));
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> withdrawnAt(UUID transactionId) {
        return transactions.findById(transactionId).map(Transaction::getWithdrawnAt);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PayableTransaction> planForSeller(UUID transactionId, UUID tenantId) {
        return transactions.findById(transactionId)
                .filter(t -> t.getPlan() == PaymentPlan.INSTALLMENT)
                .filter(t -> tenantId.equals(t.getSellerTenantId()))
                .filter(t -> plotLockGateway.isVisible(t.getPlotId()))
                .map(CheckoutApiImpl::toPayable);
    }

    @Override
    @Transactional
    public boolean revoke(UUID transactionId, UUID tenantId, UUID actorUserId, String reason) {
        Optional<Reservation> locked = lockForFinance(transactionId, tenantId);
        if (locked.isEmpty()) {
            return false;
        }
        Transaction transaction = transactions.findById(transactionId).orElseThrow();
        if (transactions.revoke(transactionId, reason) != 1) {
            return false;
        }
        // The update is native: reload the copy this request already holds, so
        // anything read afterwards in it (the response) sees `revoked`.
        entityManager.refresh(transaction);
        // From here every step must happen: an exception rolls the revocation back.
        if (!plotLockGateway.releaseWithdrawn(transactionId, actorUserId.toString())) {
            throw new IllegalStateException("Plot " + transaction.getPlotId() + " of revoked purchase "
                    + transactionId + " was not on the plan");
        }
        auditApi.record(AuditEntryRequest.of(actorUserId, "transaction.revoked", "transaction", transactionId,
                tenantId, "Plan " + transaction.getPlanName() + " on " + transaction.getReference()
                        + " revoked by the developer after the notice ran out; the plot is back on sale. Reason: "
                        + reason));
        events.publishEvent(new PlanRevoked(transactionId, transaction.getBuyerUserId(), transaction.getPlotId(),
                tenantId));
        return true;
    }

    @Override
    @Transactional
    public boolean restructure(UUID transactionId, UUID buyerUserId, int fromVersion,
                               List<PlanSchedule.Instalment> schedule) {
        Transaction transaction = transactions.findByIdAndBuyerUserId(transactionId, buyerUserId).orElse(null);
        if (transaction == null) {
            return false;
        }
        // The reservation's row lock first, like every other change to this purchase.
        reservations.findLockedById(transaction.getReservationId()).orElseThrow();
        if (transactions.advanceSchedule(transactionId, fromVersion) != 1) {
            return false;
        }
        int version = fromVersion + 1;
        instalmentRepository.saveAll(schedule.stream().map(i -> TransactionInstalment.builder()
                .transactionId(transactionId)
                .scheduleVersion(version)
                .sequence(i.sequence())
                .dueDate(i.dueDate())
                .amount(i.amount())
                .carriedPenalty(i.carriedPenalty())
                .build()).toList());
        entityManager.refresh(transaction);
        auditApi.record(AuditEntryRequest.of(buyerUserId, "transaction.restructured", "transaction", transactionId,
                transaction.getSellerTenantId(), "Plan " + transaction.getPlanName() + " on "
                        + transaction.getReference() + " restructured: schedule version " + version + " ("
                        + schedule.size() + " instalments) in force; version " + fromVersion + " kept as history."));
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> revokedAt(UUID transactionId) {
        return transactions.findById(transactionId).map(Transaction::getRevokedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanEscrow> escrowOf(UUID transactionId) {
        return transactions.findById(transactionId).filter(t -> t.getPlan() == PaymentPlan.INSTALLMENT)
                .map(t -> new PlanEscrow(t.getTotalPrice(), new VerifiedSale.Escrow(
                        t.getStatus() == TransactionStatus.VERIFIED, t.getEscrowDeductionPct(),
                        t.getEscrowAppliesTo(), !Boolean.FALSE.equals(t.getPenaltiesRefundable()),
                        t.getStatus() == TransactionStatus.WITHDRAWN, t.getStatus() == TransactionStatus.REVOKED)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RunningPlan> runningPlans(UUID tenantId) {
        return transactions.findRunningPlans(tenantId).stream().map(r -> new RunningPlan((UUID) r[0], (String) r[1],
                (UUID) r[2], (UUID) r[3], (String) r[4], (UUID) r[5],
                r[6] == null ? "Plot " + r[7] : "Block " + r[6] + ", Plot " + r[7], (UUID) r[8], (String) r[9],
                Currency.valueOf((String) r[10]), (Instant) r[11])).toList();
    }

    @Override
    @Transactional
    public FinanceDecision completePlan(UUID transactionId, UUID tenantId, UUID financeUserId) {
        Optional<Reservation> locked = lockForFinance(transactionId, tenantId);
        if (locked.isEmpty()) {
            return FinanceDecision.NOT_FOUND;
        }
        Transaction transaction = transactions.findById(transactionId).orElseThrow();
        if (transaction.getStatus() != TransactionStatus.ON_PLAN) {
            return FinanceDecision.NOT_ON_PLAN;
        }
        // The final letter is issued now, from the estate's current approved setup.
        if (!letterGate.letterReady(transaction.getEstateId())) {
            return FinanceDecision.LETTER_NOT_APPROVED;
        }
        if (!plotLockGateway.sellFromPlan(transaction.getPlotId())) {
            return FinanceDecision.PLOT_NOT_ON_PLAN;
        }
        if (transactions.completePlan(transactionId) != 1) {
            throw new IllegalStateException("Transaction " + transactionId + " left on_plan while locked");
        }
        auditApi.record(AuditEntryRequest.of(financeUserId, "transaction.verified", "transaction", transactionId,
                tenantId, "Plan " + transaction.getPlanName() + " for " + transaction.getReference()
                        + " paid in full and verified by finance; plot " + transaction.getPlotId() + " sold."));
        publishAllocated(transaction, locked.get().getCreatedAt(), financeUserId, false);
        return FinanceDecision.DONE;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PlanSchedule> schedulesOf(Collection<UUID> transactionIds) {
        return transactionIds.isEmpty() ? Map.of()
                : transactionService.schedulesOf(transactions.findAllById(transactionIds));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OfferSummary> offerFor(UUID transactionId) {
        return Optional.ofNullable(offerLetters.forTransactions(List.of(transactionId)).get(transactionId));
    }

    @Override
    @Transactional
    public boolean recordPaymentReceived(UUID transactionId, String paymentReference) {
        if (transactions.moveToAwaitingFinance(transactionId) == 0) {
            return false;
        }
        Transaction transaction = transactions.findById(transactionId).orElseThrow();
        // The actor is the payment provider, not a person: a system entry.
        auditApi.record(AuditEntryRequest.of(null, "transaction.payment_received", "transaction", transactionId,
                transaction.getSellerTenantId(), "Payment " + paymentReference + " confirmed by Paystack for "
                        + transaction.getReference() + "; now awaiting finance verification. The plot stays held."));
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> pendingTransactionsWithHoldExpiredBefore(Instant cutoff) {
        return transactions.findPendingWithHoldExpiredBefore(cutoff);
    }

    @Override
    @Transactional
    public boolean abandonTransaction(UUID transactionId) {
        Transaction transaction = transactions.findById(transactionId).orElse(null);
        if (transaction == null) {
            return false;
        }
        // The reservation's row lock first — the same lock opening a transaction
        // and a buyer's cancel take — then the once-only status change.
        Reservation reservation = reservations.findLockedById(transaction.getReservationId()).orElseThrow();
        if (transactions.moveToAbandoned(transactionId) == 0) {
            return false;
        }
        reservationService.endHold(reservation, ReservationStatus.EXPIRED, null, "reservation.expired",
                "Hold ended: purchase " + transaction.getReference() + " was abandoned with nothing paid; "
                        + "the plot is back on sale.");
        auditApi.record(AuditEntryRequest.of(null, "transaction.abandoned", "transaction", transactionId,
                transaction.getSellerTenantId(), "Nothing was paid for " + transaction.getReference()
                        + " after the hold ran out; purchase abandoned and the plot released."));
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinanceQueueRow> awaitingFinance(UUID tenantId) {
        Map<UUID, Boolean> letters = new HashMap<>();
        return transactions.findAwaitingFinance(tenantId).stream().map(r -> new FinanceQueueRow(
                (UUID) r[0], (String) r[1], (UUID) r[2], (String) r[3], (UUID) r[4],
                r[5] == null ? "Plot " + r[6] : "Block " + r[5] + ", Plot " + r[6],
                (UUID) r[7], (BigDecimal) r[8], Currency.valueOf((String) r[9]), (Instant) r[10],
                letters.computeIfAbsent((UUID) r[2], letterGate::letterReady),
                r[11] == null ? (BigDecimal) r[8] : (BigDecimal) r[11], (String) r[12])).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> financeStatusOf(UUID transactionId, UUID tenantId) {
        return transactions.findById(transactionId)
                .filter(t -> tenantId.equals(t.getSellerTenantId()))
                .filter(t -> plotLockGateway.isVisible(t.getPlotId()))
                .map(t -> t.getStatus().getValue());
    }

    @Override
    @Transactional
    public FinanceDecision verifyAndAllocate(UUID transactionId, UUID tenantId, UUID financeUserId) {
        Optional<Reservation> locked = lockForFinance(transactionId, tenantId);
        if (locked.isEmpty()) {
            return FinanceDecision.NOT_FOUND;
        }
        Transaction transaction = transactions.findById(transactionId).orElseThrow();
        if (transaction.getStatus() != TransactionStatus.AWAITING_FINANCE) {
            return FinanceDecision.NOT_AWAITING_FINANCE;
        }
        // Checked before anything is written, so a refusal leaves everything as it was.
        if (!letterGate.letterReady(transaction.getEstateId())) {
            return FinanceDecision.LETTER_NOT_APPROVED;
        }
        // Phase 3a: an instalment plan's deposit holds the plot on the plan; the
        // last instalment sells it (3b). Paying in full sells it now.
        boolean deposit = transaction.getPlan() == PaymentPlan.INSTALLMENT;
        if (!(deposit ? plotLockGateway.holdOnPlan(transaction.getPlotId())
                : plotLockGateway.sell(transaction.getPlotId()))) {
            return FinanceDecision.PLOT_NOT_RESERVED;
        }
        // From here every step must happen: an exception rolls back the plot sale too.
        TransactionStatus next = deposit ? TransactionStatus.ON_PLAN : TransactionStatus.VERIFIED;
        if (transactions.moveFromAwaitingFinance(transactionId, next.name()) != 1) {
            throw new IllegalStateException("Transaction " + transactionId + " left awaiting_finance while locked");
        }
        Reservation reservation = locked.get();
        Instant reservedAt = reservation.getCreatedAt();
        reservation.setStatus(ReservationStatus.CONVERTED);
        reservation.setEndedAt(Instant.now());
        reservations.save(reservation);
        auditApi.record(deposit
                ? AuditEntryRequest.of(financeUserId, "transaction.deposit_verified", "transaction", transactionId,
                tenantId, "Deposit for " + transaction.getReference() + " (" + transaction.getPlanName()
                        + ") verified by finance; plot " + transaction.getPlotId()
                        + " held on the plan and the reservation converted.")
                : AuditEntryRequest.of(financeUserId, "transaction.verified", "transaction", transactionId,
                tenantId, "Payment for " + transaction.getReference() + " verified by finance; plot "
                        + transaction.getPlotId() + " allocated (sold) and the reservation converted."));
        publishAllocated(transaction, reservedAt, financeUserId, deposit);
        return FinanceDecision.DONE;
    }

    @Override
    @Transactional
    public FinanceDecision rejectPayment(UUID transactionId, UUID tenantId, UUID financeUserId, String reason) {
        Optional<Reservation> locked = lockForFinance(transactionId, tenantId);
        if (locked.isEmpty()) {
            return FinanceDecision.NOT_FOUND;
        }
        Transaction transaction = transactions.findById(transactionId).orElseThrow();
        if (transactions.moveFromAwaitingFinance(transactionId, TransactionStatus.REJECTED.name()) != 1) {
            return FinanceDecision.NOT_AWAITING_FINANCE;
        }
        reservationService.endHold(locked.get(), ReservationStatus.RELEASED, financeUserId, "reservation.released",
                "Hold ended: finance rejected the payment for " + transaction.getReference() + "; the plot is back on sale.");
        auditApi.record(AuditEntryRequest.of(financeUserId, "transaction.rejected", "transaction", transactionId,
                tenantId, "Payment for " + transaction.getReference() + " rejected by finance: " + reason));
        return FinanceDecision.DONE;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AbandonedSale> abandonedSales(UUID tenantId, Collection<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Boolean> letters = new HashMap<>();
        return transactions.findAbandonedSales(tenantId, transactionIds).stream().map(r -> new AbandonedSale(
                (UUID) r[0], (String) r[1], (String) r[2],
                r[3] == null ? "Plot " + r[4] : "Block " + r[3] + ", Plot " + r[4],
                (BigDecimal) r[5], Currency.valueOf((String) r[6]),
                "AVAILABLE_DEV".equals(r[7]) || "AVAILABLE_INV".equals(r[7]),
                letters.computeIfAbsent((UUID) r[8], letterGate::letterReady))).toList();
    }

    @Override
    @Transactional
    public FinanceDecision allocateAbandoned(UUID transactionId, UUID tenantId, UUID financeUserId) {
        Optional<Reservation> locked = lockForFinance(transactionId, tenantId);
        if (locked.isEmpty()) {
            return FinanceDecision.NOT_FOUND;
        }
        Transaction transaction = transactions.findById(transactionId).orElseThrow();
        if (transaction.getStatus() != TransactionStatus.ABANDONED) {
            return FinanceDecision.NOT_ABANDONED;
        }
        if (transaction.getPlan() == PaymentPlan.INSTALLMENT) {
            return FinanceDecision.LATE_DEPOSIT_NOT_SUPPORTED;
        }
        // Checked before anything is written: if the plot is taken, nothing changes.
        if (!letterGate.letterReady(transaction.getEstateId())) {
            return FinanceDecision.LETTER_NOT_APPROVED;
        }
        if (!plotLockGateway.sellAvailable(transaction.getPlotId())) {
            return FinanceDecision.PLOT_NOT_AVAILABLE;
        }
        // From here every step must happen: an exception rolls back the plot sale too.
        if (transactions.moveFromAbandoned(transactionId, TransactionStatus.VERIFIED.name()) != 1) {
            throw new IllegalStateException("Transaction " + transactionId + " left abandoned while locked");
        }
        auditApi.record(AuditEntryRequest.of(financeUserId, "transaction.verified", "transaction", transactionId,
                tenantId, "Late payment for " + transaction.getReference() + " accepted by finance; plot "
                        + transaction.getPlotId() + " allocated (sold). The earlier hold stays expired."));
        publishAllocated(transaction, locked.get().getCreatedAt(), financeUserId, false);
        return FinanceDecision.DONE;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PendingPurchase> purchasesAwaitingFinance(UUID buyerUserId) {
        return transactions.findByBuyerUserIdAndStatusOrderByCreatedAtDesc(buyerUserId, TransactionStatus.AWAITING_FINANCE)
                .stream()
                .map(t -> new PendingPurchase(t.getId(), t.getPlotId(), t.getEstateId(), t.getPlan().getValue(),
                        t.getIntent(), t.getBasePrice(), t.getCornerPremiumPct(), t.getTotalPrice(), t.getCurrency(),
                        t.getCreatedAt()))
                .toList();
    }

    /**
     * Synchronous, inside this transaction: the portfolio's owned-plot record
     * and the allocation letter are written now, and a failure in either rolls
     * the sale back with it (FV-3).
     */
    private void publishAllocated(Transaction t, Instant reservedAt, UUID financeUserId, boolean provisional) {
        events.publishEvent(new PlotAllocated(t.getId(), t.getBuyerUserId(), t.getPlotId(), t.getEstateId(),
                t.getSellerTenantId(), t.getPlan().getValue(), t.getIntent(), t.getBasePrice(),
                t.getCornerPremiumPct(), t.getTotalPrice(), t.getCurrency(), reservedAt, financeUserId,
                t.buyerAddress().orElse(null), provisional));
    }

    /**
     * This company's purchase, its plot visible to the caller (so a
     * branch-scoped officer can't reach another branch's), and its reservation
     * row locked — the same lock every other change to the hold takes.
     */
    private Optional<Reservation> lockForFinance(UUID transactionId, UUID tenantId) {
        Optional<Transaction> transaction = transactions.findById(transactionId)
                .filter(t -> tenantId.equals(t.getSellerTenantId()))
                .filter(t -> plotLockGateway.isVisible(t.getPlotId()));
        return transaction.flatMap(t -> reservations.findLockedById(t.getReservationId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VerifiedSale> verifiedSales() {
        return transactions.findVerifiedSales(null).stream().map(CheckoutApiImpl::toVerifiedSale).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VerifiedSale> verifiedSale(UUID transactionId) {
        return transactions.findVerifiedSales(transactionId).stream().map(CheckoutApiImpl::toVerifiedSale).findFirst();
    }

    private static VerifiedSale toVerifiedSale(Object[] r) {
        return new VerifiedSale((UUID) r[0], (String) r[1], (UUID) r[2], (String) r[3],
                r[4] == null ? "Plot " + r[5] : "Block " + r[4] + ", Plot " + r[5],
                (BigDecimal) r[6], Currency.valueOf((String) r[7]), (Instant) r[8],
                !"INSTALLMENT".equals(r[9]) ? null : new VerifiedSale.Escrow("VERIFIED".equals(r[10]),
                        (BigDecimal) r[11], r[12] == null ? null : RefundAppliesTo.valueOf((String) r[12]),
                        !Boolean.FALSE.equals(r[13]), "WITHDRAWN".equals(r[10]), "REVOKED".equals(r[10])));
    }

    private static PayableTransaction toPayable(Transaction t) {
        return new PayableTransaction(t.getId(), t.getReference(), t.getBuyerUserId(), t.getReservationId(),
                t.getPlotId(), t.getEstateId(), t.getSellerTenantId(), t.getTotalPrice(), t.getCurrency(),
                t.getPlan() == null ? null : t.getPlan().getValue(), t.getStatus().getValue(),
                t.getPlan() == PaymentPlan.INSTALLMENT ? t.getDepositAmount() : t.getTotalPrice());
    }
}
