package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.common.TitleStatus;
import com.techcomfort.landvaultbackend.common.TitleType;
import com.techcomfort.landvaultbackend.common.VerificationCheckStatus;
import com.techcomfort.landvaultbackend.common.VerificationCheckType;
import com.techcomfort.landvaultbackend.inventory.dto.EstateTitleDto;
import com.techcomfort.landvaultbackend.inventory.dto.RequestTitleDocumentUploadRequest;
import com.techcomfort.landvaultbackend.inventory.dto.SubmitEstateTitleRequest;
import com.techcomfort.landvaultbackend.inventory.dto.TitleDocumentDto;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateTitle;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateTitleDocument;
import com.techcomfort.landvaultbackend.inventory.internal.domain.EstateVerificationCheck;
import com.techcomfort.landvaultbackend.inventory.internal.enums.TitleDocumentKind;
import com.techcomfort.landvaultbackend.inventory.internal.exceptions.InventoryException;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateTitleDocumentRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateTitleRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateVerificationCheckRepository;
import com.techcomfort.landvaultbackend.storage.DownloadLink;
import com.techcomfort.landvaultbackend.storage.FileType;
import com.techcomfort.landvaultbackend.storage.StorageApi;
import com.techcomfort.landvaultbackend.storage.StorageException;
import com.techcomfort.landvaultbackend.storage.StorageVisibility;
import com.techcomfort.landvaultbackend.storage.StoredFileInfo;
import com.techcomfort.landvaultbackend.storage.UploadRequest;
import com.techcomfort.landvaultbackend.storage.UploadTicket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The developer's side of an estate's title: upload the evidence, then
 * declare the title. A declaration is always {@code pending}; only HomeTrue
 * decides ({@link AdminTitleReviewService}). See AGENTS.md, "Estate titles".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EstateTitleService {

    /** 10 MB. */
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Set<FileType> ALLOWED = Set.of(FileType.PDF, FileType.JPEG, FileType.PNG);
    /** Where "not in the future" is judged: the land and its registries are in Nigeria. */
    private static final ZoneId NIGERIA = ZoneId.of("Africa/Lagos");

    private final EstateRepository estates;
    private final EstateTitleRepository titles;
    private final EstateTitleDocumentRepository documents;
    private final EstateVerificationCheckRepository checks;
    private final EstateTitleMapper mapper;
    private final StorageApi storage;
    private final AuditApi auditApi;

    @Transactional(readOnly = true)
    public EstateTitleDto get(UUID estateId) {
        readableEstate(estateId);
        return titles.findByEstateId(estateId).map(mapper::toDto).orElseThrow(() ->
                new InventoryException.NotFound("TITLE_NOT_RECORDED", "No title has been declared for this estate."));
    }

    /**
     * Permission for one upload into the PRIVATE bucket. Kept exactly as
     * uploaded: this is evidence, and nothing public is made from it.
     */
    @Transactional
    public UploadTicket requestUpload(UUID estateId, RequestTitleDocumentUploadRequest request) {
        TenantScope scope = currentScope();
        Estate estate = writableEstate(estateId, scope, false);
        TitleDocumentKind kind = TitleDocumentKind.fromValue(request.kind());
        return storage.requestUpload(new UploadRequest(StorageVisibility.PRIVATE, kind.storagePurpose(),
                request.fileName(), request.contentType(), request.sizeBytes(), ALLOWED, MAX_BYTES,
                scope.userId(), estate.getTenantId()));
    }

    /**
     * Storage checks the file; it then belongs to the estate as evidence of
     * the kind it was uploaded as, but not to the title until a submission
     * names it. Confirming again returns the same document.
     * <p>
     * {@code noRollbackFor}: storage marks a refused file rejected and deletes
     * it, then throws — rolling back here would undo the marking (the trap
     * recorded under "Estate photos").
     */
    @Transactional(noRollbackFor = StorageException.UploadRejected.class)
    public Confirmed confirm(UUID estateId, UUID fileId) {
        TenantScope scope = currentScope();
        Estate estate = writableEstate(estateId, scope, false);

        var existing = documents.findByFileId(fileId);
        if (existing.isPresent()) {
            if (!existing.get().getEstateId().equals(estateId)) {
                throw new StorageException.FileNotFound();
            }
            return new Confirmed(EstateTitleMapper.toDto(existing.get(), storage.find(fileId).orElseThrow()), false);
        }

        StoredFileInfo file = storage.confirmUpload(fileId, scope.userId());
        TitleDocumentKind kind = TitleDocumentKind.fromStoragePurpose(file.purpose())
                .filter(k -> file.visibility() == StorageVisibility.PRIVATE)
                // Another kind of file the caller uploaded. Same answer as someone else's.
                .orElseThrow(StorageException.FileNotFound::new);
        EstateTitleDocument doc = documents.saveAndFlush(EstateTitleDocument.builder()
                .tenantId(estate.getTenantId())
                .branchId(estate.getBranchId())
                .estateId(estateId)
                .fileId(fileId)
                .kind(kind)
                .build());
        log.info("Title document confirmed: {} ({}) on estate {} by actor {}", fileId, kind, estateId, scope.userId());
        return new Confirmed(EstateTitleMapper.toDto(doc, file), true);
    }

    /** {@code created} is false when the file had already been confirmed. */
    public record Confirmed(TitleDocumentDto document, boolean created) {
    }

    /** Any of this estate's title documents, attached or not. Five minutes. */
    @Transactional(readOnly = true)
    public DownloadLink downloadLink(UUID estateId, UUID fileId) {
        readableEstate(estateId);
        documents.findByEstateIdAndFileId(estateId, fileId).orElseThrow(StorageException.FileNotFound::new);
        return storage.downloadLink(fileId);
    }

    /**
     * Declares the title, creating it or replacing what was declared. Always
     * {@code pending}, and any previous decision is cleared — a verification
     * was of the documents shown then, not of these. The estate's title check
     * goes back to pending with it, so buyers stop seeing "checked" at once.
     * A resubmission identical to a title still pending changes nothing.
     */
    @Transactional
    public EstateTitleDto submit(UUID estateId, SubmitEstateTitleRequest request) {
        TenantScope scope = currentScope();
        Estate estate = writableEstate(estateId, scope, true);

        TitleType type = TitleType.fromValue(request.titleType());
        String number = request.titleNumber().strip();
        LocalDate issued = issuedDate(request.issuedDate());
        if (request.titleDocumentFileId() == null) {
            throw new InventoryException.RequestRejected("TITLE_DOCUMENT_REQUIRED",
                    "Upload the title document itself (the C of O, R of O, consent or Gazette page) and include it.");
        }
        if (request.surveyPlanFileId() == null) {
            throw new InventoryException.RequestRejected("SURVEY_PLAN_REQUIRED",
                    "Upload the survey plan and include it — it shows which land the title covers.");
        }
        requireDocument(estateId, request.titleDocumentFileId(), TitleDocumentKind.TITLE_DOCUMENT);
        requireDocument(estateId, request.surveyPlanFileId(), TitleDocumentKind.SURVEY_PLAN);
        if (request.deedFileId() != null) {
            requireDocument(estateId, request.deedFileId(), TitleDocumentKind.DEED);
        }

        EstateTitle title = titles.findByEstateId(estateId).orElse(null);
        if (title != null && title.getStatus() == TitleStatus.PENDING && title.getTitleType() == type
                && number.equals(title.getTitleNumber()) && Objects.equals(issued, title.getIssuedDate())
                && request.titleDocumentFileId().equals(title.getTitleDocumentFileId())
                && request.surveyPlanFileId().equals(title.getSurveyPlanFileId())
                && Objects.equals(request.deedFileId(), title.getDeedFileId())) {
            return mapper.toDto(title);
        }
        String before = title == null ? null : describe(title);
        TitleStatus previousStatus = title == null ? null : title.getStatus();
        if (title == null) {
            title = EstateTitle.builder().estateId(estateId).build();
            title.setTenantId(estate.getTenantId());
            title.setBranchId(estate.getBranchId());
        }
        title.setTitleType(type);
        title.setTitleNumber(number);
        title.setIssuedDate(issued);
        title.setTitleDocumentFileId(request.titleDocumentFileId());
        title.setSurveyPlanFileId(request.surveyPlanFileId());
        title.setDeedFileId(request.deedFileId());
        title.setStatus(TitleStatus.PENDING);
        title.setSubmittedAt(Instant.now());
        title.setSubmittedBy(scope.userId());
        title.setDecidedAt(null);
        title.setDecidedBy(null);
        title.setVerificationSource(null);
        title.setDecisionNote(null);
        title = titles.saveAndFlush(title);

        resetTitleCheck(estate);

        auditApi.record(AuditEntryRequest.of(scope.userId(), "estate.title_submitted", "estate", estateId,
                estate.getTenantId(), before == null
                        ? "Title declared for '" + estate.getName() + "': " + describe(title) + ". Awaiting review."
                        : "Title of '" + estate.getName() + "' resubmitted (was " + previousStatus.getValue() + "): "
                                + before + " -> " + describe(title) + ". Awaiting review."));
        log.info("Title submitted for estate {} by actor {}", estateId, scope.userId());
        return mapper.toDto(title);
    }

    // --- shared ---

    /** The estate's title check now waits on HomeTrue: never left saying "verified" for documents nobody checked. */
    private void resetTitleCheck(Estate estate) {
        EstateVerificationCheck check = checks
                .findByEstateIdAndCheckType(estate.getId(), VerificationCheckType.TITLE_VERIFICATION)
                .orElseGet(() -> {
                    EstateVerificationCheck fresh = EstateVerificationCheck.builder()
                            .estateId(estate.getId())
                            .checkType(VerificationCheckType.TITLE_VERIFICATION)
                            .build();
                    fresh.setTenantId(estate.getTenantId());
                    fresh.setBranchId(estate.getBranchId());
                    return fresh;
                });
        check.setStatus(VerificationCheckStatus.PENDING);
        check.setVerificationSource(null);
        check.setLastVerifiedAt(null);
        checks.save(check);
    }

    private void requireDocument(UUID estateId, UUID fileId, TitleDocumentKind kind) {
        boolean ok = documents.findByEstateIdAndFileId(estateId, fileId)
                .filter(d -> d.getKind() == kind)
                .flatMap(d -> storage.find(fileId))
                .filter(StoredFileInfo::ready)
                .isPresent();
        if (!ok) {
            throw new InventoryException.RequestRejected("TITLE_DOCUMENT_NOT_FOUND",
                    "File " + fileId + " isn't a confirmed " + kind.getValue() + " of this estate. Upload it as "
                            + kind.getValue() + " and confirm it first.");
        }
    }

    private static LocalDate issuedDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new InventoryException.RequestRejected("INVALID_ISSUED_DATE", "Give the issue date as YYYY-MM-DD.");
        }
        if (date.isAfter(LocalDate.now(NIGERIA))) {
            throw new InventoryException.RequestRejected("ISSUED_DATE_IN_FUTURE",
                    "The issue date can't be in the future.");
        }
        return date;
    }

    static String describe(EstateTitle t) {
        return t.getTitleType().getValue() + " " + t.getTitleNumber()
                + (t.getIssuedDate() == null ? "" : " issued " + t.getIssuedDate());
    }

    private void readableEstate(UUID estateId) {
        estates.findByIdAndTenantId(estateId, requireTenant(currentScope()))
                .orElseThrow(InventoryException.EstateNotFound::new);
    }

    /** For writes — the caller's own estate, and the EB-2 branch rule. Locked when {@code lock}. */
    private Estate writableEstate(UUID estateId, TenantScope scope, boolean lock) {
        UUID tenantId = requireTenant(scope);
        Estate estate = (lock ? estates.findByIdAndTenantIdForUpdate(estateId, tenantId)
                : estates.findByIdAndTenantId(estateId, tenantId))
                .orElseThrow(InventoryException.EstateNotFound::new);
        EstateWriteAccess.requireWritable(estate);
        return estate;
    }

    private static UUID requireTenant(TenantScope scope) {
        if (scope.tenantId() == null) {
            throw new InventoryException.EstateNotFound();
        }
        return scope.tenantId();
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }
}
