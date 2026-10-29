package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.FlexiblePurchase;
import com.techcomfort.landvaultbackend.checkout.FlexibleStanding;
import com.techcomfort.landvaultbackend.payments.InactivityPauses;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.PlanRevocation;
import com.techcomfort.landvaultbackend.payments.internal.enums.NoticeTrigger;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentPurpose;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.enums.RevocationStatus;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PlanRevocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Flexible contribution, phase 4: the one reading of whether a flexible plan
 * has gone quiet, used by the buyer's purchase, the developer's list and the
 * notice service alike, so none of them can disagree. A plan is quiet once no
 * payment Paystack confirmed has come in for the declared inactivity period
 * (decision 2: confirmed, not verified — finance's speed never counts against
 * the buyer), or once it is past its date to finish by. See AGENTS.md,
 * "Flexible contribution".
 */
@Service
@RequiredArgsConstructor
public class FlexibleStandings {

    private final PaymentRepository payments;
    private final PlanRevocationRepository revocations;
    private final ObjectProvider<InactivityPauses> pauses;

    /** Each plan's standing today, in three queries. */
    public Map<UUID, FlexibleStanding> standingsOf(Map<UUID, FlexiblePurchase> plans) {
        if (plans.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Payment>> byPurchase = payments.findAllByTransactionIdIn(plans.keySet()).stream()
                .collect(Collectors.groupingBy(Payment::getTransactionId));
        Map<UUID, PlanRevocation> open = revocations.findByTransactionIdInAndStatus(plans.keySet(),
                RevocationStatus.NOTICE).stream().collect(Collectors.toMap(PlanRevocation::getTransactionId,
                Function.identity(), (a, b) -> a));
        LocalDate today = PlanStandings.today();
        Map<UUID, List<InactivityPauses.Pause>> paused = pausesOf(plans.keySet());
        Map<UUID, FlexibleStanding> out = new HashMap<>();
        plans.forEach((id, plan) -> {
            List<Payment> mine = byPurchase.getOrDefault(id, List.of());
            out.put(id, standing(plan, mine, open.get(id), today,
                    pausedDays(paused.getOrDefault(id, List.of()), lastPaymentOn(mine), today)));
        });
        return out;
    }

    /** Sell my plot (D24): the days a listing stopped this plan's inactivity clock since its last payment. */
    int pausedDays(UUID transactionId, List<Payment> payments, LocalDate today) {
        return pausedDays(pausesOf(List.of(transactionId)).getOrDefault(transactionId, List.of()),
                lastPaymentOn(payments), today);
    }

    private Map<UUID, List<InactivityPauses.Pause>> pausesOf(Collection<UUID> transactionIds) {
        InactivityPauses source = pauses.getIfAvailable();
        return source == null ? Map.of() : source.pausesOf(transactionIds);
    }

    /** Each pause counts only for the part after the last payment and before today. */
    static int pausedDays(List<InactivityPauses.Pause> pauses, LocalDate lastPaymentOn, LocalDate today) {
        long days = 0;
        for (InactivityPauses.Pause p : pauses) {
            LocalDate from = p.from().isBefore(lastPaymentOn) ? lastPaymentOn : p.from();
            LocalDate until = p.until() == null || p.until().isAfter(today) ? today : p.until();
            days += Math.max(0, ChronoUnit.DAYS.between(from, until));
        }
        return (int) days;
    }

    static FlexibleStanding standing(FlexiblePurchase plan, List<Payment> payments, PlanRevocation open,
                                     LocalDate today, int pausedDays) {
        FlexibleProgress progress = FlexibleProgress.of(plan, payments);
        LocalDate last = lastPaymentOn(payments);
        LocalDate quietFrom = quietFrom(plan, last, pausedDays);
        return new FlexibleStanding(progress.verified(), progress.remaining(),
                progress.awaitingFinance().map(Payment::getAmount).orElse(null), last, quietFrom, plan.finishBy(),
                // While a notice runs there is nothing to give: its trigger stays on the notice.
                open != null ? null : trigger(plan, progress, last, today, pausedDays).map(NoticeTrigger::wire)
                        .orElse(null),
                open == null ? null : new FlexibleStanding.Notice(open.getTrigger().wire(), open.getReason(),
                        open.getNoticeGivenAt(), open.getDeadline(), open.getDeadline().plusDays(1)));
    }

    /**
     * Why notice may be given today, if it may: past the date to finish by
     * (the stronger reason — only finishing ends it), else no confirmed payment
     * for the inactivity period. Never once the plan is paid in full.
     */
    static Optional<NoticeTrigger> trigger(FlexiblePurchase plan, FlexibleProgress progress, LocalDate lastPaymentOn,
                                           LocalDate today, int pausedDays) {
        if (progress.complete()) {
            return Optional.empty();
        }
        if (plan.finishBy() != null && today.isAfter(plan.finishBy())) {
            return Optional.of(NoticeTrigger.FINISH_BY);
        }
        return !today.isBefore(quietFrom(plan, lastPaymentOn, pausedDays))
                ? Optional.of(NoticeTrigger.INACTIVITY) : Optional.empty();
    }

    /** The inactivity period after the last payment, moved on by the days a listing paused it. The date to finish by never moves. */
    static LocalDate quietFrom(FlexiblePurchase plan, LocalDate lastPaymentOn, int pausedDays) {
        return lastPaymentOn.plusMonths(plan.inactivityMonths()).plusDays(pausedDays);
    }

    /** The last payment Paystack confirmed (finance rejected ones don't count), as a Lagos date. */
    static LocalDate lastPaymentOn(List<Payment> payments) {
        return payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED && p.getReviewReason() == null)
                .filter(p -> p.getPurpose() == PaymentPurpose.CONTRIBUTION)
                .map(Payment::getPaidAt).filter(Objects::nonNull)
                .max(Comparator.<Instant>naturalOrder())
                .map(at -> LocalDate.ofInstant(at, PlanStandings.LAGOS))
                .orElseThrow(() -> new IllegalStateException("A running flexible plan has a confirmed first payment"));
    }
}
