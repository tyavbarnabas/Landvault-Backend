package com.techcomfort.landvaultbackend.tenancy.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.tenancy.TenantStaffAccountRequested;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateSupportAccessGrantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.ResubmitDocumentRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.SupportAccessGrantDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantPlanUpdateRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantStatusRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantSummaryDto;
import com.techcomfort.landvaultbackend.tenancy.dto.VerificationDecisionRequest;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.Organization;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.OrganizationDocument;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.SupportAccessGrant;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.VerificationDecision;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.VerificationDecisionDocument;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.DocumentStatus;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.TenantPlan;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.TenantStatus;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.VerificationDecisionType;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.VerificationState;
import com.techcomfort.landvaultbackend.tenancy.internal.exceptions.TenancyException;
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
import com.techcomfort.landvaultbackend.tenancy.internal.repository.SupportAccessGrantRepository;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.VerificationDecisionDocumentRepository;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.VerificationDecisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Orchestrates the tenant directory/detail reads, tenant creation, and the verification-lifecycle write endpoints. */
@Slf4j
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
    private final VerificationDecisionDocumentRepository verificationDecisionDocumentRepository;
    private final SupportAccessGrantRepository supportAccessGrantRepository;
    private final TenantMapper mapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditApi auditApi;

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
        return organizationRepository.findById(id).map(this::toDetailDto);
    }

    /**
     * Creates the tenant (org + its first Executive Director account + an
     * audit entry) in one transaction — see AGENTS.md Part 1. The ED
     * account itself is created in {@code identity}, via a synchronous
     * {@link TenantStaffAccountRequested} event so this method never
     * imports anything from {@code identity} — see that event's Javadoc for
     * why. If the listener throws (e.g. the seeded role is missing), this
     * whole method rolls back, organization included, since the listener
     * runs in the same transaction.
     */
    @Transactional
    public TenantDetailDto createTenant(CreateTenantRequest request, UUID actorUserId) {
        if (organizationRepository.existsByRcNumberIgnoreCase(request.identity().rcNumber())) {
            throw new TenancyException.DuplicateRcNumber();
        }

        Organization organization = mapper.toNewOrganization(request);
        try {
            organization = organizationRepository.save(organization);
        } catch (DataIntegrityViolationException e) {
            // Lost the race against a concurrent create with the same RC
            // number — the proactive check above missed it. See changeset
            // 027 and TenancyException.DuplicateRcNumber.
            throw new TenancyException.DuplicateRcNumber();
        }

        PrimaryContactDto contact = request.primaryContact();
        String[] name = splitFullName(contact.fullName());
        eventPublisher.publishEvent(new TenantStaffAccountRequested(
                organization.getId(), "executive_director", name[0], name[1], contact.workEmail(), contact.phone()));

        auditApi.record(AuditEntryRequest.of(
                actorUserId, "tenant.created", "organization", organization.getId(), organization.getId(),
                "Tenant '" + organization.getRegisteredName() + "' created."));

        log.info("Tenant created: '{}' (rcNumber={}, id={}) by actor {}",
                organization.getRegisteredName(), organization.getRcNumber(), organization.getId(), actorUserId);
        return toDetailDto(organization);
    }

    /** {@code CREATED} -> {@code DOCUMENTS_SUBMITTED}; requires >=1 uploaded {@link OrganizationDocument}. See AGENTS.md Part 2. */
    @Transactional
    public TenantDetailDto submitDocuments(UUID tenantId) {
        Organization organization = organizationRepository.findById(tenantId)
                .orElseThrow(TenancyException.TenantNotFound::new);

        if (organization.getVerificationState() != VerificationState.CREATED) {
            throw new TenancyException.InvalidVerificationTransition(
                    "Documents can only be submitted from the 'created' state (current: "
                            + organization.getVerificationState().getValue() + ").");
        }
        if (!organizationDocumentRepository.existsByOrganizationId(tenantId)) {
            throw new TenancyException.NoDocumentsUploaded();
        }

        organization.setVerificationState(VerificationState.DOCUMENTS_SUBMITTED);
        organizationRepository.save(organization);
        log.info("Tenant {} submitted documents for review", tenantId);
        return toDetailDto(organization);
    }

    /** {@code DOCUMENTS_SUBMITTED} -> {@code UNDER_REVIEW}; records the reviewer who began it. */
    @Transactional
    public TenantDetailDto beginReview(UUID tenantId, UUID reviewerUserId) {
        Organization organization = organizationRepository.findById(tenantId)
                .orElseThrow(TenancyException.TenantNotFound::new);

        if (organization.getVerificationState() != VerificationState.DOCUMENTS_SUBMITTED) {
            throw new TenancyException.InvalidVerificationTransition(
                    "Review can only begin from the 'documents_submitted' state (current: "
                            + organization.getVerificationState().getValue() + ").");
        }

        organization.setVerificationState(VerificationState.UNDER_REVIEW);
        organization.setReviewerUserId(reviewerUserId);
        organizationRepository.save(organization);
        log.info("Tenant {} review begun by reviewer {}", tenantId, reviewerUserId);
        return toDetailDto(organization);
    }

    /**
     * Always appends a new {@link VerificationDecision} row. {@code APPROVED}
     * -> {@code VERIFIED}, marking every {@link OrganizationDocument} on the
     * tenant {@code VERIFIED} too; {@code REJECTED} -> {@code REJECTED},
     * marking the named {@code failedDocumentIds} {@code REJECTED} (with the
     * decision's reason) and every other document {@code VERIFIED};
     * {@code REQUEST_MORE_INFO} leaves {@code verificationState} AND every
     * document's status untouched — see AGENTS.md's "REQUEST_MORE_INFO
     * doesn't transition verification state" note. {@code reviewerUserId}
     * is the authenticated caller, never client-supplied — see
     * {@link VerificationDecisionRequest}'s Javadoc.
     */
    @Transactional
    public TenantDetailDto recordVerificationDecision(UUID tenantId, VerificationDecisionRequest request, UUID reviewerUserId) {
        Organization organization = organizationRepository.findById(tenantId)
                .orElseThrow(TenancyException.TenantNotFound::new);

        if (organization.getVerificationState() != VerificationState.UNDER_REVIEW) {
            throw new TenancyException.InvalidVerificationTransition(
                    "A verification decision can only be recorded while a tenant is 'under_review' (current: "
                            + organization.getVerificationState().getValue() + ").");
        }

        VerificationDecisionType decisionType = VerificationDecisionType.fromValue(request.decision());
        boolean reasonRequired = decisionType == VerificationDecisionType.REJECTED
                || decisionType == VerificationDecisionType.REQUEST_MORE_INFO;
        if (reasonRequired && (request.reason() == null || request.reason().isBlank())) {
            throw new TenancyException.DecisionReasonRequired();
        }

        VerificationDecision decision = VerificationDecision.builder()
                .organizationId(tenantId)
                .reviewerUserId(reviewerUserId)
                .decision(decisionType)
                .reason(request.reason())
                .decidedAt(Instant.now())
                .build();
        decision = verificationDecisionRepository.save(decision);

        if (request.failedDocumentIds() != null) {
            for (UUID documentId : request.failedDocumentIds()) {
                verificationDecisionDocumentRepository.save(VerificationDecisionDocument.builder()
                        .decisionId(decision.getId())
                        .documentId(documentId)
                        .build());
            }
        }

        String auditAction = switch (decisionType) {
            case APPROVED -> {
                organization.setVerificationState(VerificationState.VERIFIED);
                markAllDocumentsVerified(tenantId);
                yield "tenant.verification_approved";
            }
            case REJECTED -> {
                organization.setVerificationState(VerificationState.REJECTED);
                markDocumentsForRejection(tenantId, request.failedDocumentIds(), request.reason());
                yield "tenant.verification_rejected";
            }
            // REQUEST_MORE_INFO: verificationState AND document statuses deliberately untouched — see this method's Javadoc.
            case REQUEST_MORE_INFO -> "tenant.verification_more_info_requested";
        };
        organizationRepository.save(organization);

        auditApi.record(AuditEntryRequest.of(
                reviewerUserId, auditAction, "organization", organization.getId(), organization.getId(), request.reason()));

        log.info("Tenant {} verification decision recorded: {} by reviewer {}", tenantId, decisionType, reviewerUserId);
        return toDetailDto(organization);
    }

    /**
     * Replaces one document's metadata and resets its {@code status} to
     * {@code PENDING}. Does NOT itself change {@code verificationState} —
     * {@code submit-documents}/{@code begin-review} still need to run again
     * afterward; see AGENTS.md.
     */
    @Transactional
    public TenantDetailDto resubmitDocument(UUID tenantId, UUID documentId, ResubmitDocumentRequest request) {
        Organization organization = organizationRepository.findById(tenantId)
                .orElseThrow(TenancyException.TenantNotFound::new);
        OrganizationDocument document = organizationDocumentRepository.findById(documentId)
                .filter(d -> d.getOrganizationId().equals(tenantId))
                .orElseThrow(TenancyException.DocumentNotFound::new);

        document.setFileName(request.fileName());
        document.setSize(request.size());
        document.setStorageKey(null); // no file storage integration yet — see OrganizationDocument.storageKey
        document.setStatus(DocumentStatus.PENDING);
        document.setRejectionReason(null);
        document.setUploadedAt(Instant.now());
        organizationDocumentRepository.save(document);

        log.info("Tenant {} document {} resubmitted", tenantId, documentId);
        return toDetailDto(organization);
    }

    /**
     * Changes {@code TenantStatus} — entirely independent of
     * {@code VerificationState}, which this method never reads or writes;
     * see AGENTS.md Part 0. {@code OFFBOARDED} is terminal: no transition
     * away from it is ever allowed. Setting the same status the tenant
     * already has is rejected too, not silently accepted — it usually
     * means the caller has stale data. {@code reason} is required for
     * {@code SUSPENDED}/{@code OFFBOARDED}, same service-layer-not-DB-constraint
     * pattern as the verification decision reason rule.
     */
    @Transactional
    public TenantDetailDto changeStatus(UUID tenantId, TenantStatusRequest request, UUID actorUserId) {
        Organization organization = organizationRepository.findById(tenantId)
                .orElseThrow(TenancyException.TenantNotFound::new);

        TenantStatus currentStatus = organization.getStatus();
        TenantStatus newStatus = TenantStatus.fromValue(request.status());

        if (currentStatus == TenantStatus.OFFBOARDED) {
            throw new TenancyException.InvalidStatusTransition(
                    "This tenant has been offboarded — offboarding is terminal, it cannot transition to any other status.");
        }
        if (newStatus == currentStatus) {
            throw new TenancyException.InvalidStatusTransition(
                    "Tenant is already '" + currentStatus.getValue() + "'.");
        }
        boolean reasonRequired = newStatus == TenantStatus.SUSPENDED || newStatus == TenantStatus.OFFBOARDED;
        if (reasonRequired && (request.reason() == null || request.reason().isBlank())) {
            throw new TenancyException.StatusReasonRequired();
        }

        organization.setStatus(newStatus);
        organizationRepository.save(organization);

        auditApi.record(AuditEntryRequest.of(
                actorUserId, "tenant.status_changed", "organization", organization.getId(), organization.getId(),
                "Status changed from '" + currentStatus.getValue() + "' to '" + newStatus.getValue() + "'"
                        + (request.reason() == null || request.reason().isBlank() ? "" : " — " + request.reason())));

        log.info("Tenant {} status changed: {} -> {} by actor {}", tenantId, currentStatus, newStatus, actorUserId);
        return toDetailDto(organization);
    }

    /**
     * Changes plan and entitlements — independent of both
     * {@code TenantStatus} and {@code VerificationState}; callable
     * regardless of either (e.g. sales negotiating terms on a still-unverified
     * tenant). No cross-validation between plan tier and entitlements —
     * that rule doesn't exist anywhere in AGENTS.md or the frontend, so
     * none is invented here.
     */
    @Transactional
    public TenantDetailDto updatePlan(UUID tenantId, TenantPlanUpdateRequest request, UUID actorUserId) {
        Organization organization = organizationRepository.findById(tenantId)
                .orElseThrow(TenancyException.TenantNotFound::new);

        TenantPlan oldPlan = organization.getPlan();
        boolean oldMarketplacePublishing = Boolean.TRUE.equals(organization.getMarketplacePublishing());
        boolean oldMlmModule = Boolean.TRUE.equals(organization.getMlmModule());
        boolean oldFxRails = Boolean.TRUE.equals(organization.getFxRails());
        TenantPlan newPlan = TenantPlan.fromValue(request.plan());

        organization.setPlan(newPlan);
        organization.setMarketplacePublishing(request.marketplacePublishing());
        organization.setMlmModule(request.mlmModule());
        organization.setFxRails(request.fxRails());
        organizationRepository.save(organization);

        String detail = "Plan changed from '%s' to '%s'; entitlements marketplacePublishing %s->%s, mlmModule %s->%s, fxRails %s->%s"
                .formatted(oldPlan.getValue(), newPlan.getValue(),
                        oldMarketplacePublishing, request.marketplacePublishing(),
                        oldMlmModule, request.mlmModule(),
                        oldFxRails, request.fxRails());
        auditApi.record(AuditEntryRequest.of(
                actorUserId, "tenant.plan_changed", "organization", organization.getId(), organization.getId(), detail));

        log.info("Tenant {} plan changed: {} -> {} by actor {}", tenantId, oldPlan, newPlan, actorUserId);
        return toDetailDto(organization);
    }

    /**
     * Records that support access was requested — a pure audit record, not
     * a gate. {@code grantedToUserId} is the authenticated caller, never
     * client-supplied; the audit entry is deliberately {@code privileged = true}.
     * See this class's own Javadoc and AGENTS.md — this does not, and must
     * not, itself open any door a Super Admin's existing {@code admin.tenants.manage}
     * authority and platform-scope RLS bypass don't already open.
     */
    @Transactional
    public SupportAccessGrantDto grantSupportAccess(UUID tenantId, CreateSupportAccessGrantRequest request, UUID actorUserId) {
        if (!organizationRepository.existsById(tenantId)) {
            throw new TenancyException.TenantNotFound();
        }

        int durationMinutes = request.durationMinutes() == null ? 30 : request.durationMinutes();
        Instant requestedAt = Instant.now();

        SupportAccessGrant grant = SupportAccessGrant.builder()
                .organizationId(tenantId)
                .grantedToUserId(actorUserId)
                .reason(request.reason())
                .requestedAt(requestedAt)
                .expiresAt(requestedAt.plus(Duration.ofMinutes(durationMinutes)))
                .build();
        grant = supportAccessGrantRepository.save(grant);

        auditApi.record(new AuditEntryRequest(
                actorUserId, "tenant.support_access_granted", "organization", tenantId, tenantId, request.reason(), true));

        log.info("Support access granted for tenant {} to {} ({} minutes)", tenantId, actorUserId, durationMinutes);
        return TenantMapper.toSupportAccessGrantDto(grant);
    }

    @Transactional(readOnly = true)
    public List<SupportAccessGrantDto> listSupportAccessGrants(UUID tenantId) {
        if (!organizationRepository.existsById(tenantId)) {
            throw new TenancyException.TenantNotFound();
        }
        return supportAccessGrantRepository.findByOrganizationIdOrderByRequestedAtDesc(tenantId).stream()
                .map(TenantMapper::toSupportAccessGrantDto)
                .toList();
    }

    private void markAllDocumentsVerified(UUID tenantId) {
        List<OrganizationDocument> documents = organizationDocumentRepository.findByOrganizationId(tenantId);
        documents.forEach(document -> {
            document.setStatus(DocumentStatus.VERIFIED);
            document.setRejectionReason(null);
        });
        organizationDocumentRepository.saveAll(documents);
    }

    // Matches the real frontend's own recordVerificationDecision mock logic
    // (tenantsService.ts): the documents named in failedDocumentIds are
    // REJECTED with the decision's reason; every other document on the
    // tenant is VERIFIED — a rejection is a statement about specific
    // evidence, not the whole document set.
    private void markDocumentsForRejection(UUID tenantId, List<UUID> failedDocumentIds, String reason) {
        Set<UUID> failed = failedDocumentIds == null ? Set.of() : new HashSet<>(failedDocumentIds);
        List<OrganizationDocument> documents = organizationDocumentRepository.findByOrganizationId(tenantId);
        documents.forEach(document -> {
            if (failed.contains(document.getId())) {
                document.setStatus(DocumentStatus.REJECTED);
                document.setRejectionReason(reason);
            } else {
                document.setStatus(DocumentStatus.VERIFIED);
                document.setRejectionReason(null);
            }
        });
        organizationDocumentRepository.saveAll(documents);
    }

    private TenantDetailDto toDetailDto(Organization organization) {
        UUID id = organization.getId();
        return mapper.toDetail(
                organization,
                branchRepository.findByOrganizationId(id),
                organizationDocumentRepository.findByOrganizationId(id),
                organizationRegulatoryRepository.findByOrganizationId(id).orElse(null),
                organizationStateRegulatorRepository.findByOrganizationId(id),
                directorRepository.findByOrganizationId(id),
                organizationFinancialRepository.findByOrganizationId(id).orElse(null),
                organizationGatewayRepository.findByOrganizationId(id),
                verificationDecisionRepository.findByOrganizationIdOrderByDecidedAtAsc(id));
    }

    // Splits on the last space so a multi-word given name ("Mary Ann") stays
    // together and the surname ("Smith") is what lands in lastName. A
    // single-word full name yields an empty (not null) lastName — User's
    // column is NOT NULL, not "must be non-blank".
    private static String[] splitFullName(String fullName) {
        String trimmed = fullName == null ? "" : fullName.trim();
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace < 0) {
            return new String[]{trimmed, ""};
        }
        return new String[]{trimmed.substring(0, lastSpace).trim(), trimmed.substring(lastSpace + 1).trim()};
    }
}
