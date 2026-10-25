package com.techcomfort.landvaultbackend.portfolio.internal.service;

import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.PaymentSummary;
import com.techcomfort.landvaultbackend.checkout.PaymentSummaryProvider;
import com.techcomfort.landvaultbackend.checkout.PendingPurchase;
import com.techcomfort.landvaultbackend.checkout.PlanPayment;
import com.techcomfort.landvaultbackend.checkout.PlanRefund;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.checkout.PlanStanding;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.common.TitleStatus;
import com.techcomfort.landvaultbackend.common.TitleType;
import com.techcomfort.landvaultbackend.portfolio.PortfolioApi;
import com.techcomfort.landvaultbackend.portfolio.dto.ArrearsDto;
import com.techcomfort.landvaultbackend.portfolio.dto.OwnedPlotDto;
import com.techcomfort.landvaultbackend.portfolio.dto.PlanRefundDto;
import com.techcomfort.landvaultbackend.portfolio.dto.PortfolioPaymentDto;
import com.techcomfort.landvaultbackend.portfolio.dto.PortfolioSummaryDto;
import com.techcomfort.landvaultbackend.portfolio.internal.domain.OwnedPlot;
import com.techcomfort.landvaultbackend.portfolio.internal.enums.OwnedPlotStatus;
import com.techcomfort.landvaultbackend.portfolio.internal.exceptions.PortfolioException;
import com.techcomfort.landvaultbackend.portfolio.internal.repository.OwnedPlotRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A buyer's portfolio, across every company: owned plots plus paid purchases
 * still waiting for finance. Every figure is computed here (the frontend does
 * no money arithmetic), never summed across currencies, and retired records
 * never count in totals. See AGENTS.md, "Portfolio".
 */
@Service
@RequiredArgsConstructor
public class PortfolioService implements PortfolioApi {

    private static final ZoneId NIGERIA = ZoneId.of("Africa/Lagos");

    private final OwnedPlotRepository ownedPlots;
    private final CheckoutApi checkoutApi;
    private final PaymentSummaryProvider payments;
    private final EntityManager entityManager;

    /**
     * Newest first, or most urgent first with {@code urgency}: arrears, then
     * due within a week, then in progress, then settled (the frontend's
     * {@code urgencyRank}), applied before paging so a second page stays in order.
     */
    @Transactional(readOnly = true)
    public PageResponse<OwnedPlotDto> list(UUID buyer, String sort, Pageable pageable) {
        Comparator<Entry> order = Comparator.comparing(Entry::sortTime, Comparator.reverseOrder());
        if (sort != null && !sort.isBlank()) {
            if (!"urgency".equals(sort)) {
                throw new PortfolioException.InvalidParameter("sort", "Use sort=urgency, or leave it out for newest first.");
            }
            order = Comparator.comparingInt((Entry e) -> urgencyRank(e.dto())).thenComparing(order);
        }
        List<OwnedPlotDto> all = everything(buyer).stream().sorted(order).map(Entry::dto).toList();
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return PageResponses.from(new PageImpl<>(all.subList(from, to), pageable, all.size()));
    }

    /** By owned-plot id, or by transaction id — which keeps working once a pending purchase is verified. */
    @Transactional(readOnly = true)
    public OwnedPlotDto get(UUID buyer, UUID id) {
        return everything(buyer).stream().map(Entry::dto)
                .filter(p -> p.id().equals(id) || id.equals(p.transactionId()))
                .findFirst()
                .orElseThrow(PortfolioException.PlotNotFound::new);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> transactionIdOf(UUID buyerUserId, UUID ownedPlotOrTransactionId) {
        return everything(buyerUserId).stream().map(Entry::dto)
                .filter(p -> p.id().equals(ownedPlotOrTransactionId)
                        || ownedPlotOrTransactionId.equals(p.transactionId()))
                .map(OwnedPlotDto::transactionId)
                .filter(Objects::nonNull)
                .findFirst();
    }

    @Transactional(readOnly = true)
    public PortfolioSummaryDto summary(UUID buyer) {
        List<OwnedPlotDto> all = everything(buyer).stream().map(Entry::dto).toList();
        Map<String, List<OwnedPlotDto>> owned = all.stream()
                .filter(p -> !p.status().equals(OwnedPlotStatus.PENDING_VERIFICATION.getValue()))
                .filter(p -> statusOf(p).activelyOwned())
                .collect(Collectors.groupingBy(OwnedPlotDto::currency, TreeMap::new, Collectors.toList()));
        List<PortfolioSummaryDto.CurrencyTotals> totals = owned.entrySet().stream()
                .map(e -> new PortfolioSummaryDto.CurrencyTotals(e.getKey(),
                        e.getValue().stream().map(OwnedPlotDto::totalPrice).reduce(BigDecimal.ZERO, BigDecimal::add),
                        e.getValue().stream().map(OwnedPlotDto::paidAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                        e.getValue().size()))
                .toList();
        int pending = (int) all.stream()
                .filter(p -> p.status().equals(OwnedPlotStatus.PENDING_VERIFICATION.getValue())).count();
        return new PortfolioSummaryDto(totals, pending);
    }

    // --- assembling ---

    /** A row and what "newest" means for it: when finance verified it, or when a pending purchase was made. */
    private record Entry(OwnedPlotDto dto, Instant sortTime) {
    }

    private List<Entry> everything(UUID buyer) {
        List<OwnedPlot> owned = ownedPlots.findByBuyerUserIdOrderByAcquiredAtDesc(buyer);
        List<PendingPurchase> pending = checkoutApi.purchasesAwaitingFinance(buyer);
        List<UUID> transactionIds = Stream.concat(
                        owned.stream().map(OwnedPlot::getTransactionId).filter(Objects::nonNull),
                        pending.stream().map(PendingPurchase::transactionId))
                .toList();
        Map<UUID, PaymentSummary> paid = transactionIds.isEmpty() ? Map.of() : payments.summariesFor(transactionIds);
        Map<UUID, PlotDetails> details = (owned.isEmpty() && pending.isEmpty()) ? Map.of() : details(buyer);

        List<Entry> out = new ArrayList<>(owned.size() + pending.size());
        pending.forEach(p -> out.add(pendingDto(p, details.get(p.plotId()), paid.get(p.transactionId()))));
        List<UUID> onPlans = owned.stream().filter(o -> "INSTALLMENT".equals(o.getPlan()) && o.getTransactionId() != null)
                .map(OwnedPlot::getTransactionId).toList();
        Map<UUID, PlanSchedule> schedules = checkoutApi.schedulesOf(onPlans);
        Map<UUID, List<PlanPayment>> planPayments = payments.succeededPaymentsFor(onPlans);
        // Phase 4: where each running plan stands today — arrears and penalties.
        Map<UUID, PlanSchedule> running = new HashMap<>();
        owned.stream().filter(o -> o.getStatus() == OwnedPlotStatus.INSTALLMENT_ACTIVE && o.getTransactionId() != null)
                .filter(o -> schedules.containsKey(o.getTransactionId()))
                .forEach(o -> running.put(o.getTransactionId(), schedules.get(o.getTransactionId())));
        Map<UUID, PlanStanding> standings = running.isEmpty() ? Map.of() : payments.standingsFor(running);
        // 5a/5b: what is coming back on a plan that ended.
        List<UUID> endedPlans = owned.stream().filter(PortfolioService::endedPlan).map(OwnedPlot::getTransactionId)
                .filter(Objects::nonNull).toList();
        Map<UUID, PlanRefund> refunds = endedPlans.isEmpty() ? Map.of() : payments.refundsFor(endedPlans);
        // A record with no sale behind it (an upgrade's) has no transaction id, and no plan.
        owned.forEach(o -> out.add(ownedDto(o, o.getTransactionId() == null ? null : schedules.get(o.getTransactionId()),
                o.getTransactionId() == null ? List.of() : planPayments.getOrDefault(o.getTransactionId(), List.of()),
                details.get(o.getPlotId()),
                o.getTransactionId() == null ? null : paid.get(o.getTransactionId()),
                o.getTransactionId() == null ? null : standings.get(o.getTransactionId()),
                endedPlan(o) ? refunds.get(o.getTransactionId()) : null)));
        return out;
    }

    private static boolean endedPlan(OwnedPlot o) {
        return o.getStatus() == OwnedPlotStatus.WITHDRAWN || o.getStatus() == OwnedPlotStatus.REVOKED;
    }

    /** When a withdrawn or revoked plan ended; null otherwise. A buyer has few, so one read each. */
    private LocalDate endedAt(OwnedPlot o) {
        Optional<Instant> at = o.getStatus() == OwnedPlotStatus.WITHDRAWN ? checkoutApi.withdrawnAt(o.getTransactionId())
                : o.getStatus() == OwnedPlotStatus.REVOKED ? checkoutApi.revokedAt(o.getTransactionId())
                : Optional.empty();
        return at.map(i -> LocalDate.ofInstant(i, NIGERIA)).orElse(null);
    }

    /**
     * An owned plot. On an instalment plan (phase 3a) the total is what the
     * plan costs, the deposit is what's paid, and the next instalment comes
     * from the fixed schedule; instalments themselves arrive with phase 3b.
     */
    private Entry ownedDto(OwnedPlot o, PlanSchedule schedule, List<PlanPayment> planPayments, PlotDetails d,
                           PaymentSummary payment, PlanStanding standing, PlanRefund refund) {
        if (schedule != null) {
            return planDto(o, schedule, planPayments, d, standing, refund);
        }
        boolean confirmed = payment != null && "succeeded".equals(payment.status()) && payment.amountPaid() != null;
        List<PortfolioPaymentDto> history = confirmed
                ? List.of(paymentDto(payment, o.getCurrency(), "confirmed", o.getAcquiredAt(), "outright"))
                : List.of();
        return dto(o.getId(), o.getTransactionId(), o.getEstateId(), o.getPlotId(), d, o.getCornerPremiumPct(),
                o.getBasePrice(), o.getIntent().getValue(), o.getPlan().toLowerCase(Locale.ROOT), o.getTotalPrice(),
                confirmed ? payment.amountPaid() : BigDecimal.ZERO, o.getCurrency(),
                LocalDate.ofInstant(o.getAcquiredAt(), NIGERIA), o.getStatus(), history, o.getSupersedes(),
                o.getAcquiredAt());
    }

    /**
     * An instalment plan (3b): the plan total, what finance has verified (the
     * deposit plus each verified instalment — "paid" means verified), how many
     * instalments that is, and the first one still owed. Every succeeded payment
     * is listed, awaiting verification or confirmed. Phase 4: penalties don't
     * count towards the plan total, and a plan more than 7 days late reads
     * {@code in_arrears}, with {@code arrears} filled in from the first day
     * late — worked out today, never stored.
     */
    private Entry planDto(OwnedPlot o, PlanSchedule schedule, List<PlanPayment> planPayments, PlotDetails d,
                          PlanStanding standing, PlanRefund refund) {
        List<PlanPayment> verified = planPayments.stream().filter(PlanPayment::verified).toList();
        boolean paidOff = verified.stream().anyMatch(p -> "balance".equals(p.purpose()));
        Set<Integer> paidInstalments = verified.stream().filter(p -> "instalment".equals(p.purpose()))
                .map(PlanPayment::instalmentSequence).collect(Collectors.toSet());
        // 5a/5b: a withdrawn or revoked plan owes nothing more.
        boolean ended = paidOff || o.getStatus() == OwnedPlotStatus.WITHDRAWN
                || o.getStatus() == OwnedPlotStatus.REVOKED;
        Optional<PlanSchedule.Instalment> next = ended ? Optional.empty() : schedule.instalments().stream()
                .filter(i -> !paidInstalments.contains(i.sequence())).findFirst();
        List<PortfolioPaymentDto> history = planPayments.stream().map(p -> new PortfolioPaymentDto(p.reference(),
                p.paidAt() == null ? null : LocalDate.ofInstant(p.paidAt(), NIGERIA), p.amount(), o.getCurrency().name(),
                p.purpose(), p.verified() ? "confirmed" : "pending_verification",
                p.receiptId() == null ? null : p.receiptId().toString(), null, p.financeVerifiedAt(),
                p.penaltyAmount())).toList();
        Entry entry = dto(o.getId(), o.getTransactionId(), o.getEstateId(), o.getPlotId(), d, o.getCornerPremiumPct(),
                o.getBasePrice(), o.getIntent().getValue(), o.getPlan().toLowerCase(Locale.ROOT), schedule.planTotal(),
                verified.stream().map(p -> p.amount().subtract(p.penaltyAmount())).reduce(BigDecimal.ZERO,
                        BigDecimal::add), o.getCurrency(),
                LocalDate.ofInstant(o.getAcquiredAt(), NIGERIA),
                standing != null && "in_arrears".equals(standing.status()) ? OwnedPlotStatus.IN_ARREARS : o.getStatus(),
                history, o.getSupersedes(), o.getAcquiredAt());
        OwnedPlotDto p = entry.dto();
        return new Entry(new OwnedPlotDto(p.id(), p.transactionId(), p.estateId(), p.plotId(), p.plotLabel(),
                p.estate(), p.location(), p.sqm(), p.actualSqm(), p.isCorner(), p.cornerPremiumPct(),
                p.basePrice(), p.titleType(), p.titleStatus(), p.titleVerified(), p.intent(), p.plan(),
                p.totalPrice(), p.paidAmount(), p.currency(), p.acquiredDate(), p.status(),
                next.map(PlanSchedule.Instalment::dueDate).orElse(null),
                next.map(PlanSchedule.Instalment::amount).orElse(null), p.payments(), schedule.instalments().size(),
                paidOff ? schedule.instalments().size() : paidInstalments.size(),
                standing == null ? null : arrears(standing),
                standing == null ? p.restructureStatus() : standing.restructureStatus(),
                schedule.planName(), p.acquiredDate(), endedAt(o),
                refund == null ? null : new PlanRefundDto(refund.amount(), refund.refunded(), o.getCurrency().name(),
                        refund.status(), refund.needsYourAccount()),
                p.supersedes()), entry.sortTime());
    }

    private static ArrearsDto arrears(PlanStanding standing) {
        PlanStanding.Arrears a = standing.arrears();
        if (a == null) {
            return null;
        }
        List<String> reasons = standing.instalments().stream()
                .filter(i -> "overdue".equals(i.status()) || "in_arrears".equals(i.status()))
                .filter(i -> i.penalty() != null)
                .map(i -> "instalment " + i.sequence() + " is " + i.penalty().monthsLate() + " month"
                        + (i.penalty().monthsLate() == 1 ? "" : "s") + " late, so the declared "
                        + i.penalty().penaltyPct().stripTrailingZeros().toPlainString() + "% penalty applies to it")
                .toList();
        String reason = reasons.isEmpty() ? null
                : Character.toUpperCase(reasons.getFirst().charAt(0)) + String.join("; ", reasons).substring(1) + ".";
        return new ArrearsDto(a.status(), a.amountOverdue(), a.overdueSince(), a.gracePeriodDays(), a.graceEnds(),
                a.penaltyAmount(), reason, a.instalmentsLate(), a.totalDue(), a.daysLate());
    }

    /** Paid, not yet verified: shown, but no equity — that advances only when finance signs off (IS-3). */
    private Entry pendingDto(PendingPurchase p, PlotDetails d, PaymentSummary payment) {
        List<PortfolioPaymentDto> history = payment != null && "succeeded".equals(payment.status())
                ? List.of(paymentDto(payment, p.currency(), "pending_verification", null,
                "installment".equals(p.plan()) ? "deposit" : "outright"))
                : List.of();
        return dto(p.transactionId(), p.transactionId(), p.estateId(), p.plotId(), d, p.cornerPremiumPct(),
                p.basePrice(), p.intent().getValue(), p.plan(), p.totalPrice(), BigDecimal.ZERO, p.currency(),
                null, OwnedPlotStatus.PENDING_VERIFICATION, history, null, p.createdAt());
    }

    private static Entry dto(UUID id, UUID transactionId, UUID estateId, UUID plotId, PlotDetails d,
                                    BigDecimal cornerPremiumPct, BigDecimal basePrice, String intent, String plan,
                                    BigDecimal totalPrice, BigDecimal paidAmount, Currency currency,
                                    LocalDate acquired, OwnedPlotStatus status, List<PortfolioPaymentDto> payments,
                                    UUID supersedes, Instant sortTime) {
        TitleStatus title = d == null ? null : d.titleStatus();
        return new Entry(new OwnedPlotDto(id, transactionId, estateId, plotId,
                d == null ? null : d.label(), d == null ? null : d.estateName(), d == null ? null : d.location(),
                d == null ? null : d.nominalSizeSqm(), d == null ? null : d.actualAreaSqm(),
                d != null && d.corner(), cornerPremiumPct, basePrice,
                d == null || d.titleType() == null ? null : d.titleType().getValue(),
                title == null ? null : title.getValue(), title == TitleStatus.VERIFIED,
                intent, plan, totalPrice, paidAmount, currency.name(), acquired, status.getValue(),
                null, null, payments, null, null, null, null, null, null, null, null, supersedes), sortTime);
    }

    private static PortfolioPaymentDto paymentDto(PaymentSummary p, Currency currency, String status, Instant verifiedAt,
                                                  String type) {
        return new PortfolioPaymentDto(p.reference(), p.paidAt() == null ? null : LocalDate.ofInstant(p.paidAt(), NIGERIA),
                p.amountPaid(), currency.name(), type, status,
                p.receiptId() == null ? null : p.receiptId().toString(), null, verifiedAt, BigDecimal.ZERO);
    }

    private Map<UUID, PlotDetails> details(UUID buyer) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager
                .createNativeQuery("SELECT * FROM landvault_buyer_plot_details(:buyer)")
                .setParameter("buyer", buyer)
                .getResultList();
        Map<UUID, PlotDetails> byPlot = new HashMap<>();
        for (Object[] r : rows) {
            byPlot.put((UUID) r[0], new PlotDetails((String) r[1], (String) r[2], Boolean.TRUE.equals(r[3]),
                    (BigDecimal) r[4], (BigDecimal) r[5], (String) r[7], (String) r[8], (String) r[9], (String) r[10],
                    r[11] == null ? null : TitleType.valueOf((String) r[11]),
                    r[12] == null ? null : TitleStatus.valueOf((String) r[12])));
        }
        return byPlot;
    }

    private record PlotDetails(String plotNumber, String blockName, boolean corner, BigDecimal nominalSizeSqm,
                               BigDecimal actualAreaSqm, String estateName, String area, String city, String state,
                               TitleType titleType, TitleStatus titleStatus) {

        String label() {
            return blockName == null ? "Plot " + plotNumber : "Block " + blockName + ", Plot " + plotNumber;
        }

        String location() {
            String joined = Stream.of(area, city, state).filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.joining(", "));
            return joined.isEmpty() ? null : joined;
        }
    }

    // --- ordering ---

    static int urgencyRank(OwnedPlotDto p) {
        if (OwnedPlotStatus.IN_ARREARS.getValue().equals(p.status())) {
            return 0;
        }
        if (p.nextDueDate() != null && ChronoUnit.DAYS.between(LocalDate.now(NIGERIA), p.nextDueDate()) <= 7) {
            return 1;
        }
        return OwnedPlotStatus.COMPLETED.getValue().equals(p.status()) ? 3 : 2;
    }

    private static OwnedPlotStatus statusOf(OwnedPlotDto p) {
        return OwnedPlotStatus.valueOf(p.status().toUpperCase(Locale.ROOT));
    }
}
