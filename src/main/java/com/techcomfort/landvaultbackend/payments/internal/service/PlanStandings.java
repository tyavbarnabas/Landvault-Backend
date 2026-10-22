package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.checkout.PlanStanding;
import com.techcomfort.landvaultbackend.common.LatePaymentTerms;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.domain.PaymentPenalty;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentPenaltyRepository;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
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
        Map<UUID, PlanProgress> out = new HashMap<>();
        schedules.forEach((id, schedule) -> out.put(id,
                PlanProgress.of(schedule, byTransaction.getOrDefault(id, List.of()), lines)));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<UUID, PlanStanding> standingsOf(Map<UUID, PlanSchedule> schedules, LocalDate today) {
        Map<UUID, PlanStanding> out = new HashMap<>();
        progressOf(schedules).forEach((id, progress) -> out.put(id, standing(progress, today)));
        return out;
    }

    static PlanStanding standing(PlanProgress progress, LocalDate today) {
        List<PlanProgress.InstalmentStanding> standings = progress.standings(today);
        List<PlanStanding.InstalmentStatus> instalments = standings.stream().map(s -> new PlanStanding.InstalmentStatus(
                s.instalment().sequence(), s.instalment().dueDate(), s.instalment().amount(), s.standing().wire(),
                s.daysLate(), s.penalty().map(PlanStandings::penalty).orElse(null))).toList();
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

    private static PlanStanding.Penalty penalty(LatePaymentTerms.Penalty p) {
        return new PlanStanding.Penalty(p.monthsLate(), p.penaltyPct(), p.amount());
    }
}
