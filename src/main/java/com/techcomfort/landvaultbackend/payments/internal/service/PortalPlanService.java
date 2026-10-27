package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.FlexiblePurchase;
import com.techcomfort.landvaultbackend.checkout.FlexibleStanding;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.checkout.PlanStanding;
import com.techcomfort.landvaultbackend.checkout.RunningPlan;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.dto.PortalFlexiblePlanDto;
import com.techcomfort.landvaultbackend.payments.dto.PortalPlanDto;
import com.techcomfort.landvaultbackend.payments.internal.domain.Payment;
import com.techcomfort.landvaultbackend.payments.internal.enums.PaymentPurpose;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Phase 4: a developer's running instalment plans and which are late — the
 * same standing the buyer sees, worked out today. Company-only; a branch
 * officer sees only their branch's (RLS on the plots and estates it's read
 * with). Read-only: chasing a buyer, or starting revocation, is a person's
 * decision made elsewhere.
 */
@Service
@RequiredArgsConstructor
public class PortalPlanService {

    /** {@code late} means overdue or in arrears. */
    private static final Set<String> FILTERS = Set.of("on_track", "due_today", "overdue", "in_arrears", "late");
    private static final List<String> URGENCY = List.of("in_arrears", "overdue", "due_today", "on_track");

    private final CheckoutApi checkoutApi;
    private final PlanStandings planStandings;
    private final IdentityApi identityApi;
    private final PaymentRepository payments;
    private final FlexibleStandings flexibleStandings;

    @Transactional(readOnly = true)
    public List<PortalPlanDto> plans(String status) {
        if (status != null && !FILTERS.contains(status)) {
            throw new PaymentException.UnknownStatusFilter(status);
        }
        return dtos(runningPlans()).stream()
                .filter(p -> status == null || status.equals(p.status())
                        || ("late".equals(status) && p.arrears() != null))
                .sorted(Comparator.comparingInt((PortalPlanDto p) -> URGENCY.indexOf(p.status()))
                        .thenComparing(PortalPlanDto::nextDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** One running plan, as in the list; 404 for one that isn't running, another company's or another branch's. */
    @Transactional(readOnly = true)
    public PortalPlanDto plan(UUID transactionId) {
        return dtos(runningPlans().stream().filter(p -> p.transactionId().equals(transactionId)).toList()).stream()
                .findFirst()
                .orElseThrow(PaymentException.TransactionNotFound::new);
    }

    /**
     * Flexible contribution, phase 4 (decision 4): running flexible plans, the
     * ones notice may be given on first, then those with a notice running, then
     * by when each would count as quiet. Read-only, like the instalment list.
     */
    @Transactional(readOnly = true)
    public List<PortalFlexiblePlanDto> flexiblePlans() {
        Map<UUID, RunningPlan> running = checkoutApi.runningFlexiblePlans(company()).stream()
                .collect(Collectors.toMap(RunningPlan::transactionId, Function.identity()));
        Map<UUID, FlexiblePurchase> plans = checkoutApi.flexiblePlansOf(running.keySet());
        Map<UUID, FlexibleStanding> standings = flexibleStandings.standingsOf(plans);
        return plans.entrySet().stream()
                .map(e -> {
                    RunningPlan r = running.get(e.getKey());
                    return new PortalFlexiblePlanDto(r.transactionId(), r.transactionReference(), r.estateId(),
                            r.estateName(), r.plotId(), r.plotLabel(), identityApi.emailOf(r.buyerUserId()).orElse(null),
                            r.planName(), r.currency().name(), e.getValue().planTotal(), standings.get(e.getKey()));
                })
                .sorted(Comparator.comparing((PortalFlexiblePlanDto p) -> !p.standing().noticeAllowedNow())
                        .thenComparing(p -> p.standing().notice() == null)
                        .thenComparing(p -> p.standing().quietFrom()))
                .toList();
    }

    private List<RunningPlan> runningPlans() {
        return checkoutApi.runningPlans(company());
    }

    private static UUID company() {
        TenantScope scope = TenantContext.get().orElseThrow();
        if (scope.tenantId() == null) {
            throw new PaymentException.CompanyScopeRequired();
        }
        return scope.tenantId();
    }

    private List<PortalPlanDto> dtos(List<RunningPlan> running) {
        Map<UUID, RunningPlan> plans = running.stream()
                .collect(Collectors.toMap(RunningPlan::transactionId, Function.identity()));
        Map<UUID, PlanSchedule> schedules = checkoutApi.schedulesOf(plans.keySet());
        LocalDate today = PlanStandings.today();
        Map<UUID, PlanStanding> standings = planStandings.standingsOf(schedules, today);
        // The plan started when finance verified its deposit — the payment's own record.
        Map<UUID, Instant> depositVerified = plans.isEmpty() ? Map.of()
                : payments.findAllByTransactionIdIn(plans.keySet()).stream()
                .filter(p -> p.getPurpose() == PaymentPurpose.DEPOSIT && p.getFinanceVerifiedAt() != null)
                .collect(Collectors.toMap(Payment::getTransactionId, Payment::getFinanceVerifiedAt, (a, b) -> a));
        return planStandings.progressOf(schedules).entrySet().stream()
                .map(e -> toDto(plans.get(e.getKey()), e.getValue(), standings.get(e.getKey()),
                        depositVerified.get(e.getKey())))
                .toList();
    }

    private PortalPlanDto toDto(RunningPlan plan, PlanProgress progress, PlanStanding standing,
                                Instant depositVerifiedAt) {
        PlanSchedule schedule = progress.schedule();
        return new PortalPlanDto(plan.transactionId(), plan.transactionReference(), plan.estateId(), plan.estateName(),
                plan.plotId(), plan.plotLabel(), identityApi.emailOf(plan.buyerUserId()).orElse(null),
                plan.planName(), plan.currency().name(), schedule.instalments().size(), progress.instalmentsPaid(),
                progress.next().map(PlanSchedule.Instalment::dueDate).orElse(null),
                progress.next().map(PlanSchedule.Instalment::amount).orElse(null), standing.status(),
                standing.arrears(), standing.revocation(),
                new PortalPlanDto.Deposit(schedule.deposit(), depositVerifiedAt), standing.instalments());
    }
}
