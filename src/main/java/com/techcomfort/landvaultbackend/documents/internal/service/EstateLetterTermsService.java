package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.documents.dto.EstateLetterTermsDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterTermsVersionDto;
import com.techcomfort.landvaultbackend.documents.dto.SaveLetterTermsRequest;
import com.techcomfort.landvaultbackend.documents.internal.domain.EstateLetterTerms;
import com.techcomfort.landvaultbackend.documents.internal.domain.EstateLetterTermsReview;
import com.techcomfort.landvaultbackend.documents.internal.enums.ReviewDecision;
import com.techcomfort.landvaultbackend.documents.internal.exceptions.DocumentException;
import com.techcomfort.landvaultbackend.documents.internal.render.DisclosureClauses;
import com.techcomfort.landvaultbackend.documents.internal.repository.EstateLetterTermsRepository;
import com.techcomfort.landvaultbackend.documents.internal.repository.EstateLetterTermsReviewRepository;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentFacts;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentsApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Each estate's allocation conditions (DS-3, DS-4). The developer writes the
 * estate's own clauses; HomeTrue adds the disclosed figures. Every save is a
 * new version and earlier ones are kept: a letter will record the version it
 * was issued with, and HomeTrue reviews every version before a letter may use
 * it ({@link LetterTermsReviewService}). Reads need {@code portal.documents.view}; saving needs
 * {@code portal.documents.manage} and a company-wide scope. See AGENTS.md,
 * "Documents: allocation terms".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EstateLetterTermsService {

    private final EstateLetterTermsRepository terms;
    private final EstateLetterTermsReviewRepository reviews;
    private final EstateDocumentsApi estates;
    private final AuditApi auditApi;

    @Transactional(readOnly = true)
    public EstateLetterTermsDto get(UUID estateId) {
        EstateDocumentFacts facts = estate(estateId, PortalScopes.company(PortalScopes.current()));
        return toDto(facts, terms.findFirstByEstateIdOrderByVersionDesc(estateId));
    }

    /** Newest first, each with HomeTrue's decision; older undecided versions read {@code superseded}. */

    @Transactional(readOnly = true)
    public List<LetterTermsVersionDto> versions(UUID estateId) {
        estate(estateId, PortalScopes.company(PortalScopes.current()));
        List<EstateLetterTerms> all = terms.findByEstateIdOrderByVersionDesc(estateId);
        Map<UUID, EstateLetterTermsReview> decisions = reviews.findByEstateId(estateId).stream()
                .collect(Collectors.toMap(EstateLetterTermsReview::getTermsId, Function.identity()));
        int newest = all.isEmpty() ? 0 : all.getFirst().getVersion();
        return all.stream().map(t -> {
            EstateLetterTermsReview review = decisions.get(t.getId());
            return new LetterTermsVersionDto(t.getVersion(), List.of(t.getClauses()), t.getCreatedAt(),
                    ReviewStatus.of(t.getVersion(), review == null ? null : review.getDecision(), newest),
                    review == null ? null : review.getNote(),
                    review == null ? null : review.getCreatedAt());
        }).toList();
    }

    /**
     * Saves the clauses as a new version, awaiting HomeTrue's review, or does
     * nothing when they are the same as the current ones. Letters keep using
     * the last approved version until the new one is approved. Every clause is checked; all problems come back
     * at once, keyed by position, and nothing is saved.
     */
    @Transactional
    public EstateLetterTermsDto save(UUID estateId, SaveLetterTermsRequest request) {
        TenantScope scope = PortalScopes.current();
        UUID tenantId = PortalScopes.companyWide(scope);
        EstateDocumentFacts facts = estate(estateId, tenantId);

        Map<String, String> problems = ClauseRules.problems(request.clauses());
        if (!problems.isEmpty()) {
            throw new DocumentException.ClausesRefused(problems);
        }
        List<String> clauses = request.clauses().stream().map(String::strip).toList();

        Optional<EstateLetterTerms> current = terms.findFirstByEstateIdOrderByVersionDesc(estateId);
        if (current.isPresent() && Arrays.asList(current.get().getClauses()).equals(clauses)) {
            return toDto(facts, current);
        }
        int version = current.map(t -> t.getVersion() + 1).orElse(1);
        // saveAndFlush: two saves at once collide on the (estate, version) key here,
        // as a translated exception the handler answers with 409.
        EstateLetterTerms saved = terms.saveAndFlush(EstateLetterTerms.builder()
                .tenantId(facts.tenantId())
                .branchId(facts.branchId())
                .estateId(estateId)
                .version(version)
                .clauses(clauses.toArray(String[]::new))
                .authorUserId(scope.userId())
                .build());
        auditApi.record(AuditEntryRequest.of(scope.userId(), "estate.letter_terms_saved", "estate", estateId,
                tenantId, "Allocation conditions for '" + facts.name() + "' saved as version " + version + " ("
                        + clauses.size() + (clauses.size() == 1 ? " clause)." : " clauses).")));
        log.info("Letter terms v{} saved for estate {} by actor {}", version, estateId, scope.userId());
        return toDto(facts, Optional.of(saved));
    }

    /** The caller's company's estate, read under their RLS; anything else is the same 404. */
    private EstateDocumentFacts estate(UUID estateId, UUID tenantId) {
        return estates.forDocuments(estateId)
                .filter(facts -> tenantId.equals(facts.tenantId()))
                .orElseThrow(() -> new DocumentException.NotFound("ESTATE_NOT_FOUND", "Estate not found."));
    }

    private EstateLetterTermsDto toDto(EstateDocumentFacts facts, Optional<EstateLetterTerms> current) {
        Optional<EstateLetterTermsReview> review = current.flatMap(t -> reviews.findByTermsId(t.getId()));
        Integer approvedVersion = reviews.findFirstByEstateIdAndDecisionOrderByTermsVersionDesc(
                facts.estateId(), ReviewDecision.APPROVED).map(EstateLetterTermsReview::getTermsVersion).orElse(null);
        return new EstateLetterTermsDto(
                facts.estateId(),
                facts.name(),
                current.map(EstateLetterTerms::getVersion).orElse(0),
                current.map(t -> List.of(t.getClauses())).orElse(List.of()),
                current.map(EstateLetterTerms::getCreatedAt).orElse(null),
                DisclosureClauses.of(facts),
                new EstateLetterTermsDto.DisclosureVersionsDto(
                        facts.fees() == null ? null : facts.fees().version(),
                        facts.refundTerms() == null ? null : facts.refundTerms().version(),
                        facts.defaultTerms() == null ? null : facts.defaultTerms().version()),
                DisclosureClauses.missing(facts),
                current.map(t -> ReviewStatus.of(t.getVersion(), review.map(EstateLetterTermsReview::getDecision).orElse(null), t.getVersion()))
                        .orElse(null),
                review.map(EstateLetterTermsReview::getNote).orElse(null),
                review.map(EstateLetterTermsReview::getCreatedAt).orElse(null),
                approvedVersion);
    }
}
