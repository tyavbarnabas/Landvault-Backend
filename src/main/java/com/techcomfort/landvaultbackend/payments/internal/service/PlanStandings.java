package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.checkout.PlanStanding;
import com.techcomfort.landvaultbackend.common.LatePaymentTerms;
import com.techcomfort.landvaultbackend.common.RevocationTerms;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.PaymentPenalty;
import com.techcomfort.landvaultbackend.payments.internal.domain.PenaltyWaiver;
import com.techcomfort.landvaultbackend.payments.internal.domain.PlanRestructure;
import com.techcomfort.landvaultbackend.payments.internal.domain.PlanRevocation;
import com.techcomfort.landvaultbackend.payments.internal.enums.RestructureStatus;
import com.techcomfort.landvaultbackend.payments.internal.enums.RevocationStatus;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentPenaltyRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PenaltyWaiverRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PlanRestructureRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PlanRevocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 4: where plans stand on a given day — the one place the buyer's
 * purchase, the portfolio, the developer's list and the reminders read it
 * from, so none of them can disagree. Batched: two queries for any number of
 * plans. Nothing is stored. See AGENTS.md, "Instalment plans".
 */
@Service
@RequiredArgsConstructor
public class PlanStandings {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final PaymentRepository payments;
    private final PaymentPenaltyRepository penalties;
    private final PlanRevocationRepository revocations;
    private final PlanRestructureRepository restructures;
    private final PenaltyWaiverRepository waivers;

    public static LocalDate today() {
        return LocalDate.now(LAGOS);
    }

    @Transactional(readOnly = true)
    public Map<UUID, PlanProgress> progressOf(Map<UUID, PlanSchedule> schedules) {
        if (schedules.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Payment>> byTransaction = payments.findAllByTransactionIdIn(schedules.keySet()).stream()
                .sorted(Comparator.comparing(Payment::getCreatedAt))
                .collect(Collectors.groupingBy(Payment::getTransactionId));
        List<PaymentPenalty> lines = penalties.findByPaymentIdIn(byTransaction.values().stream()
                .flatMap(List::stream).map(Payment::getId).toList());
        Map<UUID, List<PenaltyWaiver>> waived = waivers.findByTransactionIdIn(schedules.keySet()).stream()
                .collect(Collectors.groupingBy(PenaltyWaiver::getTransactionId));
        Map<UUID, PlanProgress> out = new HashMap<>();
        schedules.forEach((id, schedule) -> out.put(id, PlanProgress.of(schedule,
                byTransaction.getOrDefault(id, List.of()), lines, waived.getOrDefault(id, List.of()))));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<UUID, PlanStanding> standingsOf(Map<UUID, PlanSchedule> schedules, LocalDate today) {
        Map<UUID, PlanStanding> out = new HashMap<>();
        Map<UUID, PlanRevocation> notices = schedules.isEmpty() ? Map.of()
                : revocations.findByTransactionIdInAndStatus(schedules.keySet(), RevocationStatus.NOTICE).stream()
                .collect(Collectors.toMap(PlanRevocation::getTransactionId, r -> r));
        Map<UUID, String> restructuring = schedules.isEmpty() ? Map.of()
                : restructures.findByTransactionIdInAndStatusIn(schedules.keySet(), RestructureStatus.OPEN).stream()
                .collect(Collectors.toMap(PlanRestructure::getTransactionId, r -> r.getStatus().wire()));
        progressOf(schedules).forEach((id, progress) -> {
            PlanStanding s = standing(progress, today, Optional.ofNullable(notices.get(id)));
            out.put(id, new PlanStanding(s.status(), s.instalments(), s.arrears(), s.revocation(),
                    restructuring.get(id)));
        });
        return out;
    }

    static PlanStanding standing(PlanProgress progress, LocalDate today) {
        return standing(progress, today, Optional.empty());
    }

    static PlanStanding standing(PlanProgress progress, LocalDate today, Optional<PlanRevocation> notice) {
        PlanStanding standing = arrearsStanding(progress, today);
        return new PlanStanding(standing.status(), standing.instalments(), standing.arrears(),
                progress.complete() ? null : revocation(progress, today, notice));
    }

    /** Phase 5b: when the plan could be revoked, and the open notice. Null with no terms. */
    private static PlanStanding.Revocation revocation(PlanProgress progress, LocalDate today,
                                                      Optional<PlanRevocation> notice) {
        RevocationTerms terms = progress.schedule().lateTerms().revocation();
        if (terms == null) {
            return null;
        }
        return new PlanStanding.Revocation(terms.afterMonths(), terms.noticeDays(),
                progress.oldestLate(today).map(s -> terms.revocableFrom(s.instalment().dueDate())).orElse(null),
                progress.revocationTrigger(today).isPresent(),
                notice.map(n -> new PlanStanding.Notice(n.getReason(), n.getNoticeGivenAt(), n.getDeadline(),
                        n.getInstalmentSequence())).orElse(null));
    }

    private static PlanStanding arrearsStanding(PlanProgress progress, LocalDate today) {
        List<PlanProgress.InstalmentStanding> standings = progress.standings(today);
        List<PlanStanding.InstalmentStatus> instalments = standings.stream().map(s -> new PlanStanding.InstalmentStatus(
                s.instalment().sequence(), s.instalment().dueDate(), s.instalment().amount(), s.standing().wire(),
                s.daysLate(), s.penalty().map(PlanStandings::penalty).orElse(null),
                nullIfZero(progress.waivedOn(s.instalment().sequence())))).toList();
        if (progress.complete()) {
            return new PlanStanding("paid_off", instalments, null);
        }
        List<PlanProgress.InstalmentStanding> late = standings.stream().filter(PlanProgress.InstalmentStanding::late)
                .toList();
        if (late.isEmpty()) {
            boolean dueToday = standings.stream().anyMatch(s -> s.standing() == PlanProgress.Standing.DUE_TODAY);
            return new PlanStanding(dueToday ? "due_today" : "on_track", instalments, null);
        }
        PlanProgress.InstalmentStanding oldest = late.getFirst();
        boolean inArrears = late.stream().anyMatch(s -> s.standing() == PlanProgress.Standing.IN_ARREARS);
        BigDecimal overdue = late.stream().map(s -> s.instalment().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal penalty = late.stream().flatMap(s -> s.penalty().stream()).map(LatePaymentTerms.Penalty::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String status = inArrears ? "in_arrears" : "overdue";
        return new PlanStanding(status, instalments, new PlanStanding.Arrears(status, late.size(), overdue, penalty,
                overdue.add(penalty), oldest.instalment().dueDate(), LatePaymentTerms.GRACE_DAYS,
                oldest.instalment().dueDate().plusDays(LatePaymentTerms.GRACE_DAYS), oldest.daysLate()));
    }

    private static BigDecimal nullIfZero(BigDecimal amount) {
        return amount.signum() == 0 ? null : amount;
    }

    private static PlanStanding.Penalty penalty(LatePaymentTerms.Penalty p) {
        return new PlanStanding.Penalty(p.monthsLate(), p.penaltyPct(), p.amount());
    }
}
