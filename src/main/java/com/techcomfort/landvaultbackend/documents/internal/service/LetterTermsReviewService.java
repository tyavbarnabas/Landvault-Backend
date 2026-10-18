package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.documents.dto.ApproveLetterTermsRequest;
import com.techcomfort.landvaultbackend.documents.dto.LetterTermsReviewDto;
import com.techcomfort.landvaultbackend.documents.dto.RejectLetterTermsRequest;
import com.techcomfort.landvaultbackend.documents.internal.domain.EstateLetterTerms;
import com.techcomfort.landvaultbackend.documents.internal.domain.EstateLetterTermsReview;
import com.techcomfort.landvaultbackend.documents.internal.enums.ReviewDecision;
import com.techcomfort.landvaultbackend.documents.internal.exceptions.DocumentException;
import com.techcomfort.landvaultbackend.documents.internal.render.DisclosureClauses;
import com.techcomfort.landvaultbackend.documents.internal.repository.EstateLetterTermsRepository;
import com.techcomfort.landvaultbackend.documents.internal.repository.EstateLetterTermsReviewRepository;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentFacts;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentsApi;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * HomeTrue's review of the allocation conditions developers write. The
 * automatic clause check catches the common ways of writing money and bank
 * details; a person catches the rest, before any letter may use the version.
 * Runs under the platform-scope RLS bypass. See AGENTS.md, "Documents: HomeTrue
 * reviews the conditions".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LetterTermsReviewService {

    private final EstateLetterTermsRepository terms;
    private final EstateLetterTermsReviewRepository reviews;
    private final EstateDocumentsApi estates;
    private final TenancyApi tenancy;
    private final AuditApi auditApi;

    /** {@code pending} (default): the queue, oldest first. {@code approved}/{@code rejected}: decided, newest first. */
    @Transactional(readOnly = true)
    public List<LetterTermsReviewDto> list(String status) {
        String wanted = status == null || status.isBlank() ? ReviewStatus.PENDING : status.strip();
        List<EstateLetterTerms> found = switch (wanted) {
            case ReviewStatus.PENDING -> terms.findAwaitingReview();
            case "approved" -> decided(ReviewDecision.APPROVED);
            case "rejected" -> decided(ReviewDecision.REJECTED);
            default -> throw new DocumentException.RequestRejected("INVALID_PARAMETER",
                    "Use status 'pending', 'approved' or 'rejected'.");
        };
        return toDtos(found);
    }

    @Transactional(readOnly = true)
    public LetterTermsReviewDto get(UUID termsId) {
        return toDtos(List.of(version(termsId))).getFirst();
    }

    @Transactional
    public LetterTermsReviewDto approve(UUID termsId, ApproveLetterTermsRequest request) {
        return decide(termsId, ReviewDecision.APPROVED, blankToNull(request.note()));
    }

    @Transactional
    public LetterTermsReviewDto reject(UUID termsId, RejectLetterTermsRequest request) {
        return decide(termsId, ReviewDecision.REJECTED, request.note().strip());
    }

    /**
     * Only the estate's newest version, and only once. A decision on a version
     * the developer has since replaced is refused: the reviewer would be
     * approving clauses no letter will use, and hasn't read the ones it will.
     * If a save lands at the same instant, the reviewer's version keeps its
     * decision and the new one waits its turn — both outcomes are honest.
     */
    private LetterTermsReviewDto decide(UUID termsId, ReviewDecision decision, String note) {
        TenantScope scope = PortalScopes.current();
        EstateLetterTerms version = version(termsId);
        Optional<EstateLetterTermsReview> existing = reviews.findByTermsId(termsId);
        if (existing.isPresent()) {
            throw new DocumentException.Conflict("LETTER_TERMS_ALREADY_DECIDED",
                    "This version has already been " + existing.get().getDecision().value() + ".");
        }
        int newest = terms.findFirstByEstateIdOrderByVersionDesc(version.getEstateId())
                .map(EstateLetterTerms::getVersion).orElse(version.getVersion());
        if (newest != version.getVersion()) {
            throw new DocumentException.Conflict("LETTER_TERMS_SUPERSEDED",
                    "The developer has saved version " + newest + " since this one. Review that version instead.");
        }

        // saveAndFlush: two reviewers deciding at once collide on the one-decision-per-version key here.
        reviews.saveAndFlush(EstateLetterTermsReview.builder()
                .tenantId(version.getTenantId())
                .branchId(version.getBranchId())
                .estateId(version.getEstateId())
                .termsId(termsId)
                .termsVersion(version.getVersion())
                .decision(decision)
                .note(note)
                .reviewerUserId(scope.userId())
                .build());

        String estateName = estates.forDocuments(version.getEstateId()).map(EstateDocumentFacts::name).orElse("?");
        auditApi.record(AuditEntryRequest.of(scope.userId(), "estate.letter_terms_" + decision.value(), "estate",
                version.getEstateId(), version.getTenantId(), "Allocation conditions for '" + estateName
                        + "' version " + version.getVersion() + " " + decision.value() + "."
                        + (note == null ? "" : " Note: " + note)));
        log.info("Letter terms v{} of estate {} {} by actor {}", version.getVersion(), version.getEstateId(),
                decision.value(), scope.userId());
        return toDtos(List.of(version)).getFirst();
    }

    // ------------------------------------------------------------- shared

    private EstateLetterTerms version(UUID termsId) {
        return terms.findById(termsId).orElseThrow(() ->
                new DocumentException.NotFound("LETTER_TERMS_NOT_FOUND", "No allocation conditions have this id."));
    }

    private List<EstateLetterTerms> decided(ReviewDecision decision) {
        List<EstateLetterTermsReview> found = reviews.findByDecisionOrderByCreatedAtDesc(decision);
        Map<UUID, EstateLetterTerms> byId = terms.findAllById(found.stream().map(EstateLetterTermsReview::getTermsId).toList())
                .stream().collect(Collectors.toMap(EstateLetterTerms::getId, Function.identity()));
        return found.stream().map(r -> byId.get(r.getTermsId())).toList();
    }

    /**
     * Estate facts are read once per estate, not per version: the queue holds at
     * most one pending version per estate, so this is one read per row there.
     * The cost disclosure behind {@code generatedClauses} has no batch read yet.
     */
    private List<LetterTermsReviewDto> toDtos(List<EstateLetterTerms> versions) {
        if (versions.isEmpty()) {
            return List.of();
        }
        Map<UUID, EstateLetterTermsReview> decisions = reviews.findByTermsIdIn(
                        versions.stream().map(EstateLetterTerms::getId).toList())
                .stream().collect(Collectors.toMap(EstateLetterTermsReview::getTermsId, Function.identity()));
        Set<UUID> estateIds = versions.stream().map(EstateLetterTerms::getEstateId).collect(Collectors.toSet());
        Map<UUID, Optional<EstateDocumentFacts>> facts = new HashMap<>();
        Map<UUID, Integer> newest = new HashMap<>();
        Map<UUID, Optional<EstateLetterTerms>> approved = new HashMap<>();
        for (UUID estateId : estateIds) {
            facts.put(estateId, estates.forDocuments(estateId));
            newest.put(estateId, terms.findFirstByEstateIdOrderByVersionDesc(estateId)
                    .map(EstateLetterTerms::getVersion).orElse(0));
            approved.put(estateId, reviews.findFirstByEstateIdAndDecisionOrderByTermsVersionDesc(
                            estateId, ReviewDecision.APPROVED)
                    .flatMap(r -> terms.findById(r.getTermsId())));
        }
        Map<UUID, String> companies = tenancy.organizationNamesFor(
                versions.stream().map(EstateLetterTerms::getTenantId).collect(Collectors.toSet()));

        return versions.stream().map(t -> {
            EstateDocumentFacts estate = facts.get(t.getEstateId()).orElse(null);
            EstateLetterTermsReview review = decisions.get(t.getId());
            Optional<EstateLetterTerms> inUse = approved.get(t.getEstateId());
            return new LetterTermsReviewDto(
                    t.getId(),
                    t.getEstateId(),
                    estate == null ? null : estate.name(),
                    estate == null ? null : estate.state(),
                    companies.get(t.getTenantId()),
                    t.getVersion(),
                    List.of(t.getClauses()),
                    t.getCreatedAt(),
                    estate == null ? List.of() : DisclosureClauses.of(estate),
                    ReviewStatus.of(t.getVersion(), review == null ? null : review.getDecision(), newest.get(t.getEstateId())),
                    review == null ? null : review.getNote(),
                    review == null ? null : review.getCreatedAt(),
                    inUse.map(EstateLetterTerms::getVersion).orElse(null),
                    inUse.map(a -> List.of(a.getClauses())).orElse(List.of()));
        }).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
