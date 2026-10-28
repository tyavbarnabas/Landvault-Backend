package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.FlexiblePurchase;
import com.techcomfort.landvaultbackend.checkout.FlexibleStanding;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.PlanRevocation;
import com.techcomfort.landvaultbackend.payments.internal.enums.NoticeTrigger;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentPurpose;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentStatus;
import com.techcomfort.landvaultbackend.payments.internal.enums.RevocationStatus;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PlanRevocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
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

    /** Each plan's standing today, in two queries. */
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
        Map<UUID, FlexibleStanding> out = new HashMap<>();
        plans.forEach((id, plan) -> out.put(id, standing(plan, byPurchase.getOrDefault(id, List.of()),
                open.get(id), today)));
        return out;
    }

    static FlexibleStanding standing(FlexiblePurchase plan, List<Payment> payments, PlanRevocation open,
                                     LocalDate today) {
        FlexibleProgress progress = FlexibleProgress.of(plan, payments);
        LocalDate last = lastPaymentOn(payments);
        LocalDate quietFrom = last.plusMonths(plan.inactivityMonths());
        return new FlexibleStanding(progress.verified(), progress.remaining(),
                progress.awaitingFinance().map(Payment::getAmount).orElse(null), last, quietFrom, plan.finishBy(),
                // While a notice runs there is nothing to give: its trigger stays on the notice.
                open != null ? null : trigger(plan, progress, last, today).map(NoticeTrigger::wire).orElse(null),
                open == null ? null : new FlexibleStanding.Notice(open.getTrigger().wire(), open.getReason(),
                        open.getNoticeGivenAt(), open.getDeadline(), open.getDeadline().plusDays(1)));
    }

    /**
     * Why notice may be given today, if it may: past the date to finish by
     * (the stronger reason — only finishing ends it), else no confirmed payment
     * for the inactivity period. Never once the plan is paid in full.
     */
    static Optional<NoticeTrigger> trigger(FlexiblePurchase plan, FlexibleProgress progress, LocalDate lastPaymentOn,
                                           LocalDate today) {
        if (progress.complete()) {
            return Optional.empty();
        }
        if (plan.finishBy() != null && today.isAfter(plan.finishBy())) {
            return Optional.of(NoticeTrigger.FINISH_BY);
        }
        return !today.isBefore(lastPaymentOn.plusMonths(plan.inactivityMonths()))
                ? Optional.of(NoticeTrigger.INACTIVITY) : Optional.empty();
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
