package com.techcomfort.landvaultbackend.checkout.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.FlexiblePurchase;
import com.techcomfort.landvaultbackend.checkout.FlexibleStanding;
import com.techcomfort.landvaultbackend.checkout.NewPurchase;
import com.techcomfort.landvaultbackend.checkout.OfferLetters;
import com.techcomfort.landvaultbackend.checkout.OfferSummary;
import com.techcomfort.landvaultbackend.checkout.PaymentSummary;
import com.techcomfort.landvaultbackend.checkout.PaymentSummaryProvider;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.checkout.PlanStanding;
import com.techcomfort.landvaultbackend.checkout.dto.CreateTransactionRequest;
import com.techcomfort.landvaultbackend.checkout.dto.TransactionDto;
import com.techcomfort.landvaultbackend.checkout.internal.domain.Reservation;
import com.techcomfort.landvaultbackend.checkout.internal.domain.Transaction;
import com.techcomfort.landvaultbackend.checkout.internal.domain.TransactionInstalment;
import com.techcomfort.landvaultbackend.checkout.internal.domain.TransactionPenaltyStep;
import com.techcomfort.landvaultbackend.checkout.internal.enums.PaymentPlan;
import com.techcomfort.landvaultbackend.checkout.internal.enums.ReservationStatus;
import com.techcomfort.landvaultbackend.checkout.internal.enums.TransactionStatus;
import com.techcomfort.landvaultbackend.checkout.internal.exceptions.CheckoutException;
import com.techcomfort.landvaultbackend.checkout.internal.repository.ReservationRepository;
import com.techcomfort.landvaultbackend.checkout.internal.repository.TransactionInstalmentRepository;
import com.techcomfort.landvaultbackend.checkout.internal.repository.TransactionPenaltyStepRepository;
import com.techcomfort.landvaultbackend.checkout.internal.repository.TransactionRepository;
import com.techcomfort.landvaultbackend.common.FlexiblePlanCalculator;
import com.techcomfort.landvaultbackend.common.InstalmentPlanCalculator;
import com.techcomfort.landvaultbackend.common.LatePaymentTerms;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.common.PlotIntent;
import com.techcomfort.landvaultbackend.common.PostalAddress;
import com.techcomfort.landvaultbackend.common.RevocationTerms;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.marketplace.MarketplaceApi;
import com.techcomfort.landvaultbackend.marketplace.OfferedFlexiblePlan;
import com.techcomfort.landvaultbackend.marketplace.OfferedPlan;
import com.techcomfort.landvaultbackend.marketplace.OfferedRefundTerms;
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
import java.util.HashMap;
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
    private final TransactionPenaltyStepRepository penaltyStepRepository;

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
        // Sell my plot (D14): a resale is always paid in full — nobody's ownership hangs on a second plan.
        if (reservation.isResale() && plan != PaymentPlan.OUTRIGHT) {
            throw new CheckoutException.PlanNotOffered("A resale plot is paid in full; no instalment or flexible plan "
                    + "is offered on it.");
        }
        // IC-1: an instalment purchase names one of the estate's declared plans,
        // worked out now from the agreed price, with due dates counted from the
        // offer date (today, in Lagos) — fixed here and never recomputed.
        Optional<ChosenPlan> chosen = choosePlan(plan, request, reservation);
        // Flexible contribution, phase 2: no schedule, the plan's terms copied
        // as offered now — a later redeclaration reaches new buyers only.
        Optional<ChosenFlexible> flexible = plan == PaymentPlan.FLEXIBLE
                ? Optional.of(chooseFlexible(request, reservation)) : Optional.empty();
        Optional<FlexiblePlanCalculator.Terms> flexTerms = flexible.map(f -> f.offered().terms());

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
                .planId(chosen.map(c -> c.offered().planId())
                        .or(() -> flexible.map(f -> f.offered().planId())).orElse(null))
                .planName(chosen.map(c -> c.offered().name()).or(() -> flexible.map(f -> f.offered().name()))
                        .orElse(null))
                .planFrequency(chosen.map(c -> c.quote().frequency()).orElse(null))
                .planTotal(chosen.map(c -> c.quote().planTotal()).or(() -> flexible.map(f -> f.quote().planTotal()))
                        .orElse(null))
                .planPremium(chosen.map(c -> c.quote().premium()).or(() -> flexible.map(f -> f.quote().premium()))
                        .orElse(null))
                .depositAmount(chosen.map(c -> c.quote().deposit()).orElse(null))
                // 3c: the withdrawal deduction the buyer is offered now decides
                // the developer's share of each payment; fixed like the price.
                // A flexible plan declares its own exit split, used the same way.
                .escrowDeductionPct(chosen.flatMap(ChosenPlan::refundTerms).map(OfferedRefundTerms::deductionPct)
                        .or(() -> flexTerms.map(FlexiblePlanCalculator.Terms::exitDeductionPct)).orElse(null))
                .escrowAppliesTo(chosen.flatMap(ChosenPlan::refundTerms).map(OfferedRefundTerms::appliesTo)
                        .or(() -> flexTerms.map(FlexiblePlanCalculator.Terms::exitAppliesTo)).orElse(null))
                .flexibleMinimumToStart(flexible.map(f -> f.quote().minimumToStart()).orElse(null))
                .flexibleMinimumContribution(flexTerms.map(FlexiblePlanCalculator.Terms::minimumContribution)
                        .orElse(null))
                .flexibleGainSharePct(flexTerms.map(FlexiblePlanCalculator.Terms::developerGainSharePct).orElse(null))
                .flexibleInactivityMonths(flexTerms.map(FlexiblePlanCalculator.Terms::inactivityMonths).orElse(null))
                .flexibleNoticeDays(flexTerms.map(FlexiblePlanCalculator.Terms::noticeDays).orElse(null))
                .flexibleMaxMonths(flexTerms.map(FlexiblePlanCalculator.Terms::maxMonths).orElse(null))
                // Counted from the offer date, like an instalment plan's due dates.
                .flexibleFinishBy(flexTerms.map(FlexiblePlanCalculator.Terms::maxMonths)
                        .map(months -> LocalDate.now(LAGOS).plusMonths(months)).orElse(null))
                // Phase 4: whether penalties are refunded, fixed like the price.
                .penaltiesRefundable(chosen.map(c -> c.lateTerms().penaltiesRefundable()).orElse(null))
                // Phase 5b: when the plan may be revoked, and the notice, fixed like the price.
                .revocationAfterMonths(chosen.map(c -> c.lateTerms().revocation()).map(RevocationTerms::afterMonths)
                        .orElse(null))
                .revocationNoticeDays(chosen.map(c -> c.lateTerms().revocation()).map(RevocationTerms::noticeDays)
                        .orElse(null))
                .buyerAddressLine1(address.line1())
                .buyerAddressLine2(address.line2())
                .buyerAddressCity(address.city())
                .buyerAddressState(address.state())
                .buyerAddressPostalCode(address.postalCode())
                .buyerAddressCountry(address.country())
                .resaleListingId(reservation.getResaleListingId())
                .resaleOfTransactionId(reservation.getResaleOfTransactionId())
                .resaleDeveloperReceives(reservation.getResaleDeveloperReceives())
                .resaleSellerReceives(reservation.getResaleSellerReceives())
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
                        transaction.getCurrency(), reservation.getCreatedAt(), address, schedule.orElse(null),
                        transaction.flexiblePlan().orElse(null), resaleOf(reservation)))
                .orElseThrow(CheckoutException.LetterNotApproved::new);

        log.info("Transaction {} opened by buyer {} against reservation {}",
                transaction.getReference(), buyerUserId, reservation.getId());
        auditApi.record(AuditEntryRequest.of(
                buyerUserId, "transaction.created", "transaction", transaction.getId(),
                reservation.getSellerTenantId(),
                "Pending purchase opened (" + plan.getValue() + ")"));

        // Nothing paid yet on a purchase opened this instant.
        return toDto(transaction, null, offer, schedule, null, null);
    }

    /** Sell my plot: who a resale hold buys the plot from, for its offer letter; null on any other hold. */
    private NewPurchase.Resale resaleOf(Reservation reservation) {
        if (!reservation.isResale()) {
            return null;
        }
        Transaction from = transactionRepository.findById(reservation.getResaleOfTransactionId()).orElseThrow();
        return new NewPurchase.Resale(from.getId(), from.getBuyerUserId(),
                Boolean.TRUE.equals(reservation.getResaleConsented()));
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
        Map<UUID, PlanSchedule> schedules = schedulesOf(page.getContent());
        Map<UUID, PlanStanding> standings = standingsOf(page.getContent(), schedules);
        Map<UUID, FlexibleStanding> flexible = flexibleStandingsOf(page.getContent());
        return PageResponses.from(page, t -> toDto(t, summaries.get(t.getId()), offers.get(t.getId()),
                Optional.ofNullable(schedules.get(t.getId())), standings.get(t.getId()), flexible.get(t.getId())));
    }

    private TransactionDto withPayment(Transaction transaction) {
        List<UUID> id = List.of(transaction.getId());
        Map<UUID, PlanSchedule> schedules = schedulesOf(List.of(transaction));
        return toDto(transaction, paymentSummaries.summariesFor(id).get(transaction.getId()),
                offerLetters.forTransactions(id).get(transaction.getId()),
                Optional.ofNullable(schedules.get(transaction.getId())),
                standingsOf(List.of(transaction), schedules).get(transaction.getId()),
                flexibleStandingsOf(List.of(transaction)).get(transaction.getId()));
    }

    /** Flexible contribution, phase 4: only while the plan runs. */
    private Map<UUID, FlexibleStanding> flexibleStandingsOf(List<Transaction> transactions) {
        Map<UUID, FlexiblePurchase> running = new HashMap<>();
        transactions.stream().filter(t -> t.getStatus() == TransactionStatus.ON_PLAN)
                .forEach(t -> t.flexiblePlan().ifPresent(f -> running.put(t.getId(), f)));
        return running.isEmpty() ? Map.of() : paymentSummaries.flexibleStandingsFor(running);
    }

    /** Phase 4: only once the plot is held on the plan (or paid off) — before that nothing is owed yet. */
    private Map<UUID, PlanStanding> standingsOf(List<Transaction> transactions, Map<UUID, PlanSchedule> schedules) {
        Map<UUID, PlanSchedule> running = new HashMap<>();
        transactions.stream()
                .filter(t -> t.getStatus() == TransactionStatus.ON_PLAN || t.getStatus() == TransactionStatus.VERIFIED)
                .filter(t -> schedules.containsKey(t.getId()))
                .forEach(t -> running.put(t.getId(), schedules.get(t.getId())));
        return running.isEmpty() ? Map.of() : paymentSummaries.standingsFor(running);
    }

    /** The plan chosen at Proceed to buy, as published, and its figures at the agreed price. */
    private record ChosenPlan(OfferedPlan offered, InstalmentPlanCalculator.Quote quote,
                              Optional<OfferedRefundTerms> refundTerms, LatePaymentTerms lateTerms) {
    }

    /** The flexible plan chosen at Proceed to buy, as published, and its figures at the agreed price. */
    private record ChosenFlexible(OfferedFlexiblePlan offered, FlexiblePlanCalculator.Quote quote) {
    }

    /**
     * IC-1. Outright and milestone carry no plan; an instalment purchase must
     * name one the estate offers now, at this plot's agreed price. The month
     * count comes from the plan, never the buyer. A flexible purchase is
     * {@link #chooseFlexible}'s; here it only passes the shared checks.
     */
    private Optional<ChosenPlan> choosePlan(PaymentPlan plan, CreateTransactionRequest request,
                                            Reservation reservation) {
        if (request.installmentMonths() != null) {
            throw new CheckoutException.InvalidPaymentPlan(
                    "installmentMonths comes from the plan you choose; leave it out and send planId.");
        }
        if (plan == PaymentPlan.FLEXIBLE) {
            return Optional.empty();
        }
        if (plan != PaymentPlan.INSTALLMENT) {
            if (request.planId() != null) {
                throw new CheckoutException.InvalidPaymentPlan(
                        "planId applies only to an installment or flexible plan.");
            }
            return Optional.empty();
        }
        if (request.planId() == null) {
            throw new CheckoutException.InvalidPaymentPlan(
                    "Choose one of the estate's instalment plans (planId).");
        }
        OfferedPlan offered = marketplaceApi.offeredPlan(reservation.getEstateId(), request.planId())
                .orElseThrow(() -> new CheckoutException.PlanNotOffered(
                        marketplaceApi.offeredFlexiblePlan(reservation.getEstateId(), request.planId()).isPresent()
                                ? "That is a flexible plan: send plan \"flexible\" to choose it."
                                : "This estate doesn't offer that plan now. Choose one of its current plans."));
        InstalmentPlanCalculator.Quote quote = InstalmentPlanCalculator.quote(reservation.getTotalPrice(),
                        offered.terms())
                .orElseThrow(() -> new CheckoutException.PlanNotOffered(
                        "That plan isn't available at this plot's price."));
        return Optional.of(new ChosenPlan(offered, quote, marketplaceApi.refundTermsOf(reservation.getEstateId()),
                marketplaceApi.latePaymentTermsOf(reservation.getEstateId()).orElse(LatePaymentTerms.NONE)));
    }

    /** Flexible contribution, phase 2: one of the estate's current flexible plans, at the agreed price. */
    private ChosenFlexible chooseFlexible(CreateTransactionRequest request, Reservation reservation) {
        if (request.planId() == null) {
            throw new CheckoutException.InvalidPaymentPlan("Choose one of the estate's flexible plans (planId).");
        }
        OfferedFlexiblePlan offered = marketplaceApi.offeredFlexiblePlan(reservation.getEstateId(), request.planId())
                .orElseThrow(() -> new CheckoutException.PlanNotOffered(
                        marketplaceApi.offeredPlan(reservation.getEstateId(), request.planId()).isPresent()
                                ? "That is an instalment plan: send plan \"installment\" to choose it."
                                : "This estate doesn't offer that plan now. Choose one of its current plans."));
        // The agreed price, corner premium included — the same figure an instalment plan is worked out from.
        return new ChosenFlexible(offered, FlexiblePlanCalculator.quote(reservation.getTotalPrice(), offered.terms()));
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
        // Phase 4: the late-payment steps offered now, copied and never changed.
        penaltyStepRepository.saveAll(chosen.lateTerms().steps().stream()
                .map(step -> TransactionPenaltyStep.builder()
                        .transactionId(transaction.getId())
                        .monthsLate(step.monthsLate())
                        .penaltyPct(step.penaltyPct())
                        .build())
                .toList());
        return schedule(transaction, instalmentRepository.saveAll(rows), chosen.lateTerms().steps()).orElseThrow();
    }

    /** Plan and schedule for each instalment purchase in {@code transactions}, in one query. */
    Map<UUID, PlanSchedule> schedulesOf(List<Transaction> transactions) {
        // Instalment purchases only: a flexible plan has a plan id but no schedule.
        List<UUID> ids = transactions.stream().filter(t -> t.getPlan() == PaymentPlan.INSTALLMENT
                && t.getPlanId() != null).map(Transaction::getId).toList();
        Map<UUID, List<TransactionInstalment>> instalments = instalmentsFor(ids);
        Map<UUID, List<LatePaymentTerms.Step>> steps = stepsFor(ids);
        Map<UUID, List<PlanSchedule.Pause>> holds = resaleHoldsOf(ids);
        Map<UUID, PlanSchedule> out = new HashMap<>();
        // Phase 5c: only the version in force; earlier ones are history.
        transactions.forEach(t -> schedule(t, instalments.getOrDefault(t.getId(), List.of()).stream()
                        .filter(i -> i.getScheduleVersion() == t.currentScheduleVersion()).toList(),
                steps.getOrDefault(t.getId(), List.of()))
                .map(s -> holds.containsKey(t.getId()) ? new PlanSchedule(s.planId(), s.planName(), s.frequency(),
                        s.planTotal(), s.premium(), s.deposit(), s.instalments(), s.lateTerms(), s.version(),
                        holds.get(t.getId())) : s)
                .ifPresent(s -> out.put(t.getId(), s)));
        return out;
    }

    /**
     * Sell my plot (D25): every hold a new buyer has had on these purchases'
     * plots, as Lagos days — from the day it was taken up to (not including)
     * the day it ended, or still running.
     */
    private Map<UUID, List<PlanSchedule.Pause>> resaleHoldsOf(List<UUID> transactionIds) {
        return transactionIds.isEmpty() ? Map.of()
                : reservationRepository.findByResaleOfTransactionIdIn(transactionIds).stream()
                .sorted(Comparator.comparing(Reservation::getCreatedAt))
                .collect(Collectors.groupingBy(Reservation::getResaleOfTransactionId, Collectors.mapping(
                        r -> new PlanSchedule.Pause(LocalDate.ofInstant(r.getCreatedAt(), LAGOS),
                                r.getEndedAt() == null ? null : LocalDate.ofInstant(r.getEndedAt(), LAGOS)),
                        Collectors.toList())));
    }

    private Map<UUID, List<TransactionInstalment>> instalmentsFor(List<UUID> transactionIds) {
        return transactionIds.isEmpty() ? Map.of()
                : instalmentRepository.findByTransactionIdInOrderBySequenceAsc(transactionIds).stream()
                .collect(Collectors.groupingBy(TransactionInstalment::getTransactionId));
    }

    private Map<UUID, List<LatePaymentTerms.Step>> stepsFor(List<UUID> transactionIds) {
        return transactionIds.isEmpty() ? Map.of()
                : penaltyStepRepository.findByTransactionIdIn(transactionIds).stream()
                .collect(Collectors.groupingBy(TransactionPenaltyStep::getTransactionId, Collectors.mapping(
                        s -> new LatePaymentTerms.Step(s.getMonthsLate(), s.getPenaltyPct()), Collectors.toList())));
    }

    private static Optional<PlanSchedule> schedule(Transaction t, List<TransactionInstalment> instalments,
                                                   List<LatePaymentTerms.Step> steps) {
        if (t.getPlan() != PaymentPlan.INSTALLMENT || t.getPlanId() == null) {
            return Optional.empty();
        }
        return Optional.of(new PlanSchedule(t.getPlanId(), t.getPlanName(), t.getPlanFrequency().getValue(),
                t.getPlanTotal(), t.getPlanPremium(), t.getDepositAmount(), instalments.stream()
                .sorted(Comparator.comparing(TransactionInstalment::getSequence))
                .map(i -> new PlanSchedule.Instalment(i.getSequence(), i.getDueDate(), i.getAmount(),
                        i.getCarriedPenalty()))
                .toList(), new LatePaymentTerms(steps, !Boolean.FALSE.equals(t.getPenaltiesRefundable()),
                t.revocationTerms()), t.currentScheduleVersion()));
    }

    private static String generateReference() {
        StringBuilder reference = new StringBuilder("LV-");
        for (int i = 0; i < REFERENCE_LENGTH; i++) {
            reference.append(REFERENCE_ALPHABET.charAt(RANDOM.nextInt(REFERENCE_ALPHABET.length())));
        }
        return reference.toString();
    }

    private static TransactionDto toDto(Transaction transaction, PaymentSummary payment, OfferSummary offer,
                                        Optional<PlanSchedule> schedule, PlanStanding standing,
                                        FlexibleStanding flexibleStanding) {
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
                schedule.orElse(null),
                transaction.flexiblePlan().orElse(null),
                flexibleStanding,
                standing,
                transaction.getWithdrawnAt(),
                transaction.getRevokedAt(),
                transaction.getResaleListingId());
    }
}
