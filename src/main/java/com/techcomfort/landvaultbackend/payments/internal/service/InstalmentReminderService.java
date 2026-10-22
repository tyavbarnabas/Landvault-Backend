package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.checkout.RunningPlan;
import com.techcomfort.landvaultbackend.common.LatePaymentTerms;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.internal.enums.ReminderStage;
import com.techcomfort.landvaultbackend.payments.internal.repository.InstalmentReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AR-3, phase 4: instalment reminders — 7 days and 1 day before the due date,
 * on the first day overdue and on entering arrears. Moves no money and changes
 * no status (statuses are worked out on read). Each reminder is claimed in the
 * database once per instalment and stage, and its email sent only after that
 * claim commits; a failed email is logged, never retried, and never undoes
 * anything. See AGENTS.md, "Instalment plans".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstalmentReminderService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

    private final CheckoutApi checkoutApi;
    private final PlanStandings planStandings;
    private final InstalmentReminderRepository reminders;
    private final IdentityApi identityApi;
    private final SettlementAlertSender alerts;
    private final BuyerNotifier buyerNotifier;

    /** Runs under platform scope (the job sets it). Returns how many reminders were claimed. */
    @Transactional
    public int remind(LocalDate today) {
        Map<UUID, RunningPlan> plans = checkoutApi.runningPlans(null).stream()
                .collect(Collectors.toMap(RunningPlan::transactionId, Function.identity()));
        Map<UUID, PlanSchedule> schedules = checkoutApi.schedulesOf(plans.keySet());
        int claimed = 0;
        for (Map.Entry<UUID, PlanProgress> entry : planStandings.progressOf(schedules).entrySet()) {
            RunningPlan plan = plans.get(entry.getKey());
            PlanProgress progress = entry.getValue();
            for (PlanProgress.InstalmentStanding standing : progress.standings(today)) {
                Optional<ReminderStage> stage = stageFor(standing, today);
                if (stage.isEmpty() || reminders.claim(plan.transactionId(), standing.instalment().sequence(),
                        stage.get().name()) == 0) {
                    continue;
                }
                claimed++;
                send(plan, progress.schedule(), standing, stage.get());
            }
        }
        if (claimed > 0) {
            log.info("{} instalment reminder(s) claimed for {}", claimed, today);
        }
        return claimed;
    }

    /**
     * Which reminder an instalment is due on {@code today}, if any. Windows
     * rather than exact days, so a missed run still sends the reminder the
     * next day; the claim keeps each to once.
     */
    static Optional<ReminderStage> stageFor(PlanProgress.InstalmentStanding standing, LocalDate today) {
        return switch (standing.standing()) {
            case UPCOMING -> {
                long days = ChronoUnit.DAYS.between(today, standing.instalment().dueDate());
                yield days <= 1 ? Optional.of(ReminderStage.ONE_DAY_BEFORE)
                        : days <= 7 ? Optional.of(ReminderStage.SEVEN_DAYS_BEFORE) : Optional.empty();
            }
            case DUE_TODAY -> Optional.of(ReminderStage.ONE_DAY_BEFORE);
            case OVERDUE -> Optional.of(ReminderStage.OVERDUE);
            case IN_ARREARS -> Optional.of(ReminderStage.IN_ARREARS);
            case AWAITING_VERIFICATION, PAID -> Optional.empty();
        };
    }

    private void send(RunningPlan plan, PlanSchedule schedule, PlanProgress.InstalmentStanding standing,
                      ReminderStage stage) {
        PlanSchedule.Instalment instalment = standing.instalment();
        BigDecimal penalty = standing.penalty().map(LatePaymentTerms.Penalty::amount).orElse(null);
        identityApi.emailOf(plan.buyerUserId()).ifPresent(to -> buyerNotifier.afterCommit("instalment reminder",
                () -> alerts.instalmentReminder(new SettlementAlertSender.InstalmentReminderAlert(to, stage.name(),
                        plan.plotLabel(), plan.estateName(), instalment.sequence(), schedule.instalments().size(),
                        instalment.amount().toPlainString(), DATE.format(instalment.dueDate()),
                        DATE.format(instalment.dueDate().plusDays(LatePaymentTerms.GRACE_DAYS)),
                        penalty == null ? null : penalty.toPlainString(),
                        (penalty == null ? instalment.amount() : instalment.amount().add(penalty)).toPlainString(),
                        !schedule.lateTerms().steps().isEmpty(), buyerNotifier.purchaseUrl(plan.transactionId())))));
    }
}
