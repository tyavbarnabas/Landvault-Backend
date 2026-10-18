package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.documents.dto.ApproveLetterTermsRequest;
import com.techcomfort.landvaultbackend.documents.dto.LetterTemplateReviewDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterTemplateVersionDto;
import com.techcomfort.landvaultbackend.documents.dto.RejectLetterTermsRequest;
import com.techcomfort.landvaultbackend.documents.internal.domain.LetterTemplate;
import com.techcomfort.landvaultbackend.documents.internal.domain.LetterTemplateReview;
import com.techcomfort.landvaultbackend.documents.internal.enums.ReviewDecision;
import com.techcomfort.landvaultbackend.documents.internal.exceptions.DocumentException;
import com.techcomfort.landvaultbackend.documents.internal.repository.LetterTemplateRepository;
import com.techcomfort.landvaultbackend.documents.internal.repository.LetterTemplateReviewRepository;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * HomeTrue's review of company and branch letter templates — same rules as the
 * review of an estate's conditions ({@link LetterTermsReviewService}): only the
 * newest version, once, and a rejection needs a note. Runs under the
 * platform-scope RLS bypass. See AGENTS.md, "Documents: letter templates".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LetterTemplateReviewService {

    private final LetterTemplateRepository templates;
    private final LetterTemplateReviewRepository reviews;
    private final TenancyApi tenancy;
    private final AuditApi auditApi;

    /** {@code pending} (default): the queue, oldest first. {@code approved}/{@code rejected}: newest first. */
    @Transactional(readOnly = true)
    public List<LetterTemplateReviewDto> list(String status) {
        String wanted = status == null || status.isBlank() ? ReviewStatus.PENDING : status.strip();
        List<LetterTemplate> found = switch (wanted) {
            case ReviewStatus.PENDING -> templates.findAwaitingReview();
            case "approved" -> decided(ReviewDecision.APPROVED);
            case "rejected" -> decided(ReviewDecision.REJECTED);
            default -> throw new DocumentException.RequestRejected("INVALID_PARAMETER",
                    "Use status 'pending', 'approved' or 'rejected'.");
        };
        return found.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public LetterTemplateReviewDto get(UUID templateId) {
        return toDto(version(templateId));
    }

    @Transactional
    public LetterTemplateReviewDto approve(UUID templateId, ApproveLetterTermsRequest request) {
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        return decide(templateId, ReviewDecision.APPROVED, note);
    }

    @Transactional
    public LetterTemplateReviewDto reject(UUID templateId, RejectLetterTermsRequest request) {
        return decide(templateId, ReviewDecision.REJECTED, request.note().strip());
    }

    private LetterTemplateReviewDto decide(UUID templateId, ReviewDecision decision, String note) {
        TenantScope scope = PortalScopes.current();
        LetterTemplate version = version(templateId);
        Optional<LetterTemplateReview> existing = reviews.findByTemplateId(templateId);
        if (existing.isPresent()) {
            throw new DocumentException.Conflict("LETTER_TEMPLATE_ALREADY_DECIDED",
                    "This version has already been " + existing.get().getDecision().value() + ".");
        }
        int newest = newest(version).map(LetterTemplate::getVersion).orElse(version.getVersion());
        if (newest != version.getVersion()) {
            throw new DocumentException.Conflict("LETTER_TEMPLATE_SUPERSEDED",
                    "The developer has saved version " + newest + " since this one. Review that version instead.");
        }
        reviews.saveAndFlush(LetterTemplateReview.builder()
                .tenantId(version.getTenantId())
                .branchId(version.getBranchId())
                .templateId(templateId)
                .templateVersion(version.getVersion())
                .decision(decision)
                .note(note)
                .reviewerUserId(scope.userId())
                .build());
        auditApi.record(AuditEntryRequest.of(scope.userId(), "letter_template." + decision.value(), "letter_template",
                version.getBranchId() == null ? version.getTenantId() : version.getBranchId(), version.getTenantId(),
                whose(version) + " letter template version " + version.getVersion() + " " + decision.value() + "."
                        + (note == null ? "" : " Note: " + note)));
        log.info("Letter template v{} of {} {} by actor {}", version.getVersion(), version.getTenantId(),
                decision.value(), scope.userId());
        return toDto(version);
    }

    private LetterTemplate version(UUID templateId) {
        return templates.findById(templateId).orElseThrow(() ->
                new DocumentException.NotFound("LETTER_TEMPLATE_NOT_FOUND", "No letter template has this id."));
    }

    private Optional<LetterTemplate> newest(LetterTemplate t) {
        return t.getBranchId() == null
                ? templates.findFirstByTenantIdAndBranchIdIsNullOrderByVersionDesc(t.getTenantId())
                : templates.findFirstByTenantIdAndBranchIdOrderByVersionDesc(t.getTenantId(), t.getBranchId());
    }

    private List<LetterTemplate> decided(ReviewDecision decision) {
        List<LetterTemplateReview> found = reviews.findByDecisionOrderByCreatedAtDesc(decision);
        Map<UUID, LetterTemplate> byId = templates.findAllById(found.stream().map(LetterTemplateReview::getTemplateId).toList())
                .stream().collect(Collectors.toMap(LetterTemplate::getId, Function.identity()));
        return found.stream().map(r -> byId.get(r.getTemplateId())).toList();
    }

    /** One row at a time: the queue holds at most one version per company or branch, and is short. */
    private LetterTemplateReviewDto toDto(LetterTemplate t) {
        LetterTemplateReview review = reviews.findByTemplateId(t.getId()).orElse(null);
        int newest = newest(t).map(LetterTemplate::getVersion).orElse(t.getVersion());
        Optional<LetterTemplateReview> inUse = t.getBranchId() == null
                ? reviews.findFirstByTenantIdAndBranchIdIsNullAndDecisionOrderByTemplateVersionDesc(
                        t.getTenantId(), ReviewDecision.APPROVED)
                : reviews.findFirstByTenantIdAndBranchIdAndDecisionOrderByTemplateVersionDesc(
                        t.getTenantId(), t.getBranchId(), ReviewDecision.APPROVED);
        LetterTemplateVersionDto approved = inUse.flatMap(r -> templates.findById(r.getTemplateId()).map(a ->
                new LetterTemplateVersionDto(a.getVersion(), a.getOpening(), a.getClosing(), a.getSignOff(),
                        List.of(a.getStandardClauses()), a.getCreatedAt(), ReviewStatus.of(a.getVersion(), r.getDecision(), newest),
                        r.getNote(), r.getCreatedAt()))).orElse(null);
        return new LetterTemplateReviewDto(t.getId(), t.getTenantId(),
                tenancy.organizationNamesFor(List.of(t.getTenantId())).get(t.getTenantId()),
                t.getBranchId() == null ? LetterTarget.COMPANY : "branch", t.getBranchId(),
                t.getBranchId() == null ? null : tenancy.branchNameFor(t.getBranchId(), t.getTenantId()).orElse(null),
                t.getVersion(), t.getOpening(), t.getClosing(), t.getSignOff(), List.of(t.getStandardClauses()),
                t.getCreatedAt(), ReviewStatus.of(t.getVersion(), review == null ? null : review.getDecision(), newest),
                review == null ? null : review.getNote(), review == null ? null : review.getCreatedAt(), approved);
    }

    private String whose(LetterTemplate t) {
        String company = tenancy.organizationNamesFor(List.of(t.getTenantId())).getOrDefault(t.getTenantId(), "?");
        return t.getBranchId() == null ? company + "'s"
                : company + " branch '" + tenancy.branchNameFor(t.getBranchId(), t.getTenantId()).orElse("?") + "'";
    }
}
