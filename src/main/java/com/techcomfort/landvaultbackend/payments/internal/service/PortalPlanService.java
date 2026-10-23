package com.techcomfort.landvaultbackend.payments.internal.service;

import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.checkout.PlanStanding;
import com.techcomfort.landvaultbackend.checkout.RunningPlan;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.payments.dto.PortalPlanDto;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional(readOnly = true)
    public List<PortalPlanDto> plans(String status) {
        if (status != null && !FILTERS.contains(status)) {
            throw new PaymentException.UnknownStatusFilter(status);
        }
        TenantScope scope = TenantContext.get().orElseThrow();
        if (scope.tenantId() == null) {
            throw new PaymentException.CompanyScopeRequired();
        }
        Map<UUID, RunningPlan> plans = checkoutApi.runningPlans(scope.tenantId()).stream()
                .collect(Collectors.toMap(RunningPlan::transactionId, Function.identity()));
        Map<UUID, PlanSchedule> schedules = checkoutApi.schedulesOf(plans.keySet());
        LocalDate today = PlanStandings.today();
        Map<UUID, PlanStanding> standings = planStandings.standingsOf(schedules, today);
        return planStandings.progressOf(schedules).entrySet().stream()
                .map(e -> toDto(plans.get(e.getKey()), e.getValue(), standings.get(e.getKey())))
                .filter(p -> status == null || status.equals(p.status())
                        || ("late".equals(status) && p.arrears() != null))
                .sorted(Comparator.comparingInt((PortalPlanDto p) -> URGENCY.indexOf(p.status()))
                        .thenComparing(PortalPlanDto::nextDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private PortalPlanDto toDto(RunningPlan plan, PlanProgress progress, PlanStanding standing) {
        PlanSchedule schedule = progress.schedule();
        return new PortalPlanDto(plan.transactionId(), plan.transactionReference(), plan.estateId(), plan.estateName(),
                plan.plotId(), plan.plotLabel(), identityApi.emailOf(plan.buyerUserId()).orElse(null),
                plan.planName(), plan.currency().name(), schedule.instalments().size(), progress.instalmentsPaid(),
                progress.next().map(PlanSchedule.Instalment::dueDate).orElse(null),
                progress.next().map(PlanSchedule.Instalment::amount).orElse(null), standing.status(),
                standing.arrears(), standing.revocation());
    }
}
