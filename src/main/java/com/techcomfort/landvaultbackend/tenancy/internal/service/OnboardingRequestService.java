package com.techcomfort.landvaultbackend.tenancy.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.NigerianStates;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.OnboardingRequestDto;
import com.techcomfort.landvaultbackend.tenancy.dto.OnboardingRequestSubmission;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.OnboardingRequest;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.OnboardingRequestStatus;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.PreferredContactMethod;
import com.techcomfort.landvaultbackend.tenancy.internal.exceptions.TenancyException;
import com.techcomfort.landvaultbackend.tenancy.internal.repository.OnboardingRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The public "request onboarding" form and the Super Admin queue behind it.
 * A request grants nothing; converting one runs the ordinary tenant creation
 * in the same transaction. See AGENTS.md, "Onboarding requests".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingRequestService {

    static final String OPEN_EMAIL_CONSTRAINT = "uq_onboarding_requests_open_email";

    private final OnboardingRequestRepository repository;
    private final AdminTenantService adminTenantService;
    private final NigerianStates nigerianStates;
    private final OnboardingRequestMailer mailer;
    private final OnboardingProperties properties;
    private final AuditApi auditApi;

    /**
     * Stores the request and, after commit, emails HomeTrue and the address
     * given. An email that already has an open request throws
     * {@link TenancyException.OnboardingRequestAlreadyOpen}, which the caller
     * must answer exactly like a success.
     * <p>
     * The duplicate is found by the unique index, not by a query: the caller
     * is anonymous, and under RLS may insert a request but never read one.
     */
    @Transactional
    public void submit(OnboardingRequestSubmission submission) {
        PreferredContactMethod method = PreferredContactMethod.fromValue(submission.preferredContactMethod().trim());
        OnboardingRequest request = OnboardingRequest.builder()
                .companyName(submission.companyName().trim())
                .rcNumber(blankToNull(submission.rcNumber()))
                .companyAddress(blankToNull(submission.companyAddress()))
                .statesOfOperation(canonicalStates(submission.statesOfOperation()))
                .estimatedEstateCount(submission.estimatedEstateCount())
                .contactName(submission.contactName().trim())
                .contactRole(blankToNull(submission.contactRole()))
                .contactEmail(submission.contactEmail().trim().toLowerCase(Locale.ROOT))
                .contactPhone(submission.contactPhone().trim())
                .preferredContactMethod(method)
                .preferredContactTime(blankToNull(submission.preferredContactTime()))
                .message(blankToNull(submission.message()))
                .status(OnboardingRequestStatus.NEW)
                .build();
        try {
            request = repository.saveAndFlush(request);
        } catch (DataIntegrityViolationException e) {
            if (OPEN_EMAIL_CONSTRAINT.equals(constraintNameOf(e))) {
                log.info("Onboarding request dropped: an open request already exists for that email");
                throw new TenancyException.OnboardingRequestAlreadyOpen();
            }
            throw e;
        }

        // No person acted: the submitter has no account, so this is a system entry.
        auditApi.record(AuditEntryRequest.of(null, "onboarding_request.received", "onboarding_request",
                request.getId(), null, "Onboarding request from '" + request.getCompanyName() + "'."));
        log.info("Onboarding request received: '{}' (id={})", request.getCompanyName(), request.getId());

        OnboardingRequest saved = request;
        afterCommit("onboarding alert", () -> mailer.requestReceived(alertFor(saved)));
        afterCommit("onboarding confirmation", () -> mailer.confirmation(saved.getContactEmail()));
    }

    /**
     * Newest first; with {@code attentionOnly}, only requests needing attention,
     * waiting longest first. Filtered in the query, so pages and totals stay right.
     */
    @Transactional(readOnly = true)
    public PageResponse<OnboardingRequestDto> list(OnboardingRequestStatus status, boolean attentionOnly,
                                                   Pageable pageable) {
        Instant cutoff = attentionCutoff();
        Page<OnboardingRequest> page;
        if (attentionOnly) {
            // Only a new request can need attention today.
            page = status == null || status == OnboardingRequestStatus.NEW
                    ? repository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(OnboardingRequestStatus.NEW,
                            cutoff, pageable)
                    : Page.empty(pageable);
        } else {
            page = status == null
                    ? repository.findAllByOrderByCreatedAtDesc(pageable)
                    : repository.findByStatusOrderByCreatedAtDesc(status, pageable);
        }
        return PageResponses.from(page, r -> toDto(r, cutoff));
    }

    @Transactional(readOnly = true)
    public OnboardingRequestDto get(UUID id) {
        return repository.findById(id).map(this::toDto)
                .orElseThrow(TenancyException.OnboardingRequestNotFound::new);
    }

    /** Records that HomeTrue reached the company. Repeatable while open; the latest contact wins. */
    @Transactional
    public OnboardingRequestDto markContacted(UUID id, String note, UUID actorUserId) {
        OnboardingRequest request = lockOpen(id);
        request.setStatus(OnboardingRequestStatus.CONTACTED);
        request.setContactedAt(Instant.now());
        request.setContactedBy(actorUserId);
        request.setContactNote(blankToNull(note));
        repository.save(request);
        auditApi.record(AuditEntryRequest.of(actorUserId, "onboarding_request.contacted", "onboarding_request",
                id, null, "Contacted '" + request.getCompanyName() + "'."
                        + (request.getContactNote() == null ? "" : " Note: " + request.getContactNote())));
        return toDto(request);
    }

    /** Final. The reason is kept for HomeTrue and never sent to the company. */
    @Transactional
    public OnboardingRequestDto decline(UUID id, String reason, UUID actorUserId) {
        OnboardingRequest request = lockOpen(id);
        request.setStatus(OnboardingRequestStatus.DECLINED);
        request.setDecidedAt(Instant.now());
        request.setDecidedBy(actorUserId);
        request.setDeclineReason(reason.trim());
        repository.save(request);
        auditApi.record(AuditEntryRequest.of(actorUserId, "onboarding_request.declined", "onboarding_request",
                id, null, "Declined '" + request.getCompanyName() + "': " + request.getDeclineReason()));
        return toDto(request);
    }

    /**
     * Creates the tenant through the ordinary path ({@link AdminTenantService#createTenant}:
     * same checks, same first-director invitation) and marks the request
     * converted, in one transaction: if creation fails, the request stays open.
     */
    @Transactional
    public OnboardingRequestDto convert(UUID id, CreateTenantRequest tenant, UUID actorUserId) {
        OnboardingRequest request = lockOpen(id);
        TenantDetailDto created = adminTenantService.createTenant(tenant, actorUserId);
        request.setStatus(OnboardingRequestStatus.CONVERTED);
        request.setDecidedAt(Instant.now());
        request.setDecidedBy(actorUserId);
        request.setOrganizationId(created.id());
        repository.save(request);
        auditApi.record(AuditEntryRequest.of(actorUserId, "onboarding_request.converted", "onboarding_request",
                id, created.id(), "Converted '" + request.getCompanyName() + "' into a tenant."));
        return toDto(request);
    }

    private OnboardingRequest lockOpen(UUID id) {
        OnboardingRequest request = repository.findForUpdate(id)
                .orElseThrow(TenancyException.OnboardingRequestNotFound::new);
        if (!request.getStatus().isOpen()) {
            throw new TenancyException.OnboardingRequestClosed(request.getStatus().getValue());
        }
        return request;
    }

    private String[] canonicalStates(List<String> input) {
        if (input == null) {
            return new String[0];
        }
        Set<String> names = new LinkedHashSet<>();
        for (String raw : input) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            names.add(nigerianStates.resolve(raw).orElseThrow(() -> new TenancyException.UnknownState(raw.trim()))
                    .name());
        }
        return names.toArray(String[]::new);
    }

    private OnboardingRequestMailer.RequestReceivedAlert alertFor(OnboardingRequest r) {
        return new OnboardingRequestMailer.RequestReceivedAlert(properties.getAlertTo(), r.getCompanyName(),
                r.getRcNumber(), r.getCompanyAddress(), String.join(", ", r.getStatesOfOperation()),
                r.getEstimatedEstateCount(), r.getContactName(), r.getContactRole(), r.getContactEmail(),
                r.getContactPhone(), r.getPreferredContactMethod().getValue(), r.getPreferredContactTime(),
                r.getMessage());
    }

    private Instant attentionCutoff() {
        return WorkingDays.before(Instant.now(), properties.getAttentionAfterWorkingDays(), properties.getTimeZone());
    }

    private OnboardingRequestDto toDto(OnboardingRequest r) {
        return toDto(r, attentionCutoff());
    }

    private static OnboardingRequestDto toDto(OnboardingRequest r, Instant cutoff) {
        String attentionReason = r.getStatus() == OnboardingRequestStatus.NEW && r.getCreatedAt().isBefore(cutoff)
                ? "no_contact_yet" : null;
        return new OnboardingRequestDto(r.getId(), r.getStatus().getValue(), r.getCompanyName(), r.getRcNumber(),
                r.getCompanyAddress(), r.getStatesOfOperation() == null ? List.of() : Arrays.asList(r.getStatesOfOperation()),
                r.getEstimatedEstateCount(), r.getContactName(), r.getContactRole(), r.getContactEmail(),
                r.getContactPhone(), r.getPreferredContactMethod().getValue(), r.getPreferredContactTime(),
                r.getMessage(), r.getCreatedAt(), r.getContactedAt(), r.getContactedBy(), r.getContactNote(),
                r.getDecidedAt(), r.getDecidedBy(), r.getDeclineReason(), r.getOrganizationId(),
                attentionReason != null, attentionReason);
    }

    /**
     * After commit, so a request that rolls back emails nobody; and a failed
     * email never undoes a stored request. The exception's class only is
     * logged: a mail error's message can carry the recipient's address.
     */
    private static void afterCommit(String what, Runnable send) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    send.run();
                } catch (RuntimeException e) {
                    log.warn("Email '{}' could not be sent ({}); the request is stored", what,
                            e.getClass().getSimpleName());
                }
            }
        });
    }

    private static String constraintNameOf(DataIntegrityViolationException ex) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
