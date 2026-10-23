package com.techcomfort.landvaultbackend.marketplace.internal.service;

import com.techcomfort.landvaultbackend.common.LatePaymentTerms;
import com.techcomfort.landvaultbackend.marketplace.EstateEligibility;
import com.techcomfort.landvaultbackend.marketplace.MarketplaceApi;
import com.techcomfort.landvaultbackend.common.RefundAppliesTo;
import com.techcomfort.landvaultbackend.common.RevocationTerms;
import com.techcomfort.landvaultbackend.marketplace.OfferedPlan;
import com.techcomfort.landvaultbackend.marketplace.OfferedRefundTerms;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.PlotView;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.DefaultTermsViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.EstateEligibilityViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.PaymentPlanViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.PenaltyTierViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.PlotViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.RefundTermsViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MarketplaceApiImpl implements MarketplaceApi {

    private final EstateEligibilityViewRepository eligibility;
    private final PlotViewRepository plots;
    private final PaymentPlanViewRepository paymentPlans;
    private final RefundTermsViewRepository refundTerms;
    private final DefaultTermsViewRepository defaultTerms;
    private final PenaltyTierViewRepository penaltyTiers;

    @Override
    @Transactional(readOnly = true)
    public Optional<EstateEligibility> eligibilityOf(UUID estateId) {
        return eligibility.findById(estateId).map(v -> new EstateEligibility(
                v.isPublished(), v.isTenantVerified(), v.isTenantEntitled(), v.isTenantActive(),
                v.isFeesDeclared(), v.isRefundTermsDeclared(), v.isHasBoundary(), v.isHasPlots(), v.isHasPhoto(),
                v.isLetterApproved(), v.isEligible()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> estateIdOfPlot(UUID plotId) {
        return plots.findByPlotId(plotId).map(PlotView::getEstateId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OfferedPlan> offeredPlan(UUID estateId, UUID planId) {
        return paymentPlans.findByPlanIdAndEstateId(planId, estateId)
                .map(p -> new OfferedPlan(p.getPlanId(), p.getName(), p.terms()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OfferedRefundTerms> refundTermsOf(UUID estateId) {
        return refundTerms.findByEstateIdIn(List.of(estateId)).stream().findFirst()
                .map(t -> new OfferedRefundTerms(t.getDeductionPct(), RefundAppliesTo.fromValue(t.getAppliesTo())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LatePaymentTerms> latePaymentTermsOf(UUID estateId) {
        return defaultTerms.findByEstateIdIn(List.of(estateId)).stream().findFirst()
                .map(d -> new LatePaymentTerms(penaltyTiers.findByEstateIdInOrderByMonthsLateAsc(List.of(estateId))
                        .stream().map(t -> new LatePaymentTerms.Step(t.getMonthsLate(), t.getPenaltyPct())).toList(),
                        d.isPenaltiesRefundable(),
                        RevocationTerms.offered(d.getRevocationAfterMonths(), d.getRevocationNoticeDays())));
    }
}
