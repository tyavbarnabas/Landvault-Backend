package com.techcomfort.landvaultbackend.portfolio.internal.service;

import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.PaymentSummary;
import com.techcomfort.landvaultbackend.checkout.PaymentSummaryProvider;
import com.techcomfort.landvaultbackend.checkout.PendingPurchase;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.common.TitleStatus;
import com.techcomfort.landvaultbackend.common.TitleType;
import com.techcomfort.landvaultbackend.portfolio.PortfolioApi;
import com.techcomfort.landvaultbackend.portfolio.dto.OwnedPlotDto;
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
        Map<UUID, PlanSchedule> schedules = checkoutApi.schedulesOf(owned.stream()
                .filter(o -> o.getStatus() == OwnedPlotStatus.INSTALLMENT_ACTIVE).map(OwnedPlot::getTransactionId).toList());
        // A record with no sale behind it (an upgrade's) has no transaction id, and no plan.
        owned.forEach(o -> out.add(ownedDto(o, o.getTransactionId() == null ? null : schedules.get(o.getTransactionId()),
                details.get(o.getPlotId()),
                o.getTransactionId() == null ? null : paid.get(o.getTransactionId()))));
        return out;
    }

    /**
     * An owned plot. On an instalment plan (phase 3a) the total is what the
     * plan costs, the deposit is what's paid, and the next instalment comes
     * from the fixed schedule; instalments themselves arrive with phase 3b.
     */
    private Entry ownedDto(OwnedPlot o, PlanSchedule schedule, PlotDetails d, PaymentSummary payment) {
        boolean confirmed = payment != null && "succeeded".equals(payment.status()) && payment.amountPaid() != null;
        String paymentType = schedule == null ? "outright" : "deposit";
        List<PortfolioPaymentDto> history = confirmed
                ? List.of(paymentDto(payment, o.getCurrency(), "confirmed", o.getAcquiredAt(), paymentType))
                : List.of();
        Entry entry = dto(o.getId(), o.getTransactionId(), o.getEstateId(), o.getPlotId(), d, o.getCornerPremiumPct(),
                o.getBasePrice(), o.getIntent().getValue(), o.getPlan().toLowerCase(Locale.ROOT),
                schedule == null ? o.getTotalPrice() : schedule.planTotal(),
                confirmed ? payment.amountPaid() : BigDecimal.ZERO, o.getCurrency(),
                LocalDate.ofInstant(o.getAcquiredAt(), NIGERIA), o.getStatus(), history, o.getSupersedes(),
                o.getAcquiredAt());
        if (schedule == null || schedule.instalments().isEmpty()) {
            return entry;
        }
        PlanSchedule.Instalment next = schedule.instalments().getFirst();
        OwnedPlotDto p = entry.dto();
        return new Entry(new OwnedPlotDto(p.id(), p.transactionId(), p.estateId(), p.plotId(), p.plotLabel(),
                p.estate(), p.location(), p.sqm(), p.actualSqm(), p.isCorner(), p.cornerPremiumPct(),
                p.basePrice(), p.titleType(), p.titleStatus(), p.titleVerified(), p.intent(), p.plan(),
                p.totalPrice(), p.paidAmount(), p.currency(), p.acquiredDate(), p.status(), next.dueDate(),
                next.amount(), p.payments(), schedule.instalments().size(), 0, p.arrears(), p.restructureStatus(),
                p.supersedes()), entry.sortTime());
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
                null, null, payments, null, null, null, null, supersedes), sortTime);
    }

    private static PortfolioPaymentDto paymentDto(PaymentSummary p, Currency currency, String status, Instant verifiedAt,
                                                  String type) {
        return new PortfolioPaymentDto(p.reference(), p.paidAt() == null ? null : LocalDate.ofInstant(p.paidAt(), NIGERIA),
                p.amountPaid(), currency.name(), type, status,
                p.receiptId() == null ? null : p.receiptId().toString(), null, verifiedAt);
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
