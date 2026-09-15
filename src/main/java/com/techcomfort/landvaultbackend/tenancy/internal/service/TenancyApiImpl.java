package com.techcomfort.landvaultbackend.tenancy.internal.service;

import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.BranchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TenancyApiImpl implements TenancyApi {

    private final BranchRepository branchRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean branchBelongsToTenant(UUID branchId, UUID tenantId) {
        if (branchId == null || tenantId == null) {
            return false;
        }
        return branchRepository.existsByIdAndOrganizationId(branchId, tenantId);
    }
}
