package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.common.TitleStatus;
import com.techcomfort.landvaultbackend.common.VerificationCheckStatus;
import com.techcomfort.landvaultbackend.common.VerificationCheckType;
import com.techcomfort.landvaultbackend.common.VerificationSource;
import com.techcomfort.landvaultbackend.inventory.dto.CreateVerificationCheckRequest;
import com.techcomfort.landvaultbackend.inventory.dto.EstateTitleDto;
import com.techcomfort.landvaultbackend.inventory.dto.RejectTitleRequest;
import com.techcomfort.landvaultbackend.inventory.dto.TitleReviewDto;
import com.techcomfort.landvaultbackend.inventory.dto.VerificationCheckDto;
import com.techcomfort.landvaultbackend.inventory.dto.VerifyTitleRequest;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateTitle;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateVerificationCheck;
import com.techcomfort.landvaultbackend.inventory.internal.exceptions.InventoryException;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateTitleRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateVerificationCheckRepository;
import com.techcomfort.landvaultbackend.storage.DownloadLink;
import com.techcomfort.landvaultbackend.storage.StorageApi;
import com.techcomfort.landvaultbackend.storage.StorageException;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * HomeTrue's side of an estate's title: the review queue, the documents, and
 * the decision. Also the estate's other due-diligence checks, which only
 * HomeTrue records now. Reads and writes under the platform-scope RLS bypass.
 * See AGENTS.md, "Estate titles".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminTitleReviewService {

    private final EstateTitleRepository titles;
    private final EstateRepository estates;
    private final EstateVerificationCheckRepository checks;
    private final EstateTitleMapper mapper;
    private final StorageApi storage;
    private final TenancyApi tenancyApi;
    private final AuditApi auditApi;

    /** Pending oldest first (the queue); decided newest first. */
    @Transactional(readOnly = true)
    public List<TitleReviewDto> list(TitleStatus status) {
        List<EstateTitle> found = status == TitleStatus.PENDING
                ? titles.findByStatusOrderBySubmittedAtAsc(status)
                : titles.findByStatusOrderByDecidedAtDesc(status);
        return toReviewDtos(found);
    }

    /** Only the documents of the submission under review. Five minutes. */
    @Transactional(readOnly = true)
    public DownloadLink downloadLink(UUID titleId, UUID fileId) {
        EstateTitle title = titles.findById(titleId).orElseThrow(AdminTitleReviewService::titleNotFound);
        if (EstateTitleMapper.attached(title).noneMatch(fileId::equals)) {
            throw new StorageException.FileNotFound();
        }
        return storage.downloadLink(fileId);
    }

    @Transactional
    public TitleReviewDto verify(UUID titleId, VerifyTitleRequest request) {
        TenantScope scope = currentScope();
        VerificationSource source = VerificationSource.fromValue(request.verificationSource());
        requireRecordableByAPerson(source);
        EstateTitle title = decidable(titleId, request.submittedAt());
        if (title.getTitleDocumentFileId() == null || title.getSurveyPlanFileId() == null) {
            // Titles recorded before review existed carry no documents.
            throw new InventoryException.StateConflict("TITLE_DOCUMENTS_MISSING",
                    "This title has no title document or survey plan to check. Reject it and ask the developer "
                            + "to upload them.");
        }
        Estate estate = estates.findById(title.getEstateId()).orElseThrow(InventoryException.EstateNotFound::new);
        Instant now = Instant.now();
        decide(title, TitleStatus.VERIFIED, scope, now, source, blankToNull(request.note()));
        recordTitleCheck(estate, VerificationCheckStatus.VERIFIED, source, now);

        auditApi.record(AuditEntryRequest.of(scope.userId(), "estate.title_verified", "estate", estate.getId(),
                estate.getTenantId(), "Title of '" + estate.getName() + "' verified (" + source.getValue() + "): "
                        + EstateTitleService.describe(title) + "."
                        + (title.getDecisionNote() == null ? "" : " Note: " + title.getDecisionNote())));
        log.info("Title {} of estate {} verified by {}", titleId, estate.getId(), scope.userId());
        return toReviewDtos(List.of(title)).getFirst();
    }

    @Transactional
    public TitleReviewDto reject(UUID titleId, RejectTitleRequest request) {
        TenantScope scope = currentScope();
        EstateTitle title = decidable(titleId, request.submittedAt());
        Estate estate = estates.findById(title.getEstateId()).orElseThrow(InventoryException.EstateNotFound::new);
        decide(title, TitleStatus.REJECTED, scope, Instant.now(), null, request.note().strip());
        recordTitleCheck(estate, VerificationCheckStatus.FAILED, null, null);

        auditApi.record(AuditEntryRequest.of(scope.userId(), "estate.title_rejected", "estate", estate.getId(),
                estate.getTenantId(), "Title of '" + estate.getName() + "' rejected: "
                        + EstateTitleService.describe(title) + ". Reason: " + title.getDecisionNote()));
        log.info("Title {} of estate {} rejected by {}", titleId, estate.getId(), scope.userId());
        return toReviewDtos(List.of(title)).getFirst();
    }

    /**
     * AGIS registration and encroachment checks, recorded by a person at
     * HomeTrue. The title check is not recorded here — it follows the title's
     * own decision, so the two can never disagree. A person may only claim the
     * sources a person can produce.
     */
    @Transactional
    public VerificationCheckDto recordVerificationCheck(UUID estateId, CreateVerificationCheckRequest request) {
        TenantScope scope = currentScope();
        Estate estate = estates.findById(estateId).orElseThrow(InventoryException.EstateNotFound::new);

        VerificationCheckType checkType = VerificationCheckType.fromValue(request.checkType());
        if (checkType == VerificationCheckType.TITLE_VERIFICATION) {
            throw new InventoryException.RequestRejected("TITLE_CHECK_FOLLOWS_REVIEW",
                    "The title check follows the title's review. Verify or reject the title in the title review "
                            + "queue instead.");
        }
        // NOT_CHECKED when omitted: the absence of a check is not a clean
        // bill of health, and must never be recorded as one.
        VerificationCheckStatus status = request.status() == null
                ? VerificationCheckStatus.NOT_CHECKED
                : VerificationCheckStatus.fromValue(request.status());
        VerificationSource source = request.verificationSource() == null
                ? null : VerificationSource.fromValue(request.verificationSource());
        if (source != null) {
            requireRecordableByAPerson(source);
        }
        if (status == VerificationCheckStatus.VERIFIED && source == null) {
            throw new InventoryException.InvalidRequest(
                    "A VERIFIED check needs a verificationSource — a green badge from a registry search and one "
                            + "from a person reading a PDF are different claims, and must stay distinguishable.");
        }

        EstateVerificationCheck check = checkFor(estate, checkType);
        check.setStatus(status);
        check.setVerificationSource(source);
        check.setNotes(request.notes());
        check.setLastVerifiedAt(status == VerificationCheckStatus.VERIFIED ? Instant.now() : null);
        check = checks.save(check);

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.verification_check_recorded", "estate", estateId, estate.getTenantId(),
                checkType.getValue() + " -> " + status.getValue()
                        + (source == null ? "" : " (" + source.getValue() + ")")));
        return new VerificationCheckDto(check.getId(), estateId, checkType.getValue(), status.getValue(),
                source == null ? null : source.getValue(), check.getLastVerifiedAt(), check.getNotes());
    }

    // --- shared ---

    /**
     * The title, locked, still pending, and still the submission the reviewer
     * looked at. Without the last check, a developer resubmitting during a
     * review would have documents nobody opened marked verified.
     */
    private EstateTitle decidable(UUID titleId, Instant reviewedSubmission) {
        EstateTitle title = titles.findByIdForUpdate(titleId).orElseThrow(AdminTitleReviewService::titleNotFound);
        if (title.getStatus() != TitleStatus.PENDING) {
            throw new InventoryException.StateConflict("TITLE_NOT_PENDING",
                    "This title has already been " + title.getStatus().getValue() + ". Only a pending title can be decided.");
        }
        if (!title.getSubmittedAt().truncatedTo(ChronoUnit.MICROS)
                .equals(reviewedSubmission.truncatedTo(ChronoUnit.MICROS))) {
            throw new InventoryException.StateConflict("TITLE_CHANGED",
                    "The developer has resubmitted this title since you opened it. Reload it and review the new "
                            + "documents.");
        }
        return title;
    }

    private void decide(EstateTitle title, TitleStatus status, TenantScope scope, Instant at,
                        VerificationSource source, String note) {
        title.setStatus(status);
        title.setDecidedAt(at);
        title.setDecidedBy(scope.userId());
        title.setVerificationSource(source);
        title.setDecisionNote(note);
        titles.saveAndFlush(title);
    }

    private void recordTitleCheck(Estate estate, VerificationCheckStatus status, VerificationSource source,
                                  Instant verifiedAt) {
        EstateVerificationCheck check = checkFor(estate, VerificationCheckType.TITLE_VERIFICATION);
        check.setStatus(status);
        check.setVerificationSource(source);
        check.setLastVerifiedAt(verifiedAt);
        checks.save(check);
    }

    private EstateVerificationCheck checkFor(Estate estate, VerificationCheckType type) {
        return checks.findByEstateIdAndCheckType(estate.getId(), type).orElseGet(() -> {
            EstateVerificationCheck fresh = EstateVerificationCheck.builder()
                    .estateId(estate.getId())
                    .checkType(type)
                    .build();
            fresh.setTenantId(estate.getTenantId());
            fresh.setBranchId(estate.getBranchId());
            return fresh;
        });
    }

    private static void requireRecordableByAPerson(VerificationSource source) {
        if (!source.recordableByAPerson()) {
            throw new InventoryException.RequestRejected("VERIFICATION_SOURCE_NOT_ALLOWED",
                    "'" + source.getValue() + "' is reserved for an integration that queries the registry itself, "
                            + "and none is built. Use manual_review or registry_search.");
        }
    }

    private List<TitleReviewDto> toReviewDtos(List<EstateTitle> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, Estate> estatesById = estates.findAllById(list.stream().map(EstateTitle::getEstateId).toList())
                .stream().collect(Collectors.toMap(Estate::getId, Function.identity()));
        Set<UUID> tenantIds = estatesById.values().stream().map(Estate::getTenantId).collect(Collectors.toSet());
        Map<UUID, String> companies = tenancyApi.organizationNamesFor(tenantIds);
        List<EstateTitleDto> dtos = mapper.toDtos(list);
        List<TitleReviewDto> out = new ArrayList<>(dtos.size());
        for (EstateTitleDto dto : dtos) {
            Estate estate = estatesById.get(dto.estateId());
            out.add(TitleReviewDto.of(dto, estate == null ? null : estate.getName(),
                    estate == null ? null : estate.getState(),
                    estate == null ? null : companies.get(estate.getTenantId())));
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static InventoryException.NotFound titleNotFound() {
        return new InventoryException.NotFound("TITLE_NOT_FOUND", "Title not found.");
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
