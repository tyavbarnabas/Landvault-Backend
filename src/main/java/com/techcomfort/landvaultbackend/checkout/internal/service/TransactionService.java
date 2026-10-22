package com.techcomfort.landvaultbackend.checkout.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.NewPurchase;
import com.techcomfort.landvaultbackend.checkout.OfferLetters;
import com.techcomfort.landvaultbackend.checkout.OfferSummary;
import com.techcomfort.landvaultbackend.checkout.PaymentSummary;
import com.techcomfort.landvaultbackend.checkout.PaymentSummaryProvider;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.checkout.dto.CreateTransactionRequest;
import com.techcomfort.landvaultbackend.checkout.dto.TransactionDto;
import com.techcomfort.landvaultbackend.checkout.internal.domain.Reservation;
import com.techcomfort.landvaultbackend.checkout.internal.domain.Transaction;
import com.techcomfort.landvaultbackend.checkout.internal.domain.TransactionInstalment;
import com.techcomfort.landvaultbackend.checkout.internal.enums.PaymentPlan;
import com.techcomfort.landvaultbackend.checkout.internal.enums.ReservationStatus;
import com.techcomfort.landvaultbackend.checkout.internal.enums.TransactionStatus;
import com.techcomfort.landvaultbackend.checkout.internal.exceptions.CheckoutException;
import com.techcomfort.landvaultbackend.checkout.internal.repository.ReservationRepository;
import com.techcomfort.landvaultbackend.checkout.internal.repository.TransactionInstalmentRepository;
import com.techcomfort.landvaultbackend.checkout.internal.repository.TransactionRepository;
import com.techcomfort.landvaultbackend.common.InstalmentPlanCalculator;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.common.PlotIntent;
import com.techcomfort.landvaultbackend.common.PostalAddress;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.marketplace.MarketplaceApi;
import com.techcomfort.landvaultbackend.marketplace.OfferedPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final TransactionRepository transactionRepository;
    private final ReservationRepository reservationRepository;
    private final AuditApi auditApi;
    /** Implemented by the payments module; see the interface for why checkout doesn't read payments itself. */
    private final PaymentSummaryProvider paymentSummaries;
    private final IdentityApi identityApi;
    /** Implemented by documents; see the interface for why checkout doesn't issue letters itself. */
    private final OfferLetters offerLetters;
    private final TransactionInstalmentRepository instalmentRepository;
    private final MarketplaceApi marketplaceApi;

    @Transactional
    public TransactionDto create(UUID buyerUserId, CreateTransactionRequest request) {
        Reservation reservation = reservationRepository
                // Locked: the sweeper and a cancel lock the same row before
                // ending a hold, so the hold cannot be released between the
                // checks below and this transaction committing.
                .findLockedByIdAndBuyerUserId(request.reservationId(), buyerUserId)
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

        // Copied now and never again: changing the account's address later
        // doesn't rewrite what this purchase, or its letter, says.
        PostalAddress address = identityApi.addressOf(buyerUserId)
                .orElseThrow(CheckoutException.AddressRequired::new);

        PaymentPlan plan = PaymentPlan.fromValue(request.plan());
        PlotIntent intent = PlotIntent.fromValue(request.intent());
        // IC-1: an instalment purchase names one of the estate's declared plans,
        // worked out now from the agreed price, with due dates counted from the
        // offer date (today, in Lagos) — fixed here and never recomputed.
        Optional<ChosenPlan> chosen = choosePlan(plan, request, reservation);

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
                .installmentMonths(chosen.map(c -> c.quote().instalmentCount()).orElse(null))
                .planId(chosen.map(c -> c.offered().planId()).orElse(null))
                .planName(chosen.map(c -> c.offered().name()).orElse(null))
                .planFrequency(chosen.map(c -> c.quote().frequency()).orElse(null))
                .planTotal(chosen.map(c -> c.quote().planTotal()).orElse(null))
                .planPremium(chosen.map(c -> c.quote().premium()).orElse(null))
                .depositAmount(chosen.map(c -> c.quote().deposit()).orElse(null))
                .buyerAddressLine1(address.line1())
                .buyerAddressLine2(address.line2())
                .buyerAddressCity(address.city())
                .buyerAddressState(address.state())
                .buyerAddressPostalCode(address.postalCode())
                .buyerAddressCountry(address.country())
                // The only status this module writes. Reservation does not
                // allocate, and no payment signal is treated as confirmation
                // — a finance-role human does that, and finance is not built.
                .status(TransactionStatus.PENDING_PAYMENT)
                .build());

        Optional<PlanSchedule> schedule = chosen.map(c -> saveSchedule(transaction, c));

        // OL: the offer letter is issued in the same step, from the estate's
        // approved letter setup — so a purchase never exists without the terms
        // it was offered on. No approved, current setup: nothing is opened.
        OfferSummary offer = offerLetters.issue(new NewPurchase(transaction.getId(), buyerUserId,
                        transaction.getPlotId(), transaction.getEstateId(), transaction.getSellerTenantId(),
                        transaction.getBasePrice(), transaction.getCornerPremiumPct(), transaction.getTotalPrice(),
                        transaction.getCurrency(), reservation.getCreatedAt(), address, schedule.orElse(null)))
                .orElseThrow(CheckoutException.LetterNotApproved::new);

        log.info("Transaction {} opened by buyer {} against reservation {}",
                transaction.getReference(), buyerUserId, reservation.getId());
        auditApi.record(AuditEntryRequest.of(
                buyerUserId, "transaction.created", "transaction", transaction.getId(),
                reservation.getSellerTenantId(),
                "Pending purchase opened (" + plan.getValue() + ")"));

        // Nothing paid yet on a purchase opened this instant.
        return toDto(transaction, null, offer, schedule);
    }

    /** A buyer's own transaction. Another buyer's is not found, not forbidden. */
    @Transactional(readOnly = true)
    public TransactionDto get(UUID buyerUserId, UUID transactionId) {
        return transactionRepository.findByIdAndBuyerUserId(transactionId, buyerUserId)
                .map(this::withPayment)
                .orElseThrow(CheckoutException.TransactionNotFound::new);
    }

    /** The buyer's own purchases, newest first, each with its payment summary (batched: one call per page). */
    @Transactional(readOnly = true)
    public PageResponse<TransactionDto> mine(UUID buyerUserId, Pageable pageable) {
        Page<Transaction> page = transactionRepository.findAllByBuyerUserIdOrderByCreatedAtDesc(buyerUserId, pageable);
        List<UUID> ids = page.getContent().stream().map(Transaction::getId).toList();
        Map<UUID, PaymentSummary> summaries = paymentSummaries.summariesFor(ids);
        Map<UUID, OfferSummary> offers = offerLetters.forTransactions(ids);
        Map<UUID, List<TransactionInstalment>> instalments = instalmentsFor(ids);
        return PageResponses.from(page, t -> toDto(t, summaries.get(t.getId()), offers.get(t.getId()),
                schedule(t, instalments.getOrDefault(t.getId(), List.of()))));
    }

    private TransactionDto withPayment(Transaction transaction) {
        List<UUID> id = List.of(transaction.getId());
        return toDto(transaction, paymentSummaries.summariesFor(id).get(transaction.getId()),
                offerLetters.forTransactions(id).get(transaction.getId()),
                schedule(transaction, instalmentsFor(id).getOrDefault(transaction.getId(), List.of())));
    }

    /** The plan chosen at Proceed to buy, as published, and its figures at the agreed price. */
    private record ChosenPlan(OfferedPlan offered, InstalmentPlanCalculator.Quote quote) {
    }

    /**
     * IC-1. Outright and milestone carry no plan; an instalment purchase must
     * name one the estate offers now, at this plot's agreed price. The month
     * count comes from the plan, never the buyer.
     */
    private Optional<ChosenPlan> choosePlan(PaymentPlan plan, CreateTransactionRequest request,
                                            Reservation reservation) {
        if (request.installmentMonths() != null) {
            throw new CheckoutException.InvalidPaymentPlan(
                    "installmentMonths comes from the plan you choose; leave it out and send planId.");
        }
        if (plan != PaymentPlan.INSTALLMENT) {
            if (request.planId() != null) {
                throw new CheckoutException.InvalidPaymentPlan("planId applies only to an installment plan.");
            }
            return Optional.empty();
        }
        if (request.planId() == null) {
            throw new CheckoutException.InvalidPaymentPlan(
                    "Choose one of the estate's instalment plans (planId).");
        }
        OfferedPlan offered = marketplaceApi.offeredPlan(reservation.getEstateId(), request.planId())
                .orElseThrow(() -> new CheckoutException.PlanNotOffered(
                        "This estate doesn't offer that plan now. Choose one of its current plans."));
        InstalmentPlanCalculator.Quote quote = InstalmentPlanCalculator.quote(reservation.getTotalPrice(),
                        offered.terms())
                .orElseThrow(() -> new CheckoutException.PlanNotOffered(
                        "That plan isn't available at this plot's price."));
        return Optional.of(new ChosenPlan(offered, quote));
    }

    /** The schedule, with due dates counted from today in Lagos — the offer's date. */
    private PlanSchedule saveSchedule(Transaction transaction, ChosenPlan chosen) {
        List<BigDecimal> amounts = chosen.quote().instalments();
        List<LocalDate> dates = InstalmentPlanCalculator.dueDates(LocalDate.now(LAGOS), chosen.quote().frequency(),
                amounts.size());
        List<TransactionInstalment> rows = new ArrayList<>();
        for (int i = 0; i < amounts.size(); i++) {
            rows.add(TransactionInstalment.builder()
                    .transactionId(transaction.getId())
                    .scheduleVersion(1)
                    .sequence(i + 1)
                    .dueDate(dates.get(i))
                    .amount(amounts.get(i))
                    .build());
        }
        return schedule(transaction, instalmentRepository.saveAll(rows)).orElseThrow();
    }

    private Map<UUID, List<TransactionInstalment>> instalmentsFor(List<UUID> transactionIds) {
        return transactionIds.isEmpty() ? Map.of()
                : instalmentRepository.findByTransactionIdInOrderBySequenceAsc(transactionIds).stream()
                .collect(Collectors.groupingBy(TransactionInstalment::getTransactionId));
    }

    private static Optional<PlanSchedule> schedule(Transaction t, List<TransactionInstalment> instalments) {
        if (t.getPlanId() == null) {
            return Optional.empty();
        }
        return Optional.of(new PlanSchedule(t.getPlanId(), t.getPlanName(), t.getPlanFrequency().getValue(),
                t.getPlanTotal(), t.getPlanPremium(), t.getDepositAmount(), instalments.stream()
                .sorted(Comparator.comparing(TransactionInstalment::getSequence))
                .map(i -> new PlanSchedule.Instalment(i.getSequence(), i.getDueDate(), i.getAmount()))
                .toList()));
    }

    private static String generateReference() {
        StringBuilder reference = new StringBuilder("LV-");
        for (int i = 0; i < REFERENCE_LENGTH; i++) {
            reference.append(REFERENCE_ALPHABET.charAt(RANDOM.nextInt(REFERENCE_ALPHABET.length())));
        }
        return reference.toString();
    }

    private static TransactionDto toDto(Transaction transaction, PaymentSummary payment, OfferSummary offer,
                                        Optional<PlanSchedule> schedule) {
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
                transaction.getCreatedAt(),
                payment,
                transaction.buyerAddress().orElse(null),
                offer,
                schedule.orElse(null));
    }
}
