package com.techcomfort.landvaultbackend.tenancy.internal.service;

import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.TenantStatus;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.BranchRepository;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TenancyApiImpl implements TenancyApi {

    private final BranchRepository branchRepository;
    private final OrganizationRepository organizationRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean branchBelongsToTenant(UUID branchId, UUID tenantId) {
        if (branchId == null || tenantId == null) {
            return false;
        }
        return branchRepository.existsByIdAndOrganizationId(branchId, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isTenantActive(UUID tenantId) {
        if (tenantId == null) {
            return false;
        }
        return organizationRepository.findById(tenantId)
                .map(org -> org.getStatus() == TenantStatus.ACTIVE)
                .orElse(false);
    }
}
