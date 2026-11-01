package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.FinanceQueueRow;
import com.techcomfort.landvaultbackend.checkout.PayableTransaction;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.common.InstalmentPlanCalculator;
import com.techcomfort.landvaultbackend.common.LatePaymentTerms;
import com.techcomfort.landvaultbackend.common.PaymentFrequency;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.RestructureLetterRequest;
import com.techcomfort.landvaultbackend.payments.RestructureLetters;
import com.techcomfort.landvaultbackend.payments.dto.RestructureDto;
import com.techcomfort.landvaultbackend.payments.dto.RestructureQueueItemDto;
import com.techcomfort.landvaultbackend.payments.internal.domain.PlanRestructure;
import com.techcomfort.landvaultbackend.payments.internal.domain.PlanRestructureInstalment;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.enums.RestructureStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PlanRestructureInstalmentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PlanRestructureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 5c: restructuring an instalment plan (decided with the user,
 * 2026-10-23: A, A, A, A). The buyer asks; the developer's finance proposes
 * new terms for what is still owed — HomeTrue works out the figures, the total
 * never changes, penalties already reached are kept and charged with the first
 * new instalment — or declines; the buyer accepts the restructure letter
 * within 14 days or it lapses. Accepting puts a new schedule version in force:
 * the paid instalments as they were, then the new ones, numbering carried on.
 * See AGENTS.md, "Instalment plans", phase 5c.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestructureService {

    /** How long a buyer has to accept a proposal (decision 4). */
    static final int PROPOSAL_DAYS = 14;
    /** A lapse reminder goes this many days before the last day. */
    static final int LAPSE_REMINDER_DAYS = 2;
    /** Instalments in all, paid and new (decision 2). */
    static final int MAX_INSTALMENTS = 60;
    /** The first new instalment falls within this many months (decision 2). */
    static final int LATEST_START_MONTHS = 3;
    private static final int USER_AGENT_MAX = 255;

    private final CheckoutApi checkoutApi;
    private final PlanStandings planStandings;
    private final PlanRestructureRepository restructures;
    private final PlanRestructureInstalmentRepository proposalRows;
    private final PaymentRepository payments;
    private final RestructureLetters letters;
    private final RestructureNotices notices;
    private final RevocationNotices revocationNotices;
    private final AuditApi auditApi;
    private final IdentityApi identityApi;
    private final SettlementAlertSender alerts;
    private final BuyerNotifier buyerNotifier;

    // ------------------------------------------------------------------ the buyer

    @Transactional(readOnly = true)
    public RestructureDto forBuyer(UUID transactionId) {
        return view(ownPurchase(transactionId));
    }

    @Transactional
    public RestructureDto request(UUID transactionId, String reason, Integer preferredInstalments) {
        PayableTransaction purchase = ownPurchase(transactionId);
        if (checkoutApi.isHeldForResale(transactionId)) {
            throw new PaymentException.PlotHeldForResale();
        }
        PlanSchedule schedule = requireRunning(purchase);
        restructures.findOpenLocked(transactionId).ifPresent(r -> {
            throw new PaymentException.RestructureAlreadyOpen(r.getStatus().name());
        });
        PlanRestructure r = restructures.saveAndFlush(PlanRestructure.builder()
                .transactionId(transactionId)
                .sellerTenantId(purchase.sellerTenantId())
                .buyerUserId(purchase.buyerUserId())
                .status(RestructureStatus.REQUESTED)
                .requestReason(reason.trim())
                .preferredInstalments(preferredInstalments)
                .requestedAt(Instant.now())
                .transactionReference(purchase.reference())
                .planName(schedule.planName())
                .build());
        auditApi.record(AuditEntryRequest.of(purchase.buyerUserId(), "plan.restructure_requested", "plan_restructure",
                r.getId(), purchase.sellerTenantId(), "Buyer asked to restructure " + purchase.reference()
                        + (preferredInstalments == null ? "" : " over " + preferredInstalments + " instalments")
                        + ". Reason: " + r.getRequestReason()));
        log.info("Restructure {} requested on plan {}", r.getId(), transactionId);
        tellDevelopers(r, SettlementAlertSender.RestructureStage.REQUESTED, null, r.getRequestReason());
        return view(purchase);
    }

    @Transactional
    public RestructureDto cancel(UUID transactionId) {
        PayableTransaction purchase = ownPurchase(transactionId);
        PlanRestructure r = open(transactionId, RestructureStatus.REQUESTED, "request waiting for the developer");
        r.close(RestructureStatus.CANCELLED, null, "Withdrawn by the buyer.");
        restructures.saveAndFlush(r);
        auditApi.record(AuditEntryRequest.of(purchase.buyerUserId(), "plan.restructure_cancelled", "plan_restructure",
                r.getId(), purchase.sellerTenantId(), "Buyer withdrew their restructure request on "
                        + purchase.reference() + "."));
        return view(purchase);
    }

    @Transactional
    public RestructureDto decline(UUID transactionId, UUID restructureId) {
        PayableTransaction purchase = ownPurchase(transactionId);
        PlanRestructure r = proposal(transactionId, restructureId);
        r.close(RestructureStatus.DECLINED, null, "Declined by the buyer.");
        restructures.saveAndFlush(r);
        auditApi.record(AuditEntryRequest.of(purchase.buyerUserId(), "plan.restructure_declined", "plan_restructure",
                r.getId(), purchase.sellerTenantId(), "Buyer declined restructure letter " + r.getLetterNumber()
                        + " on " + purchase.reference() + "; the current schedule carries on."));
        tellDevelopers(r, SettlementAlertSender.RestructureStage.DECLINED, null, null);
        return view(purchase);
    }

    /**
     * Accepting the proposal they name puts it in force, exactly as the letter
     * states — but only while it still describes the plan: within its 14 days,
     * on the schedule version it was worked out on, with nothing paid or
     * waiting for finance since.
     */
    @Transactional
    public RestructureDto accept(UUID transactionId, UUID restructureId, String userAgent) {
        PayableTransaction purchase = ownPurchase(transactionId);
        if (checkoutApi.isHeldForResale(transactionId)) {
            throw new PaymentException.PlotHeldForResale();
        }
        PlanSchedule schedule = requireRunning(purchase);
        // The payments locked first — the order finance verifying one takes.
        payments.findLockedByTransactionIdAndStatus(transactionId, PaymentStatus.SUCCEEDED);
        PlanRestructure r = proposal(transactionId, restructureId);
        LocalDate today = PlanStandings.today();
        if (today.isAfter(r.getExpiresOn())) {
            throw new PaymentException.RestructureLapsed(r.getExpiresOn());
        }
        PlanProgress progress = progress(purchase.id(), schedule);
        if (progress.awaitingFinance().isPresent()) {
            throw new PaymentException.PaymentAwaitingFinance();
        }
        if (schedule.version() != r.getFromScheduleVersion() || progress.balance().compareTo(r.getBalance()) != 0) {
            throw new PaymentException.RestructureOutOfDate();
        }
        Set<Integer> unpaid = progress.remaining().stream().map(PlanSchedule.Instalment::sequence)
                .collect(Collectors.toSet());
        List<PlanSchedule.Instalment> full = new ArrayList<>(schedule.instalments().stream()
                .filter(i -> !unpaid.contains(i.sequence())).toList());
        List<PlanSchedule.Instalment> fresh = newInstalments(r);
        full.addAll(fresh);
        if (!checkoutApi.restructure(transactionId, purchase.buyerUserId(), r.getFromScheduleVersion(), full)) {
            throw new PaymentException.RestructureOutOfDate();
        }
        r.close(RestructureStatus.ACCEPTED, null, null);
        r.setAcceptedAt(Instant.now());
        r.setAcceptedUserAgent(userAgent == null ? null
                : userAgent.substring(0, Math.min(userAgent.length(), USER_AGENT_MAX)));
        r.setToScheduleVersion(r.getFromScheduleVersion() + 1);
        restructures.saveAndFlush(r);
        auditApi.record(AuditEntryRequest.of(purchase.buyerUserId(), "plan.restructure_accepted", "plan_restructure",
                r.getId(), purchase.sellerTenantId(), "Buyer accepted restructure letter " + r.getLetterNumber() + " on "
                        + purchase.reference() + ": " + terms(r, fresh, purchase.currency().name()) + "."));
        log.info("Restructure {} accepted on plan {}: schedule version {}", r.getId(), transactionId,
                r.getToScheduleVersion());
        // 5b: nothing is late on the new dates, so a notice of revocation running ends.
        PlanSchedule now = checkoutApi.schedulesOf(List.of(transactionId)).get(transactionId);
        revocationNotices.clearIfCaughtUp(transactionId, r.getTransactionReference(), r.getPlanName(),
                r.getPlotLabel() + ", " + r.getEstateName(), progress(transactionId, now));
        String text = terms(r, fresh, purchase.currency().name());
        tellBuyer(r, SettlementAlertSender.RestructureStage.ACCEPTED, text, null);
        tellDevelopers(r, SettlementAlertSender.RestructureStage.ACCEPTED, text, null);
        return view(purchase);
    }

    // ------------------------------------------------------------------ the developer

    /** Open requests and proposals for the caller's company — a branch officer sees their branch's only. */
    @Transactional(readOnly = true)
    public List<RestructureQueueItemDto> queue(String status) {
        TenantScope scope = company();
        Set<RestructureStatus> wanted = status == null ? RestructureStatus.OPEN
                : switch (status) {
                    case "requested" -> Set.of(RestructureStatus.REQUESTED);
                    case "proposed" -> Set.of(RestructureStatus.PROPOSED);
                    default -> throw new PaymentException.UnknownStatusFilter(status);
                };
        List<PlanRestructure> open = restructures.findBySellerTenantIdAndStatusInOrderByRequestedAtAsc(
                scope.tenantId(), wanted);
        Map<UUID, FinanceQueueRow> visible = checkoutApi.onPlan(scope.tenantId(),
                        open.stream().map(PlanRestructure::getTransactionId).toList()).stream()
                .collect(Collectors.toMap(FinanceQueueRow::transactionId, row -> row));
        return open.stream().filter(r -> visible.containsKey(r.getTransactionId())).map(r -> {
            FinanceQueueRow row = visible.get(r.getTransactionId());
            return new RestructureQueueItemDto(r.getId(), r.getTransactionId(), row.transactionReference(),
                    row.estateName(), row.plotLabel(), identityApi.emailOf(r.getBuyerUserId()).orElse(null),
                    row.planName(), r.getStatus().wire(), r.getRequestReason(), r.getPreferredInstalments(),
                    r.getRequestedAt(), r.getExpiresOn());
        }).toList();
    }

    @Transactional(readOnly = true)
    public RestructureDto forSeller(UUID transactionId) {
        return view(seller(transactionId, company()).purchase());
    }

    /** What proposing these terms would mean — nothing is written. */
    @Transactional(readOnly = true)
    public RestructureDto.Proposal preview(UUID transactionId, int count, String frequency, LocalDate firstDue) {
        Seller seller = seller(transactionId, company());
        PlanSchedule schedule = requireRunning(seller.purchase());
        Figures f = figures(schedule, progress(transactionId, schedule), PlanStandings.today(), count,
                frequency(frequency), firstDue);
        return proposalDto(null, null, f.paid(), count, frequency(frequency), firstDue, f.balance(), f.carried(),
                seller.purchase().currency().name(), f.fresh(), null, null);
    }

    @Transactional
    public RestructureDto propose(UUID transactionId, int count, String frequency, LocalDate firstDue) {
        TenantScope scope = company();
        Seller seller = seller(transactionId, scope);
        PayableTransaction purchase = seller.purchase();
        FinanceVerificationService.refuseOwnPurchase(purchase.buyerUserId(), scope.userId());
        PlanSchedule schedule = requireRunning(purchase);
        payments.findLockedByTransactionIdAndStatus(transactionId, PaymentStatus.SUCCEEDED);
        PlanRestructure r = open(transactionId, RestructureStatus.REQUESTED, "request waiting for a decision");
        LocalDate today = PlanStandings.today();
        PaymentFrequency every = frequency(frequency);
        Figures f = figures(schedule, progress(transactionId, schedule), today, count, every, firstDue);

        r.setStatus(RestructureStatus.PROPOSED);
        r.setProposedBy(scope.userId());
        r.setProposedAt(Instant.now());
        r.setFromScheduleVersion(schedule.version());
        r.setInstalmentCount(count);
        r.setFrequency(every);
        r.setFirstDueDate(firstDue);
        r.setBalance(f.balance().setScale(2, RoundingMode.HALF_UP));
        r.setCarriedPenalty(f.carried().setScale(2, RoundingMode.HALF_UP));
        r.setExpiresOn(today.plusDays(PROPOSAL_DAYS));
        r.setTransactionReference(seller.row().transactionReference());
        r.setPlanName(seller.row().planName());
        r.setPlotLabel(seller.row().plotLabel());
        r.setEstateName(seller.row().estateName());
        proposalRows.saveAll(f.fresh().stream().map(i -> PlanRestructureInstalment.builder()
                .restructureId(r.getId())
                .sequence(i.sequence())
                .dueDate(i.dueDate())
                .amount(i.amount())
                .carriedPenalty(i.carriedPenalty())
                .build()).toList());
        RestructureLetters.LetterRef letter = letters.issue(new RestructureLetterRequest(r.getId(), transactionId,
                        purchase.buyerUserId(), purchase.sellerTenantId(), purchase.estateId(), purchase.plotId(),
                        r.getRequestedAt(), schedule.planName(), f.paid(), schedule.planTotal(), r.getBalance(),
                        r.getCarriedPenalty(), every.getValue(), f.fresh(), r.getExpiresOn(),
                        purchase.currency().name()))
                .orElseThrow(() -> new PaymentException.LetterNotApproved("This estate's letter setup isn't approved "
                        + "and current, so a restructure letter can't be issued. Ask your Executive Director to "
                        + "approve the allocation letter again, then propose. Nothing was changed."));
        r.setLetterId(letter.id());
        r.setLetterNumber(letter.number());
        restructures.saveAndFlush(r);
        String text = terms(r, f.fresh(), purchase.currency().name());
        auditApi.record(AuditEntryRequest.of(scope.userId(), "plan.restructure_proposed", "plan_restructure",
                r.getId(), scope.tenantId(), "Restructure proposed on " + purchase.reference() + " (letter "
                        + letter.number() + ", open until " + r.getExpiresOn() + "): " + text + "."));
        log.info("Restructure {} proposed on plan {} by {}", r.getId(), transactionId, scope.userId());
        tellBuyer(r, SettlementAlertSender.RestructureStage.PROPOSED, text, null);
        return view(purchase);
    }

    @Transactional
    public RestructureDto reject(UUID transactionId, String reason) {
        TenantScope scope = company();
        PayableTransaction purchase = seller(transactionId, scope).purchase();
        FinanceVerificationService.refuseOwnPurchase(purchase.buyerUserId(), scope.userId());
        PlanRestructure r = open(transactionId, RestructureStatus.REQUESTED, "request waiting for a decision");
        r.close(RestructureStatus.REJECTED, scope.userId(), reason.trim());
        restructures.saveAndFlush(r);
        auditApi.record(AuditEntryRequest.of(scope.userId(), "plan.restructure_rejected", "plan_restructure",
                r.getId(), scope.tenantId(), "Restructure request on " + purchase.reference() + " declined: "
                        + r.getCloseNote()));
        tellBuyer(r, SettlementAlertSender.RestructureStage.REJECTED, null, r.getCloseNote());
        return view(purchase);
    }

    @Transactional
    public RestructureDto withdraw(UUID transactionId, String note) {
        TenantScope scope = company();
        PayableTransaction purchase = seller(transactionId, scope).purchase();
        PlanRestructure r = open(transactionId, RestructureStatus.PROPOSED, "proposal waiting for the buyer");
        String why = note == null || note.isBlank() ? null : note.trim();
        r.close(RestructureStatus.WITHDRAWN, scope.userId(), why);
        restructures.saveAndFlush(r);
        // Decision 4: the developer changes a proposal by withdrawing it and proposing
        // again — the buyer's request carries on, as a fresh row (history keeps both).
        restructures.saveAndFlush(PlanRestructure.builder()
                .transactionId(r.getTransactionId())
                .sellerTenantId(r.getSellerTenantId())
                .buyerUserId(r.getBuyerUserId())
                .status(RestructureStatus.REQUESTED)
                .requestReason(r.getRequestReason())
                .preferredInstalments(r.getPreferredInstalments())
                .requestedAt(r.getRequestedAt())
                .transactionReference(r.getTransactionReference())
                .planName(r.getPlanName())
                .build());
        auditApi.record(AuditEntryRequest.of(scope.userId(), "plan.restructure_withdrawn", "plan_restructure",
                r.getId(), scope.tenantId(), "Restructure proposal (letter " + r.getLetterNumber() + ") on "
                        + purchase.reference() + " withdrawn by the developer; the buyer's request stays open."
                        + (why == null ? "" : " Note: " + why)));
        tellBuyer(r, SettlementAlertSender.RestructureStage.WITHDRAWN, null, why);
        return view(purchase);
    }

    // ------------------------------------------------------------------ the daily job

    /**
     * Decision 4: proposals past their last day lapse, and the buyer is
     * reminded a couple of days before — each once. Moves no money.
     *
     * @return proposals lapsed
     */
    @Transactional
    public int lapse(LocalDate today) {
        int lapsed = 0;
        for (PlanRestructure r : restructures.findByStatusAndExpiresOnBefore(RestructureStatus.PROPOSED, today)) {
            r.close(RestructureStatus.LAPSED, null, "Not accepted by " + r.getExpiresOn() + ".");
            restructures.saveAndFlush(r);
            auditApi.record(AuditEntryRequest.of(null, "plan.restructure_lapsed", "plan_restructure", r.getId(),
                    r.getSellerTenantId(), "Restructure proposal (letter " + r.getLetterNumber() + ") on "
                            + r.getTransactionReference() + " lapsed unaccepted."));
            tellBuyer(r, SettlementAlertSender.RestructureStage.LAPSED, null, null);
            lapsed++;
        }
        for (PlanRestructure r : restructures.findByStatusAndExpiresOnLessThanEqualAndLapseReminderSentAtIsNull(
                RestructureStatus.PROPOSED, today.plusDays(LAPSE_REMINDER_DAYS))) {
            r.setLapseReminderSentAt(Instant.now());
            restructures.saveAndFlush(r);
            tellBuyer(r, SettlementAlertSender.RestructureStage.LAPSING, null, null);
        }
        return lapsed;
    }

    // ------------------------------------------------------------------ the figures

    /**
     * @param paid  instalments already paid (they stay, with their numbers)
     * @param fresh the new instalments, numbering carried on from the first unpaid one
     */
    record Figures(int paid, BigDecimal balance, BigDecimal carried, List<PlanSchedule.Instalment> fresh) {
    }

    /**
     * Decision 2: what is still owed, spread over the new instalments — never
     * less; decision 3: every penalty already reached (including one carried
     * by an earlier restructure) is kept and charged with the first.
     */
    static Figures figures(PlanSchedule schedule, PlanProgress progress, LocalDate today, int count,
                           PaymentFrequency frequency, LocalDate firstDue) {
        if (progress.awaitingFinance().isPresent()) {
            throw new PaymentException.PaymentAwaitingFinance();
        }
        List<PlanSchedule.Instalment> remaining = progress.remaining();
        if (remaining.isEmpty()) {
            throw new PaymentException.PlanNotRestructurable("paid off");
        }
        int paid = schedule.instalments().size() - remaining.size();
        if (count < 1 || paid + count > MAX_INSTALMENTS) {
            throw new PaymentException.InvalidRestructureTerms("At most " + MAX_INSTALMENTS + " instalments in all: "
                    + paid + " already paid, so between 1 and " + (MAX_INSTALMENTS - paid) + " new ones.");
        }
        LocalDate latest = today.plusMonths(LATEST_START_MONTHS);
        if (firstDue == null || !firstDue.isAfter(today) || firstDue.isAfter(latest)) {
            throw new PaymentException.InvalidRestructureTerms("The first new instalment must fall after today and "
                    + "no later than " + latest + ".");
        }
        BigDecimal balance = progress.balance();
        BigDecimal carried = remaining.stream().flatMap(i -> progress.penaltyDue(i, today).stream())
                .map(LatePaymentTerms.Penalty::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        List<BigDecimal> amounts = InstalmentPlanCalculator.split(balance, count)
                .orElseThrow(() -> new PaymentException.InvalidRestructureTerms(
                        "That is too many instalments for what is owed."));
        List<LocalDate> dates = InstalmentPlanCalculator.dueDatesFrom(firstDue, frequency, count);
        int first = remaining.getFirst().sequence();
        List<PlanSchedule.Instalment> fresh = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            fresh.add(new PlanSchedule.Instalment(first + n, dates.get(n), amounts.get(n),
                    n == 0 ? carried : BigDecimal.ZERO));
        }
        return new Figures(paid, balance, carried, fresh);
    }

    private List<PlanSchedule.Instalment> newInstalments(PlanRestructure r) {
        return proposalRows.findByRestructureIdOrderBySequenceAsc(r.getId()).stream()
                .map(i -> new PlanSchedule.Instalment(i.getSequence(), i.getDueDate(), i.getAmount(),
                        i.getCarriedPenalty()))
                .toList();
    }

    /** The proposal in plain words, for the emails and the audit trail. */
    private static String terms(PlanRestructure r, List<PlanSchedule.Instalment> fresh, String currency) {
        PlanSchedule.Instalment first = fresh.getFirst();
        PlanSchedule.Instalment last = fresh.getLast();
        String each = fresh.size() == 1 ? "1 instalment of " + naira(first.amount(), currency)
                : fresh.size() + " " + r.getFrequency().getValue() + " instalments of " + naira(first.amount(), currency)
                + (last.amount().compareTo(first.amount()) == 0 ? "" : " (the last " + naira(last.amount(), currency) + ")");
        return each + ", the first due " + RevocationService.DATE.format(first.dueDate()) + ", the last "
                + RevocationService.DATE.format(last.dueDate())
                + (r.getCarriedPenalty().signum() > 0 ? "; penalties already reached ("
                + naira(r.getCarriedPenalty(), currency) + ") are payable with the first" : "")
                + ". The total owed is unchanged: " + naira(r.getBalance(), currency);
    }

    private static String naira(BigDecimal amount, String currency) {
        return currency + " " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // ------------------------------------------------------------------ reading

    private RestructureDto view(PayableTransaction purchase) {
        List<PlanRestructure> all = restructures.findByTransactionIdOrderByCreatedAtDesc(purchase.id());
        List<RestructureDto.Restructure> history = all.stream().map(r -> dto(r, purchase.currency().name())).toList();
        RestructureDto.Restructure open = all.stream().filter(PlanRestructure::isOpen).findFirst()
                .map(r -> dto(r, purchase.currency().name())).orElse(null);
        return new RestructureDto(purchase.id(), purchase.reference(), purchase.status(), open, history);
    }

    private RestructureDto.Restructure dto(PlanRestructure r, String currency) {
        RestructureDto.Proposal proposal = r.getProposedAt() == null ? null
                : proposalDto(r.getProposedAt(), r.getExpiresOn(), paidCount(r), r.getInstalmentCount(),
                r.getFrequency(), r.getFirstDueDate(), r.getBalance(), r.getCarriedPenalty(), currency,
                newInstalments(r), r.getLetterId(), r.getLetterNumber());
        return new RestructureDto.Restructure(r.getId(), r.getStatus().wire(), r.getRequestReason(),
                r.getPreferredInstalments(), r.getRequestedAt(), proposal, r.getAcceptedAt(), r.getClosedAt(),
                r.getCloseNote());
    }

    /** Paid when proposed: the first new instalment's number, less one. */
    private int paidCount(PlanRestructure r) {
        return proposalRows.findByRestructureIdOrderBySequenceAsc(r.getId()).stream()
                .map(PlanRestructureInstalment::getSequence).min(Comparator.naturalOrder()).map(n -> n - 1).orElse(0);
    }

    private static RestructureDto.Proposal proposalDto(Instant proposedAt, LocalDate expiresOn, int paid, int count,
                                                       PaymentFrequency frequency, LocalDate firstDue,
                                                       BigDecimal balance, BigDecimal carried, String currency,
                                                       List<PlanSchedule.Instalment> fresh, UUID letterId,
                                                       String letterNumber) {
        return new RestructureDto.Proposal(proposedAt, expiresOn, paid, count, frequency.getValue(), firstDue, balance,
                carried, currency, fresh.stream().map(i -> new RestructureDto.NewInstalment(i.sequence(),
                i.dueDate(), i.amount(), i.carriedPenalty())).toList(), letterId, letterNumber);
    }

    // ------------------------------------------------------------------ rules and lookups

    private PayableTransaction ownPurchase(UUID transactionId) {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        return checkoutApi.transactionForBuyer(transactionId, scope.userId())
                .orElseThrow(PaymentException.TransactionNotFound::new);
    }

    /** The purchase and its finance row — labels, read under the caller's RLS (another branch's is a 404). */
    private record Seller(PayableTransaction purchase, FinanceQueueRow row) {
    }

    private Seller seller(UUID transactionId, TenantScope scope) {
        PayableTransaction purchase = checkoutApi.planForSeller(transactionId, scope.tenantId())
                .orElseThrow(PaymentException.TransactionNotFound::new);
        FinanceQueueRow row = checkoutApi.onPlan(scope.tenantId(), List.of(transactionId)).stream().findFirst()
                .orElseThrow(() -> new PaymentException.PlanNotRestructurable(purchase.status()));
        return new Seller(purchase, row);
    }

    private PlanSchedule requireRunning(PayableTransaction purchase) {
        PlanSchedule schedule = checkoutApi.schedulesOf(List.of(purchase.id())).get(purchase.id());
        if (!purchase.isOnPlan() || schedule == null) {
            throw new PaymentException.PlanNotRestructurable(purchase.status());
        }
        return schedule;
    }

    private PlanProgress progress(UUID transactionId, PlanSchedule schedule) {
        return planStandings.progressOf(Map.of(transactionId, schedule)).get(transactionId);
    }

    private PlanRestructure open(UUID transactionId, RestructureStatus needed, String what) {
        return restructures.findOpenLocked(transactionId).filter(r -> r.getStatus() == needed)
                .orElseThrow(() -> new PaymentException.RestructureNotOpen(what));
    }

    /** The open proposal, and only if it is the one the buyer named. */
    private PlanRestructure proposal(UUID transactionId, UUID restructureId) {
        return restructures.findOpenLocked(transactionId)
                .filter(r -> r.getStatus() == RestructureStatus.PROPOSED && r.getId().equals(restructureId))
                .orElseThrow(PaymentException.RestructureNotFound::new);
    }

    private static PaymentFrequency frequency(String value) {
        try {
            return PaymentFrequency.fromValue(value);
        } catch (IllegalArgumentException e) {
            throw new PaymentException.InvalidRestructureTerms("frequency must be monthly or quarterly.");
        }
    }

    private static TenantScope company() {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        if (scope.tenantId() == null) {
            throw new PaymentException.CompanyScopeRequired();
        }
        return scope;
    }

    // ------------------------------------------------------------------ telling people

    /** After commit; a failed email never undoes the step. */
    private void tellBuyer(PlanRestructure r, SettlementAlertSender.RestructureStage stage, String terms, String note) {
        identityApi.emailOf(r.getBuyerUserId()).ifPresent(to -> buyerNotifier.afterCommit(
                "restructure " + stage.name().toLowerCase(Locale.ROOT), () -> alerts.planRestructure(alert(to, false,
                        stage, r, terms, note, buyerNotifier.purchaseUrl(r.getTransactionId())))));
    }

    private void tellDevelopers(PlanRestructure r, SettlementAlertSender.RestructureStage stage, String terms,
                                String note) {
        for (String to : identityApi.executiveDirectorEmails(r.getSellerTenantId())) {
            buyerNotifier.afterCommit("restructure " + stage.name().toLowerCase(Locale.ROOT) + " (developer)",
                    () -> alerts.planRestructure(alert(to, true, stage, r, terms, note, null)));
        }
    }

    private static SettlementAlertSender.RestructureAlert alert(String to, boolean developer,
                                                                SettlementAlertSender.RestructureStage stage,
                                                                PlanRestructure r, String terms, String note,
                                                                String url) {
        String plot = r.getPlotLabel() == null ? null : r.getPlotLabel() + ", " + r.getEstateName();
        return new SettlementAlertSender.RestructureAlert(to, developer, stage, r.getTransactionReference(),
                r.getPlanName(), plot, terms, note,
                r.getExpiresOn() == null ? null : RevocationService.DATE.format(r.getExpiresOn()),
                r.getLetterNumber(), url);
    }
}
