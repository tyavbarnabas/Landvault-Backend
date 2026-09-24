package com.techcomfort.landvaultbackend.checkout.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.dto.CreateTransactionRequest;
import com.techcomfort.landvaultbackend.checkout.dto.TransactionDto;
import com.techcomfort.landvaultbackend.checkout.internal.domain.Reservation;
import com.techcomfort.landvaultbackend.checkout.internal.domain.Transaction;
import com.techcomfort.landvaultbackend.checkout.internal.enums.PaymentPlan;
import com.techcomfort.landvaultbackend.checkout.internal.enums.ReservationStatus;
import com.techcomfort.landvaultbackend.checkout.internal.enums.TransactionStatus;
import com.techcomfort.landvaultbackend.checkout.internal.exceptions.CheckoutException;
import com.techcomfort.landvaultbackend.checkout.internal.repository.ReservationRepository;
import com.techcomfort.landvaultbackend.checkout.internal.repository.TransactionRepository;
import com.techcomfort.landvaultbackend.common.PlotIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Opening a pending purchase against a hold.
 * <p>
 * Two rules do most of the work here, and both are about not lying to the
 * buyer: the price comes from the reservation rather than from the request
 * or a fresh read of the tier, and the transaction never advances past
 * pending.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    /**
     * Unambiguous when read down a phone line or typed into a bank
     * narration: no vowels, so a reference can never come out as a word, and
     * none of the pairs people transcribe wrongly — 0/O, 1/I/L, 5/S, 8/B.
     */
    private static final String REFERENCE_ALPHABET = "234679CDFGHJKMNPQRTVWXZ";
    private static final int REFERENCE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TransactionRepository transactionRepository;
    private final ReservationRepository reservationRepository;
    private final AuditApi auditApi;

    @Transactional
    public TransactionDto create(UUID buyerUserId, CreateTransactionRequest request) {
        Reservation reservation = reservationRepository
                .findByIdAndBuyerUserId(request.reservationId(), buyerUserId)
                .orElseThrow(CheckoutException.ReservationNotFound::new);

        if (reservation.getStatus() != ReservationStatus.ACTIVE) {
            throw new CheckoutException.ReservationNotActive(
                    "That reservation is " + reservation.getStatus().getValue() + ".");
        }
        // The sweep runs on an interval, so a hold can be past its time
        // without having been swept yet. The clock decides, not the sweeper:
        // accepting a lapsed hold here would let a buyer transact on a plot
        // that is, as far as everyone else is concerned, back on the market.
        if (reservation.getExpiresAt().isBefore(Instant.now())) {
            throw new CheckoutException.ReservationNotActive("That reservation has expired.");
        }

        transactionRepository.findByReservationId(reservation.getId()).ifPresent(existing -> {
            throw new CheckoutException.TransactionAlreadyExists();
        });

        PaymentPlan plan = PaymentPlan.fromValue(request.plan());
        validateInstallmentMonths(plan, request.installmentMonths());
        PlotIntent intent = PlotIntent.fromValue(request.intent());

        Transaction transaction = transactionRepository.save(Transaction.builder()
                .reference(generateReference())
                .buyerUserId(buyerUserId)
                .plotId(reservation.getPlotId())
                .estateId(reservation.getEstateId())
                .sellerTenantId(reservation.getSellerTenantId())
                .reservationId(reservation.getId())
                // Copied from the hold, never recomputed. If the developer
                // re-priced the tier since, that change does not reach a
                // buyer who already acted on the old figure.
                .basePrice(reservation.getBasePrice())
                .cornerPremiumPct(reservation.getCornerPremiumPct())
                .totalPrice(reservation.getTotalPrice())
                .currency(reservation.getCurrency())
                .intent(intent)
                .plan(plan)
                .installmentMonths(request.installmentMonths())
                // The only status this module writes. Reservation does not
                // allocate, and no payment signal is treated as confirmation
                // — a finance-role human does that, and finance is not built.
                .status(TransactionStatus.PENDING_PAYMENT)
                .build());

        log.info("Transaction {} opened by buyer {} against reservation {}",
                transaction.getReference(), buyerUserId, reservation.getId());
        auditApi.record(AuditEntryRequest.of(
                buyerUserId, "transaction.created", "transaction", transaction.getId(),
                reservation.getSellerTenantId(),
                "Pending purchase opened (" + plan.getValue() + ")"));

        return toDto(transaction);
    }

    /** A buyer's own transaction. Another buyer's is not found, not forbidden. */
    @Transactional(readOnly = true)
    public TransactionDto get(UUID buyerUserId, UUID transactionId) {
        return transactionRepository.findByIdAndBuyerUserId(transactionId, buyerUserId)
                .map(TransactionService::toDto)
                .orElseThrow(CheckoutException.TransactionNotFound::new);
    }

    /**
     * A month count without an installment plan has nothing to schedule, and
     * an installment plan without one cannot be scheduled. The database
     * enforces the same equivalence; this is what turns it into a 400 with
     * an explanation rather than a constraint violation.
     */
    private static void validateInstallmentMonths(PaymentPlan plan, Integer months) {
        if (plan == PaymentPlan.INSTALLMENT) {
            if (months == null || months <= 0) {
                throw new CheckoutException.InvalidPaymentPlan(
                        "An installment plan needs a positive number of months.");
            }
        } else if (months != null) {
            throw new CheckoutException.InvalidPaymentPlan(
                    "installmentMonths applies only to an installment plan.");
        }
    }

    private static String generateReference() {
        StringBuilder reference = new StringBuilder("LV-");
        for (int i = 0; i < REFERENCE_LENGTH; i++) {
            reference.append(REFERENCE_ALPHABET.charAt(RANDOM.nextInt(REFERENCE_ALPHABET.length())));
        }
        return reference.toString();
    }

    private static TransactionDto toDto(Transaction transaction) {
        return new TransactionDto(
                transaction.getId(),
                transaction.getReference(),
                transaction.getEstateId(),
                transaction.getPlotId(),
                transaction.getReservationId(),
                transaction.getBasePrice(),
                transaction.getCornerPremiumPct(),
                transaction.getTotalPrice(),
                transaction.getCurrency().name(),
                transaction.getIntent().getValue(),
                transaction.getPlan().getValue(),
                transaction.getInstallmentMonths(),
                transaction.getStatus().getValue(),
                transaction.getCreatedAt());
    }
}
