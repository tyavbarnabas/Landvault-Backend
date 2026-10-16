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
        if (!"outright".equals(event.plan())) {
            // Installments can't be paid for yet, so nothing can be allocated on one. If that changes
            // without the portfolio learning schedules, refuse rather than record a plan it can't show.
            throw new IllegalStateException("Allocated a " + event.plan() + " sale; the portfolio only knows outright ones.");
        }
        OwnedPlot owned = ownedPlots.saveAndFlush(OwnedPlot.builder()
                .buyerUserId(event.buyerUserId())
                .transactionId(event.transactionId())
                .plotId(event.plotId())
                .estateId(event.estateId())
                .sellerTenantId(event.sellerTenantId())
                // Outright and verified: paid in full. ALLOCATED waits for issued documents.
                .status(OwnedPlotStatus.COMPLETED)
                .plan(event.plan().toUpperCase(Locale.ROOT))
                .intent(event.intent())
                .basePrice(event.basePrice())
                .cornerPremiumPct(event.cornerPremiumPct())
                .totalPrice(event.totalPrice())
                .currency(event.currency())
                .acquiredAt(Instant.now())
                .build());
        auditApi.record(AuditEntryRequest.of(event.actorUserId(), "portfolio.plot_owned", "owned_plot", owned.getId(),
                event.sellerTenantId(), "Plot " + event.plotId() + " is now owned by buyer " + event.buyerUserId()
                        + " (sale " + event.transactionId() + ")."));
        log.info("Owned plot {} recorded for sale {}", owned.getId(), event.transactionId());
    }
}
