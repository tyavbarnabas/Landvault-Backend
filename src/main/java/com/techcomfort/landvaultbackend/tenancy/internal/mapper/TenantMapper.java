package com.techcomfort.landvaultbackend.tenancy.internal.mapper;

import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.DirectorDto;
import com.techcomfort.landvaultbackend.tenancy.dto.FinancialSettlementDto;
import com.techcomfort.landvaultbackend.tenancy.dto.RegulatoryDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.StateRegulatorEntryDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SupportAccessGrantDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantBranchDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDocumentDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantEntitlementsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantSummaryDto;
import com.techcomfort.landvaultbackend.tenancy.dto.VerificationDecisionDto;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.CompanyType;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.TenantPlan;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.TenantStatus;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.VerificationState;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.Branch;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.Director;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.Organization;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.OrganizationDocument;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.OrganizationFinancial;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.OrganizationGateway;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.OrganizationRegulatory;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.OrganizationStateRegulator;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.SupportAccessGrant;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.VerificationDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Entity → wire-DTO translation for the tenant directory/detail endpoints —
 * see AGENTS.md for the real gaps this surfaces (no stored primary contact,
 * no director-attestation column, {@code statesOfOperation} derived rather
 * than stored) and why enum fields become wire-value strings here rather
 * than the internal enum types.
 * <p>
 * {@code reviewerName}/{@code managerName} are always {@code null} — both
 * entities store only a user id ({@code reviewerUserId}/{@code managerUserId}).
 * Resolving a name would mean calling {@code identity.IdentityApi}, but
 * {@code identity} already depends on {@code tenancy} (via {@code TenancyApi},
 * for the tenant-context filter's branch switcher) — {@code tenancy} also
 * depending on {@code identity} would be a genuine module dependency cycle,
 * and {@code ModularityTests} correctly rejects it (confirmed directly: it
 * was tried, and failed with exactly this "Cycle detected: Slice identity
 * -> Slice tenancy -> Slice identity" violation). See AGENTS.md.
 */
@Component
@RequiredArgsConstructor
public class TenantMapper {

    public TenantSummaryDto toSummary(Organization org, long branchCount) {
        return new TenantSummaryDto(
                org.getId(),
                displayName(org),
                null, // primaryContactName — see AGENTS.md, no such data exists
                org.getCompanyEmail(), // closest real substitute for primaryContactEmail — see AGENTS.md
                org.getPlan().getValue(),
                org.getVerificationState().getValue(),
                org.getStatus().getValue(),
                statesOfOperation(org, List.of()), // no state-regulator query per row — see AGENTS.md
                branchCount,
                isoDate(org.getCreatedAt()));
    }

    public TenantDetailDto toDetail(
            Organization org,
            List<Branch> branches,
            List<OrganizationDocument> documents,
            OrganizationRegulatory regulatory,
            List<OrganizationStateRegulator> stateRegulators,
            List<Director> directors,
            OrganizationFinancial financial,
            List<OrganizationGateway> gateways,
            List<VerificationDecision> decisions) {
        return new TenantDetailDto(
                org.getId(),
                org.getStatus().getValue(),
                org.getPlan().getValue(),
                new TenantEntitlementsDto(
                        Boolean.TRUE.equals(org.getMarketplacePublishing()),
                        Boolean.TRUE.equals(org.getMlmModule()),
                        Boolean.TRUE.equals(org.getFxRails())),
                branches.stream().map(TenantMapper::toBranchDto).toList(),
                isoDate(org.getCreatedAt()),

                toIdentity(org, stateRegulators),
                null, // primaryContact — see AGENTS.md and PrimaryContactDto's own Javadoc
                toPresence(org),

                documents.stream().map(TenantMapper::toDocumentDto).toList(),
                regulatory == null ? null : toRegulatory(regulatory, stateRegulators),
                directors.stream().map(TenantMapper::toDirector).toList(),
                // No stored attestation flag exists — inferred from having
                // progressed past CREATED, since Stage 2 submission (the
                // only way verificationState advances) always includes it
                // on the frontend's own SubmitVerificationInput. See AGENTS.md.
                org.getVerificationState() != VerificationState.CREATED,
                financial == null ? null : toFinancial(financial, gateways),

                org.getVerificationState().getValue(),
                decisions.stream().map(TenantMapper::toDecision).toList());
    }

    /**
     * {@code POST /api/admin/tenants}'s request -> a new, unsaved
     * {@link Organization} — the reverse direction of {@link #toDetail}
     * above. {@code status}/{@code verificationState} and the default
     * entitlements are fixed here, not read from the request — see
     * AGENTS.md Part 0 (the two axes are set independently, never derived)
     * and Part 1 (SA-1.3 plan/entitlement changes are out of scope for this
     * slice). Doesn't touch the database — {@code AdminTenantService} still
     * owns the duplicate-RC-number check and the actual save.
     */
    public Organization toNewOrganization(CreateTenantRequest request) {
        CompanyIdentityDto identity = request.identity();
        AddressDto registered = identity.registeredAddress();
        AddressDto operating = identity.operatingAddress();
        CompanyPresenceDto presence = request.presence();
        SocialsDto socials = presence.socials();

        return Organization.builder()
                .registeredName(identity.registeredName())
                .tradingName(identity.tradingName())
                .rcNumber(identity.rcNumber())
                .companyType(CompanyType.fromValue(identity.companyType()))
                .dateOfIncorporation(LocalDate.parse(identity.dateOfIncorporation()))
                .registeredStreet(registered.street())
                .registeredCity(registered.city())
                .registeredState(registered.state())
                .operatingStreet(operating.street())
                .operatingCity(operating.city())
                .operatingState(operating.state())
                .companyEmail(presence.companyEmail())
                .companyPhone(presence.companyPhone())
                .website(presence.website())
                .socialInstagram(socials == null ? null : socials.instagram())
                .socialTwitter(socials == null ? null : socials.twitter())
                .socialFacebook(socials == null ? null : socials.facebook())
                .socialLinkedin(socials == null ? null : socials.linkedin())
                .plan(TenantPlan.fromValue(request.plan()))
                // Default entitlements — matches the frontend's own
                // DEFAULT_ENTITLEMENTS.
                .marketplacePublishing(false)
                .mlmModule(false)
                .fxRails(false)
                .status(TenantStatus.ACTIVE)
                .verificationState(VerificationState.CREATED)
                .build();
    }

    private static String displayName(Organization org) {
        return (org.getTradingName() != null && !org.getTradingName().isBlank())
                ? org.getTradingName()
                : org.getRegisteredName();
    }

    // Summary: just the two real columns on Organization itself (no extra
    // query per directory row). Detail: also unions in every state the
    // organization is registered with a regulator in, since that list is
    // already loaded for the Regulatory section anyway. Neither is the
    // frontend's actual "states of operation" concept — there's no stored
    // list of that — see AGENTS.md.
    private static List<String> statesOfOperation(Organization org, List<OrganizationStateRegulator> stateRegulators) {
        Set<String> states = new TreeSet<>();
        if (org.getRegisteredState() != null) {
            states.add(org.getRegisteredState());
        }
        if (org.getOperatingState() != null) {
            states.add(org.getOperatingState());
        }
        stateRegulators.forEach(r -> states.add(r.getState()));
        return List.copyOf(states);
    }

    private static String isoDate(Instant instant) {
        return instant == null ? null : instant.atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private static TenantBranchDto toBranchDto(Branch branch) {
        // managerName: always null — see class Javadoc.
        return new TenantBranchDto(branch.getId(), branch.getName(), null, 0L);
    }

    private static CompanyIdentityDto toIdentity(Organization org, List<OrganizationStateRegulator> stateRegulators) {
        return new CompanyIdentityDto(
                org.getRegisteredName(),
                org.getTradingName(),
                org.getRcNumber(),
                org.getCompanyType().getValue(),
                org.getDateOfIncorporation() == null ? null : org.getDateOfIncorporation().toString(),
                new AddressDto(org.getRegisteredStreet(), org.getRegisteredCity(), org.getRegisteredState()),
                new AddressDto(org.getOperatingStreet(), org.getOperatingCity(), org.getOperatingState()),
                statesOfOperation(org, stateRegulators));
    }

    private static CompanyPresenceDto toPresence(Organization org) {
        return new CompanyPresenceDto(
                org.getCompanyEmail(),
                org.getCompanyPhone(),
                org.getWebsite(),
                new SocialsDto(org.getSocialInstagram(), org.getSocialTwitter(), org.getSocialFacebook(), org.getSocialLinkedin()));
    }

    private static TenantDocumentDto toDocumentDto(OrganizationDocument document) {
        return new TenantDocumentDto(
                document.getId(),
                document.getType().getValue(),
                document.getFileName(),
                document.getSize() == null ? 0 : document.getSize(),
                document.getStatus().getValue(),
                document.getRejectionReason(),
                document.getUploadedAt());
    }

    private static RegulatoryDto toRegulatory(OrganizationRegulatory regulatory, List<OrganizationStateRegulator> stateRegulators) {
        return new RegulatoryDto(
                regulatory.getScumlNumber(),
                stateRegulators.stream()
                        .map(r -> new StateRegulatorEntryDto(r.getId(), r.getState(), r.getRegulatorName(), r.getRegistrationNumber(), r.getDocumentId()))
                        .toList(),
                regulatory.getRedanNumber(),
                List.of()); // additionalPermits — no backing table, see AGENTS.md
    }

    private static DirectorDto toDirector(Director director) {
        return new DirectorDto(
                director.getId(),
                director.getFullName(),
                director.getRole(),
                director.getNationality(),
                director.getIdType().getValue(),
                mask(director.getIdNumber()),
                director.getOwnershipPct(),
                Boolean.TRUE.equals(director.getIsBeneficialOwner()));
    }

    // NDPR-regulated — never returned in full. Reveal is a separate,
    // not-yet-built, access-logged action. See AGENTS.md and DirectorDto's
    // Javadoc. Still needed for idNumber even though bvn (its original
    // co-user) is gone — don't delete this thinking it's now unused.
    private static String mask(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 4) {
            return "*".repeat(value.length());
        }
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }

    private static FinancialSettlementDto toFinancial(OrganizationFinancial financial, List<OrganizationGateway> gateways) {
        return new FinancialSettlementDto(
                financial.getBankName(),
                financial.getAccountNumber(),
                financial.getAccountName(),
                financial.getSettlementCurrency(),
                gateways.stream().collect(java.util.stream.Collectors.toMap(
                        g -> g.getGatewayName().getValue(), g -> g.getStatus().getValue(), (a, b) -> a, java.util.LinkedHashMap::new)));
    }

    public static SupportAccessGrantDto toSupportAccessGrantDto(SupportAccessGrant grant) {
        return new SupportAccessGrantDto(grant.getId(), grant.getOrganizationId(), grant.getReason(), grant.getRequestedAt(), grant.getExpiresAt());
    }

    private static VerificationDecisionDto toDecision(VerificationDecision decision) {
        return new VerificationDecisionDto(
                decision.getId(),
                null, // reviewerName — always null, see class Javadoc
                decision.getDecidedAt(),
                decision.getDecision().getValue(),
                decision.getReason(),
                null); // failedDocumentIds — see AGENTS.md and this DTO's own Javadoc
    }
}
