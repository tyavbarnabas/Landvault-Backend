package com.techcomfort.landvaultbackend.checkout.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.AbandonedSale;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.FinanceDecision;
import com.techcomfort.landvaultbackend.checkout.FinanceQueueRow;
import com.techcomfort.landvaultbackend.checkout.PayableTransaction;
import com.techcomfort.landvaultbackend.checkout.VerifiedSale;
import com.techcomfort.landvaultbackend.checkout.internal.domain.Reservation;
import com.techcomfort.landvaultbackend.checkout.internal.domain.Transaction;
import com.techcomfort.landvaultbackend.checkout.internal.enums.ReservationStatus;
import com.techcomfort.landvaultbackend.checkout.internal.enums.TransactionStatus;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.checkout.internal.repository.ReservationRepository;
import com.techcomfort.landvaultbackend.checkout.internal.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

    @Override
    @Transactional(readOnly = true)
    public Optional<PayableTransaction> transactionForBuyer(UUID transactionId, UUID buyerUserId) {
        return transactions.findByIdAndBuyerUserId(transactionId, buyerUserId).map(CheckoutApiImpl::toPayable);
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
        return transactions.findAwaitingFinance(tenantId).stream().map(r -> new FinanceQueueRow(
                (UUID) r[0], (String) r[1], (UUID) r[2], (String) r[3], (UUID) r[4],
                r[5] == null ? "Plot " + r[6] : "Block " + r[5] + ", Plot " + r[6],
                (UUID) r[7], (BigDecimal) r[8], Currency.valueOf((String) r[9]), (Instant) r[10])).toList();
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
        if (!plotLockGateway.sell(transaction.getPlotId())) {
            return FinanceDecision.PLOT_NOT_RESERVED;
        }
        // From here every step must happen: an exception rolls back the plot sale too.
        if (transactions.moveFromAwaitingFinance(transactionId, TransactionStatus.VERIFIED.name()) != 1) {
            throw new IllegalStateException("Transaction " + transactionId + " left awaiting_finance while locked");
        }
        Reservation reservation = locked.get();
        reservation.setStatus(ReservationStatus.CONVERTED);
        reservation.setEndedAt(Instant.now());
        reservations.save(reservation);
        auditApi.record(AuditEntryRequest.of(financeUserId, "transaction.verified", "transaction", transactionId,
                tenantId, "Payment for " + transaction.getReference() + " verified by finance; plot "
                        + transaction.getPlotId() + " allocated (sold) and the reservation converted."));
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
        return transactions.findAbandonedSales(tenantId, transactionIds).stream().map(r -> new AbandonedSale(
                (UUID) r[0], (String) r[1], (String) r[2],
                r[3] == null ? "Plot " + r[4] : "Block " + r[3] + ", Plot " + r[4],
                (BigDecimal) r[5], Currency.valueOf((String) r[6]),
                "AVAILABLE_DEV".equals(r[7]) || "AVAILABLE_INV".equals(r[7]))).toList();
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
        // Checked before anything is written: if the plot is taken, nothing changes.
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
        return FinanceDecision.DONE;
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
                (BigDecimal) r[6], Currency.valueOf((String) r[7]), (Instant) r[8]);
    }

    private static PayableTransaction toPayable(Transaction t) {
        return new PayableTransaction(t.getId(), t.getReference(), t.getBuyerUserId(), t.getReservationId(),
                t.getPlotId(), t.getEstateId(), t.getSellerTenantId(), t.getTotalPrice(), t.getCurrency(),
                t.getPlan() == null ? null : t.getPlan().getValue(), t.getStatus().getValue());
    }
}
