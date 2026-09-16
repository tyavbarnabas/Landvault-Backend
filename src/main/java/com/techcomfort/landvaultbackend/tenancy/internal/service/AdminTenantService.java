package com.techcomfort.landvaultbackend.tenancy.internal.service;

import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantSummaryDto;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.TenantPlan;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.VerificationState;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.Organization;


import com.techcomfort.landvaultbackend.tenancy.internal.mapper.TenantMapper;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.BranchCountProjection;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.BranchRepository;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.DirectorRepository;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.OrganizationDocumentRepository;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.OrganizationFinancialRepository;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.OrganizationGatewayRepository;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.OrganizationRegulatoryRepository;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.OrganizationRepository;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.OrganizationSpecifications;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.OrganizationStateRegulatorRepository;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.VerificationDecisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Orchestrates the two read endpoints — filtering/paging the directory, assembling one tenant's full detail. */
@Service
@RequiredArgsConstructor
public class AdminTenantService {

    private final OrganizationRepository organizationRepository;
    private final BranchRepository branchRepository;
    private final OrganizationDocumentRepository organizationDocumentRepository;
    private final OrganizationRegulatoryRepository organizationRegulatoryRepository;
    private final OrganizationStateRegulatorRepository organizationStateRegulatorRepository;
    private final DirectorRepository directorRepository;
    private final OrganizationFinancialRepository organizationFinancialRepository;
    private final OrganizationGatewayRepository organizationGatewayRepository;
    private final VerificationDecisionRepository verificationDecisionRepository;
    private final TenantMapper mapper;

    @Transactional(readOnly = true)
    public PageResponse<TenantSummaryDto> listTenants(
            String query, List<VerificationState> verificationStates, TenantPlan plan, String state,
            Instant createdAfter, Pageable pageable) {
        var spec = OrganizationSpecifications.matching(query, verificationStates, plan, state, createdAfter);
        Page<Organization> page = organizationRepository.findAll(spec, pageable);

        // One grouped query for the whole page's branch counts, not one
        // COUNT per row — see AGENTS.md's "avoid N+1 on the directory" note.
        List<UUID> orgIds = page.getContent().stream().map(Organization::getId).toList();
        Map<UUID, Long> branchCounts = branchRepository.countGroupedByOrganizationId(orgIds).stream()
                .collect(Collectors.toMap(BranchCountProjection::organizationId, BranchCountProjection::branchCount));

        return PageResponses.from(page, org -> mapper.toSummary(org, branchCounts.getOrDefault(org.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public Optional<TenantDetailDto> getTenantDetail(UUID id) {
        return organizationRepository.findById(id).map(org -> mapper.toDetail(
                org,
                branchRepository.findByOrganizationId(id),
                organizationDocumentRepository.findByOrganizationId(id),
                organizationRegulatoryRepository.findByOrganizationId(id).orElse(null),
                organizationStateRegulatorRepository.findByOrganizationId(id),
                directorRepository.findByOrganizationId(id),
                organizationFinancialRepository.findByOrganizationId(id).orElse(null),
                organizationGatewayRepository.findByOrganizationId(id),
                verificationDecisionRepository.findByOrganizationIdOrderByDecidedAtAsc(id)));
    }
}
