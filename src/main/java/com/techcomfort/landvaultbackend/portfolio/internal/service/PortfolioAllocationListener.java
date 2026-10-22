package com.techcomfort.landvaultbackend.portfolio.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.PlotAllocated;
import com.techcomfort.landvaultbackend.portfolio.internal.domain.OwnedPlot;
import com.techcomfort.landvaultbackend.portfolio.internal.enums.OwnedPlotStatus;
import com.techcomfort.landvaultbackend.portfolio.internal.repository.OwnedPlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * Finance verified a sale: the plot becomes the buyer's. A plain synchronous
 * {@code @EventListener}, never Modulith's asynchronous one — the owned-plot
 * record must commit or roll back with the sale itself (FV-3). MANDATORY so
 * it fails loudly rather than ever running outside that transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioAllocationListener {

    private final OwnedPlotRepository ownedPlots;
    private final AuditApi auditApi;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(PlotAllocated event) {
        // Outright sales arrive paid in full; an instalment plan arrives at its
        // verified deposit (phase 3a) and stays active until the last
        // instalment. Anything else would be a plan the portfolio can't show.
        boolean onPlan = event.provisional() && "installment".equals(event.plan());
        if (!event.provisional() && "installment".equals(event.plan())) {
            completePlan(event);
            return;
        }
        if (!onPlan && !"outright".equals(event.plan())) {
            throw new IllegalStateException("Allocated a " + event.plan() + " sale; the portfolio knows outright "
                    + "sales and instalment plans at their deposit.");
        }
        OwnedPlot owned = ownedPlots.saveAndFlush(OwnedPlot.builder()
                .buyerUserId(event.buyerUserId())
                .transactionId(event.transactionId())
                .plotId(event.plotId())
                .estateId(event.estateId())
                .sellerTenantId(event.sellerTenantId())
                // Outright and verified: paid in full. On a plan: active until the last instalment.
                .status(onPlan ? OwnedPlotStatus.INSTALLMENT_ACTIVE : OwnedPlotStatus.COMPLETED)
                .plan(event.plan().toUpperCase(Locale.ROOT))
                .intent(event.intent())
                .basePrice(event.basePrice())
                .cornerPremiumPct(event.cornerPremiumPct())
                .totalPrice(event.totalPrice())
                .currency(event.currency())
                .acquiredAt(Instant.now())
                .build());
        auditApi.record(AuditEntryRequest.of(event.actorUserId(), "portfolio.plot_owned", "owned_plot", owned.getId(),
                event.sellerTenantId(), "Plot " + event.plotId() + (onPlan ? " is held on an instalment plan for buyer "
                        : " is now owned by buyer ") + event.buyerUserId()
                        + " (sale " + event.transactionId() + ")."));
        log.info("Owned plot {} recorded for sale {}", owned.getId(), event.transactionId());
    }

    /** 3b: the last instalment is verified — the plan's record is now owned outright. */
    private void completePlan(PlotAllocated event) {
        OwnedPlot owned = ownedPlots.findByTransactionIdAndBuyerUserId(event.transactionId(), event.buyerUserId())
                .filter(o -> o.getStatus() == OwnedPlotStatus.INSTALLMENT_ACTIVE)
                .orElseThrow(() -> new IllegalStateException("No active plan to complete for sale "
                        + event.transactionId()));
        owned.setStatus(OwnedPlotStatus.COMPLETED);
        ownedPlots.saveAndFlush(owned);
        auditApi.record(AuditEntryRequest.of(event.actorUserId(), "portfolio.plan_completed", "owned_plot",
                owned.getId(), event.sellerTenantId(), "Plan paid in full: plot " + event.plotId()
                        + " is now owned by buyer " + event.buyerUserId() + " (sale " + event.transactionId() + ")."));
        log.info("Owned plot {} completed for sale {}", owned.getId(), event.transactionId());
    }
}
