package com.techcomfort.landvaultbackend.marketplace.internal.service;

import com.techcomfort.landvaultbackend.marketplace.EstateEligibility;
import com.techcomfort.landvaultbackend.marketplace.MarketplaceApi;
import com.techcomfort.landvaultbackend.marketplace.internal.repository.EstateEligibilityViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MarketplaceApiImpl implements MarketplaceApi {

    private final EstateEligibilityViewRepository eligibility;

    @Override
    @Transactional(readOnly = true)
    public Optional<EstateEligibility> eligibilityOf(UUID estateId) {
        return eligibility.findById(estateId).map(v -> new EstateEligibility(
                v.isPublished(), v.isTenantVerified(), v.isTenantEntitled(), v.isTenantActive(), v.isEligible()));
    }
}
