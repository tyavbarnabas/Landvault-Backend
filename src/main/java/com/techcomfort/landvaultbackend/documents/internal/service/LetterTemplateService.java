package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.documents.dto.LetterTemplateDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterTemplateVersionDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterTemplatesOverviewDto;
import com.techcomfort.landvaultbackend.documents.dto.SaveLetterTemplateRequest;
import com.techcomfort.landvaultbackend.documents.internal.domain.LetterTemplate;
import com.techcomfort.landvaultbackend.documents.internal.domain.LetterTemplateReview;
import com.techcomfort.landvaultbackend.documents.internal.enums.ReviewDecision;
import com.techcomfort.landvaultbackend.documents.internal.exceptions.DocumentException;
import com.techcomfort.landvaultbackend.documents.internal.render.LetterPlaceholders;
import com.techcomfort.landvaultbackend.documents.internal.repository.LetterTemplateRepository;
import com.techcomfort.landvaultbackend.documents.internal.repository.LetterTemplateReviewRepository;
import com.techcomfort.landvaultbackend.tenancy.CompanyLetterheadDetails;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Letter templates: the company's and each branch's own wording for allocation
 * letters. Every save is a new version that HomeTrue reviews; letters use the
 * newest approved one, a branch's replacing the company's as a whole, and
 * HomeTrue's default wording until the company has one. Reads need
 * {@code portal.documents.view}; saving needs {@code portal.documents.manage}
 * and a company-wide scope. See AGENTS.md, "Documents: letter templates".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LetterTemplateService {

    public static final String DEFAULT = "default";

    private final LetterTemplateRepository templates;
    private final LetterTemplateReviewRepository reviews;
    private final TenancyApi tenancy;
    private final AuditApi auditApi;

    /**
     * The wording a letter uses. {@code version} is null for HomeTrue's default;
     * {@code approved} is false only when a preview asked for the newest version
     * and it isn't approved yet.
     */
    public record ResolvedTemplate(String source, Integer version, String opening, String closing, String signOff,
                                   List<String> standardClauses, boolean approved) {

        static ResolvedTemplate ofDefault() {
            return new ResolvedTemplate(DEFAULT, null, LetterPlaceholders.DEFAULT_OPENING, null,
                    LetterPlaceholders.DEFAULT_SIGN_OFF, List.of(), true);
        }
    }

    // ------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public LetterTemplatesOverviewDto overview() {
        UUID tenantId = PortalScopes.company(PortalScopes.current());
        CompanyLetterheadDetails details = tenancy.letterheadDetails(tenantId)
                .orElseThrow(DocumentException.CompanyScopeRequired::new);
        List<LetterTemplate> all = templates.findByTenantIdOrderByVersionDesc(tenantId);
        Map<UUID, LetterTemplateReview> decisions = decisions(all);

        LetterTemplateDto company = toDto(new LetterTarget(null, null), all, decisions, null);
        List<LetterTemplateDto> branches = details.branches().stream()
                .map(b -> toDto(new LetterTarget(b.id(), b.name()), all, decisions, company))
                .toList();
        return new LetterTemplatesOverviewDto(company, branches, LetterPlaceholders.FIELDS,
                new LetterTemplatesOverviewDto.DefaultWording(LetterPlaceholders.DEFAULT_OPENING,
                        LetterPlaceholders.DEFAULT_SIGN_OFF));
    }

    @Transactional(readOnly = true)
    public List<LetterTemplateVersionDto> versions(String target) {
        UUID tenantId = PortalScopes.company(PortalScopes.current());
        LetterTarget t = LetterTarget.resolve(tenancy, tenantId, target);
        List<LetterTemplate> mine = of(t, templates.findByTenantIdOrderByVersionDesc(tenantId));
        Map<UUID, LetterTemplateReview> decisions = decisions(mine);
        int newest = mine.isEmpty() ? 0 : mine.getFirst().getVersion();
        return mine.stream().map(v -> toVersionDto(v, decisions.get(v.getId()), newest)).toList();
    }

    // ------------------------------------------------------------- saving

    /**
     * Saves the template as a new version, awaiting HomeTrue's review, or does
     * nothing when it's the same as the current one. Every problem comes back at
     * once, keyed by field, and nothing is saved.
     */
    @Transactional
    public LetterTemplateDto save(String target, SaveLetterTemplateRequest request) {
        TenantScope scope = PortalScopes.current();
        UUID tenantId = PortalScopes.companyWide(scope);
        LetterTarget t = LetterTarget.resolve(tenancy, tenantId, target);

        List<String> clauses = request.standardClauses() == null ? List.of() : request.standardClauses();
        Map<String, String> problems = TemplateRules.problems(request.opening(), request.closing(), request.signOff(),
                clauses);
        if (!problems.isEmpty()) {
            throw new DocumentException.TemplateRefused(problems);
        }
        String opening = request.opening().strip();
        String closing = request.closing() == null || request.closing().isBlank() ? null : request.closing().strip();
        String signOff = request.signOff().strip();
        String[] standard = clauses.stream().map(String::strip).toArray(String[]::new);

        Optional<LetterTemplate> current = newest(tenantId, t.branchId());
        if (current.isPresent() && same(current.get(), opening, closing, signOff, standard)) {
            return reload(tenantId, t);
        }
        int version = current.map(v -> v.getVersion() + 1).orElse(1);
        // saveAndFlush: two saves at once collide on (tenant, branch, version) here, answered with 409.
        templates.saveAndFlush(LetterTemplate.builder()
                .tenantId(tenantId)
                .branchId(t.branchId())
                .version(version)
                .opening(opening)
                .closing(closing)
                .signOff(signOff)
                .standardClauses(standard)
                .authorUserId(scope.userId())
                .build());
        auditApi.record(AuditEntryRequest.of(scope.userId(), "letter_template.saved", "letter_template",
                t.branchId() == null ? tenantId : t.branchId(), tenantId,
                (t.branchId() == null ? "Company" : "Branch '" + t.branchName() + "'") + " letter template saved as version "
                        + version + " (" + standard.length + " standard conditions)."));
        log.info("Letter template v{} saved for {} {} by actor {}", version, t.source(),
                t.branchId() == null ? tenantId : t.branchId(), scope.userId());
        return reload(tenantId, t);
    }

    // ------------------------------------------------------------- for letters

    /**
     * The wording a letter for an estate under {@code branchId} (null: a
     * company-level estate) uses: the branch's approved template, else the
     * company's, else HomeTrue's default. With {@code latest}, a preview shows
     * the newest saved version instead — the branch's if it has any, else the
     * company's — whether or not HomeTrue has approved it. Read under the
     * caller's RLS.
     */
    @Transactional(readOnly = true)
    public ResolvedTemplate forEstate(UUID tenantId, UUID branchId, boolean latest) {
        if (latest) {
            Optional<LetterTemplate> newest = branchId == null ? Optional.empty() : newest(tenantId, branchId);
            if (newest.isEmpty()) {
                newest = newest(tenantId, null);
            }
            if (newest.isPresent()) {
                boolean approved = reviews.findByTemplateId(newest.get().getId())
                        .map(r -> r.getDecision() == ReviewDecision.APPROVED).orElse(false);
                return resolved(newest.get(), approved);
            }
            return ResolvedTemplate.ofDefault();
        }
        Optional<LetterTemplate> approved = branchId == null ? Optional.empty() : approved(tenantId, branchId);
        if (approved.isEmpty()) {
            approved = approved(tenantId, null);
        }
        return approved.map(a -> resolved(a, true)).orElseGet(ResolvedTemplate::ofDefault);
    }

    // ------------------------------------------------------------- shared

    private Optional<LetterTemplate> newest(UUID tenantId, UUID branchId) {
        return branchId == null
                ? templates.findFirstByTenantIdAndBranchIdIsNullOrderByVersionDesc(tenantId)
                : templates.findFirstByTenantIdAndBranchIdOrderByVersionDesc(tenantId, branchId);
    }

    private Optional<LetterTemplate> approved(UUID tenantId, UUID branchId) {
        Optional<LetterTemplateReview> review = branchId == null
                ? reviews.findFirstByTenantIdAndBranchIdIsNullAndDecisionOrderByTemplateVersionDesc(
                        tenantId, ReviewDecision.APPROVED)
                : reviews.findFirstByTenantIdAndBranchIdAndDecisionOrderByTemplateVersionDesc(
                        tenantId, branchId, ReviewDecision.APPROVED);
        return review.flatMap(r -> templates.findById(r.getTemplateId()));
    }

    private static ResolvedTemplate resolved(LetterTemplate t, boolean approved) {
        return new ResolvedTemplate(t.getBranchId() == null ? LetterTarget.COMPANY : "branch", t.getVersion(),
                t.getOpening(), t.getClosing(), t.getSignOff(), List.of(t.getStandardClauses()), approved);
    }

    private LetterTemplateDto reload(UUID tenantId, LetterTarget t) {
        List<LetterTemplate> all = templates.findByTenantIdOrderByVersionDesc(tenantId);
        Map<UUID, LetterTemplateReview> decisions = decisions(all);
        LetterTemplateDto company = toDto(new LetterTarget(null, null), all, decisions, null);
        return t.branchId() == null ? company : toDto(t, all, decisions, company);
    }

    /** {@code company} is the company's own DTO when building a branch's, for what the branch falls back to. */
    private static LetterTemplateDto toDto(LetterTarget t, List<LetterTemplate> all,
                                           Map<UUID, LetterTemplateReview> decisions, LetterTemplateDto company) {
        List<LetterTemplate> mine = of(t, all);
        int newest = mine.isEmpty() ? 0 : mine.getFirst().getVersion();
        LetterTemplateVersionDto current = mine.isEmpty() ? null
                : toVersionDto(mine.getFirst(), decisions.get(mine.getFirst().getId()), newest);
        LetterTemplateVersionDto approved = mine.stream()
                .filter(v -> decisions.containsKey(v.getId())
                        && decisions.get(v.getId()).getDecision() == ReviewDecision.APPROVED)
                .findFirst()
                .map(v -> toVersionDto(v, decisions.get(v.getId()), newest))
                .orElse(null);
        String inUse = approved != null ? t.source()
                : company != null && company.approved() != null ? LetterTarget.COMPANY : DEFAULT;
        return new LetterTemplateDto(t.source(), t.branchId(), t.branchName(), current, approved, inUse);
    }

    private static LetterTemplateVersionDto toVersionDto(LetterTemplate v, LetterTemplateReview review, int newest) {
        return new LetterTemplateVersionDto(v.getVersion(), v.getOpening(), v.getClosing(), v.getSignOff(),
                List.of(v.getStandardClauses()), v.getCreatedAt(),
                ReviewStatus.of(v.getVersion(), review == null ? null : review.getDecision(), newest),
                review == null ? null : review.getNote(), review == null ? null : review.getCreatedAt());
    }

    /** One target's versions, newest first, from all the company's. */
    private static List<LetterTemplate> of(LetterTarget t, List<LetterTemplate> all) {
        return all.stream().filter(v -> Objects.equals(v.getBranchId(), t.branchId())).toList();
    }

    private Map<UUID, LetterTemplateReview> decisions(List<LetterTemplate> versions) {
        return versions.isEmpty() ? Map.of() : reviews.findByTemplateIdIn(versions.stream().map(LetterTemplate::getId).toList())
                .stream().collect(Collectors.toMap(LetterTemplateReview::getTemplateId, Function.identity()));
    }

    private static boolean same(LetterTemplate t, String opening, String closing, String signOff, String[] clauses) {
        return t.getOpening().equals(opening) && Objects.equals(t.getClosing(), closing)
                && t.getSignOff().equals(signOff) && Arrays.equals(t.getStandardClauses(), clauses);
    }
}
