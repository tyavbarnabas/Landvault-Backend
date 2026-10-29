package com.techcomfort.landvaultbackend.resale.internal.service;

import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.FlexibleStanding;
import com.techcomfort.landvaultbackend.checkout.PayableTransaction;
import com.techcomfort.landvaultbackend.checkout.PaymentSummaryProvider;
import com.techcomfort.landvaultbackend.checkout.PlanPayment;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.checkout.PlanStanding;
import com.techcomfort.landvaultbackend.common.Naira;
import com.techcomfort.landvaultbackend.kyc.KycApi;
import com.techcomfort.landvaultbackend.marketplace.CurrentPlotPrice;
import com.techcomfort.landvaultbackend.marketplace.EstateEligibility;
import com.techcomfort.landvaultbackend.marketplace.MarketplaceApi;
import com.techcomfort.landvaultbackend.portfolio.PortfolioApi;
import com.techcomfort.landvaultbackend.resale.dto.ResaleEligibilityDto;
import com.techcomfort.landvaultbackend.resale.dto.ResaleEligibilityDto.Reason;
import com.techcomfort.landvaultbackend.resale.internal.exceptions.ResaleException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Sell my plot, phase 1 (SP-1): may this plot be listed, would it be an early
 * sale, and what would each side get. Read only. Every rule is in AGENTS.md,
 * "Sell my plot"; the arithmetic is {@link ResaleCalculator}'s.
 */
@Service
@RequiredArgsConstructor
public class ResaleEligibilityService {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** D30: said to the seller before listing, never priced. */
    private static final List<String> NOT_HANDLED = List.of(
            "Recurring charges such as an annual facility fee are between you and the developer; HomeTrue doesn't "
                    + "track them.",
            "Fees for Governor's Consent or registering the transfer, where they apply, are not charged or paid by "
                    + "HomeTrue.",
            "Capital gains tax on any gain is yours to pay; HomeTrue doesn't withhold it.");

    private final PortfolioApi portfolioApi;
    private final CheckoutApi checkoutApi;
    private final PaymentSummaryProvider paymentSummaries;
    private final KycApi kycApi;
    private final MarketplaceApi marketplaceApi;

    @Transactional(readOnly = true)
    public ResaleEligibilityDto eligibility(UUID buyerUserId, UUID ownedPlotOrTransactionId, BigDecimal askedPrice) {
        UUID transactionId = portfolioApi.transactionIdOf(buyerUserId, ownedPlotOrTransactionId)
                .orElseThrow(ResaleException.PlotNotFound::new);
        PayableTransaction purchase = checkoutApi.transactionForBuyer(transactionId, buyerUserId)
                .orElseThrow(ResaleException.PlotNotFound::new);
        LocalDate today = LocalDate.now(LAGOS);
        String kind = kindOf(purchase);
        BigDecimal developerPrice = marketplaceApi.currentPriceOf(purchase.plotId())
                .filter(p -> p.currency() == purchase.currency()).map(CurrentPlotPrice::price).orElse(null);
        String currency = purchase.currency().name();
        if (kind == null) {
            Reason why = purchase.isWithdrawn() || purchase.isRevoked() || "rejected".equals(purchase.status())
                    || "abandoned".equals(purchase.status())
                    ? new Reason("plan_ended", "This purchase has ended, so there is no plot to sell.")
                    : new Reason("not_owned_yet", "This plot can be sold once your purchase has been verified.");
            return new ResaleEligibilityDto(purchase.id(), purchase.reference(), null, false, List.of(why), null,
                    currency, purchase.totalPrice(), BigDecimal.ZERO, BigDecimal.ZERO, developerPrice, null, null, null,
                    null, null,
                    NOT_HANDLED);
        }

        List<Reason> reasons = new ArrayList<>();
        if (!kycApi.isVerified(buyerUserId)) {
            reasons.add(new Reason("kyc_not_approved", "Your identity verification must be approved before you "
                    + "can sell."));
        }
        boolean listed = marketplaceApi.eligibilityOf(purchase.estateId()).map(EstateEligibility::eligible)
                .orElse(false);
        if (!listed) {
            reasons.add(new Reason("estate_not_listed", "The estate isn't on the marketplace at the moment, so "
                    + "nothing on it can be sold. This usually clears once the developer fixes it."));
        }

        BigDecimal agreed = purchase.totalPrice();
        BigDecimal paid = agreed;
        BigDecimal paidInAll = agreed;
        BigDecimal penaltiesOwed = BigDecimal.ZERO;
        BigDecimal developerGainPct = BigDecimal.ZERO;
        ResaleEligibilityDto.Thresholds thresholds = null;
        boolean early = false;
        if (!"paid_in_full".equals(kind)) {
            List<PlanPayment> verified = paymentSummaries.succeededPaymentsFor(List.of(transactionId))
                    .getOrDefault(transactionId, List.of()).stream()
                    .filter(p -> p.financeVerifiedAt() != null).toList();
            paidInAll = verified.stream().map(p -> p.amount().subtract(nullToZero(p.penaltyAmount())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            // The clock runs from when the money arrived (Paystack confirmed), not when finance got to it —
            // finance's speed never counts against the buyer. The payment must still be verified to count.
            LocalDate firstPaymentOn = verified.stream().map(p -> p.paidAt() != null ? p.paidAt()
                            : p.financeVerifiedAt())
                    .min(Instant::compareTo).map(at -> LocalDate.ofInstant(at, LAGOS)).orElse(today);
            BigDecimal planTotal;
            if ("installment".equals(kind)) {
                PlanSchedule schedule = checkoutApi.schedulesOf(List.of(transactionId)).get(transactionId);
                planTotal = schedule == null ? agreed : schedule.planTotal();
                penaltiesOwed = instalmentBlockers(transactionId, schedule, reasons);
            } else {
                flexibleBlockers(transactionId, purchase, today, reasons);
                planTotal = purchase.flexible().planTotal();
                developerGainPct = purchase.flexible().developerGainSharePct();
            }
            paid = ResaleCalculator.landShare(paidInAll, agreed, planTotal);
            BigDecimal minimumPaid = ResaleCalculator.minimumPaid(agreed);
            LocalDate timeMetOn = ResaleCalculator.timeMetOn(firstPaymentOn);
            boolean paidMet = paid.compareTo(minimumPaid) >= 0;
            boolean timeMet = !today.isBefore(timeMetOn);
            thresholds = new ResaleEligibilityDto.Thresholds(minimumPaid, paidMet,
                    minimumPaid.subtract(paid).max(BigDecimal.ZERO), firstPaymentOn, timeMetOn, timeMet,
                    paidMet && timeMet);
            early = !thresholds.met();
            if (early && (developerPrice == null || developerPrice.compareTo(agreed) < 0) && listed) {
                reasons.add(new Reason("early_sale_below_agreed_price", "Before the thresholds a plot is sold at the "
                        + "developer's current price, and that is below your agreed price at the moment, so an early "
                        + "sale isn't offered. You can leave the plan instead."));
            }
        }

        BigDecimal quotePrice = quotePrice(kind, early, askedPrice, agreed, developerPrice);
        ResaleEligibilityDto.Quote quote = quotePrice == null ? null : quote(ResaleCalculator.split(quotePrice,
                agreed, paid, penaltiesOwed, early ? HUNDRED : developerGainPct, BigDecimal.ZERO), developerPrice);
        return new ResaleEligibilityDto(purchase.id(), purchase.reference(), kind, reasons.isEmpty(),
                List.copyOf(reasons), early, currency, agreed, paid, paidInAll, developerPrice,
                "paid_in_full".equals(kind) ? null : agreed, thresholds,
                marketplaceApi.transferRequiresConsent(purchase.estateId()).orElse(null), quote,
                advice(early, quote, thresholds), NOT_HANDLED);
    }

    /** Paid in full once verified, whichever way it was bought; a running plan by its kind; otherwise none. */
    private static String kindOf(PayableTransaction purchase) {
        if ("verified".equals(purchase.status())) {
            return "paid_in_full";
        }
        if (purchase.isOnPlan()) {
            return purchase.isFlexible() ? "flexible" : purchase.isInstalment() ? "installment" : null;
        }
        return null;
    }

    /** D3 on an instalment plan; returns the penalties owed today. */
    private BigDecimal instalmentBlockers(UUID transactionId, PlanSchedule schedule, List<Reason> reasons) {
        PlanStanding standing = schedule == null ? null
                : paymentSummaries.standingsFor(Map.of(transactionId, schedule)).get(transactionId);
        if (standing == null) {
            return BigDecimal.ZERO;
        }
        if ("in_arrears".equals(standing.status())) {
            reasons.add(new Reason("in_arrears", "Your plan is in arrears. Pay what is overdue first."));
        }
        if (standing.revocation() != null && standing.revocation().notice() != null) {
            reasons.add(new Reason("notice_running", "The developer has given notice on your plan. It can be sold "
                    + "once the notice has ended."));
        }
        if (standing.instalments().stream().anyMatch(i -> "awaiting_verification".equals(i.status()))) {
            reasons.add(new Reason("payment_awaiting_finance", "A payment is waiting for the developer's finance "
                    + "team to verify it."));
        }
        if (standing.restructureStatus() != null) {
            reasons.add(new Reason("restructure_open", "A restructure of your plan is open. Settle it first."));
        }
        return standing.arrears() == null ? BigDecimal.ZERO : nullToZero(standing.arrears().penaltyAmount());
    }

    /** D3 on a flexible plan. */
    private void flexibleBlockers(UUID transactionId, PayableTransaction purchase, LocalDate today,
                                  List<Reason> reasons) {
        FlexibleStanding standing = paymentSummaries.flexibleStandingsFor(
                checkoutApi.flexiblePlansOf(List.of(transactionId))).get(transactionId);
        if (standing != null && standing.notice() != null) {
            reasons.add(new Reason("notice_running", "The developer has given notice on your plan. It can be sold "
                    + "once the notice has ended."));
        }
        if (standing != null && standing.awaitingFinance() != null) {
            reasons.add(new Reason("payment_awaiting_finance", "A payment is waiting for the developer's finance "
                    + "team to verify it."));
        }
        LocalDate finishBy = purchase.flexible().finishBy();
        if (finishBy != null && today.isAfter(finishBy)) {
            reasons.add(new Reason("past_finish_by", "Your plan is past its date to finish by ("
                    + DATE.format(finishBy) + "). Only finishing it ends that."));
        }
    }

    /**
     * The price to quote. An early sale is only ever at the developer's price
     * (D19), so a different one is refused, not ignored. Otherwise the price
     * asked — whole kobo, above zero, not below the agreed price on a plan
     * (D7) — or, with none asked, the developer's price when it is allowed.
     */
    private static BigDecimal quotePrice(String kind, boolean early, BigDecimal asked, BigDecimal agreed,
                                         BigDecimal developerPrice) {
        boolean plan = !"paid_in_full".equals(kind);
        if (early) {
            if (asked != null && (developerPrice == null || asked.compareTo(developerPrice) != 0)) {
                throw new ResaleException.InvalidPrice("Before the thresholds the price is set by HomeTrue: the "
                        + "developer's current price" + (developerPrice == null ? "" : ", "
                        + Naira.format(developerPrice)) + ". Leave the price out.");
            }
            return developerPrice != null && developerPrice.compareTo(agreed) >= 0 ? developerPrice : null;
        }
        if (asked == null) {
            return developerPrice != null && (!plan || developerPrice.compareTo(agreed) >= 0) ? developerPrice : null;
        }
        if (asked.signum() <= 0 || asked.stripTrailingZeros().scale() > 2) {
            throw new ResaleException.InvalidPrice("Enter a price above zero, in naira and kobo.");
        }
        if (plan && asked.compareTo(agreed) < 0) {
            throw new ResaleException.InvalidPrice("On a plan the price can't be below your agreed price, "
                    + Naira.format(agreed) + ", so the developer's balance is always covered.");
        }
        return asked;
    }

    private static ResaleEligibilityDto.Quote quote(ResaleCalculator.Split s, BigDecimal developerPrice) {
        return new ResaleEligibilityDto.Quote(s.price(), s.developerBalance(), s.penaltiesOwed(), s.gain(),
                s.developerGainSharePct(), s.developerGainShare(), s.transferFee(), s.developerReceives(),
                s.sellerReceives(), ResaleCalculator.needsReview(s.price(), developerPrice));
    }

    /** D19a: what selling now returns and, before the thresholds, what keeps a share of the gain. */
    private static String advice(boolean early, ResaleEligibilityDto.Quote quote, ResaleEligibilityDto.Thresholds t) {
        if (!early || t == null) {
            return null;
        }
        String now = quote == null ? "" : "Sell now and you get back " + Naira.format(quote.sellerReceives()) + ". ";
        String when = t.timeMet() ? "Once" : "From " + DATE.format(t.timeMetOn()) + (t.paidMet() ? "" : ", once");
        String pay = t.paidMet() ? "" : " you've paid " + Naira.format(t.minimumPaid()) + " in all";
        return now + when + pay + ", you can sell at your own price and keep your share of any gain.";
    }

    private static BigDecimal nullToZero(BigDecimal amount) {
        return Objects.requireNonNullElse(amount, BigDecimal.ZERO);
    }
}
