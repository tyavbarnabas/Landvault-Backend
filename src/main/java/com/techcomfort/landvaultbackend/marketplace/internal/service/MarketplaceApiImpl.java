package com.techcomfort.landvaultbackend.marketplace.internal.service;

import com.techcomfort.landvaultbackend.marketplace.EstateEligibility;
import com.techcomfort.landvaultbackend.marketplace.MarketplaceApi;
import com.techcomfort.landvaultbackend.marketplace.internal.domain.PlotView;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.EstateEligibilityViewRepository;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.PlotViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MarketplaceApiImpl implements MarketplaceApi {

    private final EstateEligibilityViewRepository eligibility;
    private final PlotViewRepository plots;

    @Override
    @Transactional(readOnly = true)
    public Optional<EstateEligibility> eligibilityOf(UUID estateId) {
        return eligibility.findById(estateId).map(v -> new EstateEligibility(
                v.isPublished(), v.isTenantVerified(), v.isTenantEntitled(), v.isTenantActive(),
                v.isFeesDeclared(), v.isRefundTermsDeclared(), v.isEligible()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> estateIdOfPlot(UUID plotId) {
        return plots.findByPlotId(plotId).map(PlotView::getEstateId);
    }
}
