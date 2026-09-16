package com.techcomfort.landvaultbackend.tenancy.dto;

import java.util.List;
import java.util.UUID;

/**
 * The tenant detail page — matches the frontend's full {@code Tenant} shape
 * in {@code tenantsService.ts} as closely as the current schema allows. See
 * AGENTS.md for the real gaps found while building this: no stored primary
 * contact person ({@link #primaryContact()} is always {@code null}), no
 * stored "additional permits" or director-attestation flag
 * ({@link #directorsAttestation()} is inferred from verification state
 * having progressed past {@code CREATED}, not a stored fact), and
 * {@code plan}/{@code status}/{@code verificationState} are wire-value
 * strings, not the internal enum types — a public DTO must not expose an
 * internal type through its own signature.
 */
public record TenantDetailDto(
        UUID id,
        String status,
        String plan,
        TenantEntitlementsDto entitlements,
        List<TenantBranchDto> branches,
        String createdDate,

        CompanyIdentityDto identity,
        PrimaryContactDto primaryContact,
        CompanyPresenceDto presence,

        List<TenantDocumentDto> documents,
        RegulatoryDto regulatory,
        List<DirectorDto> directors,
        boolean directorsAttestation,
        FinancialSettlementDto financial,

        String verificationState,
        List<VerificationDecisionDto> verificationHistory
) {
}
