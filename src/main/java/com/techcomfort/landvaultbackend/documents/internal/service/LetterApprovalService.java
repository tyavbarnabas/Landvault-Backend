package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.documents.dto.ApproveLetterRequest;
import com.techcomfort.landvaultbackend.documents.dto.LetterApprovalDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterVersionsDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterheadImageDto;
import com.techcomfort.landvaultbackend.documents.dto.PrintedLetterheadDto;
import com.techcomfort.landvaultbackend.documents.internal.domain.EstateLetterApproval;
import com.techcomfort.landvaultbackend.documents.internal.domain.EstateLetterTerms;
import com.techcomfort.landvaultbackend.documents.internal.enums.LetterApprovalAction;
import com.techcomfort.landvaultbackend.documents.internal.enums.ReviewDecision;
import com.techcomfort.landvaultbackend.documents.internal.exceptions.DocumentException;
import com.techcomfort.landvaultbackend.documents.internal.render.AllocationLetterContent;
import com.techcomfort.landvaultbackend.documents.internal.render.AllocationLetterRenderer;
import com.techcomfort.landvaultbackend.documents.internal.render.DocumentFormats;
import com.techcomfort.landvaultbackend.documents.internal.render.LetterClauses;
import com.techcomfort.landvaultbackend.documents.internal.render.LetterPlaceholders;
import com.techcomfort.landvaultbackend.documents.internal.render.PdfImages;
import com.techcomfort.landvaultbackend.documents.internal.repository.EstateLetterApprovalRepository;
import com.techcomfort.landvaultbackend.documents.internal.repository.EstateLetterTermsRepository;
import com.techcomfort.landvaultbackend.documents.internal.repository.EstateLetterTermsReviewRepository;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentFacts;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentsApi;
import com.techcomfort.landvaultbackend.storage.StorageApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * DS-5: a sample allocation letter per estate, and the Executive Director's
 * approval of it. An approval records the versions the letter was drawn from
 * and stands only while they're all current — derived on every read, never
 * cleared by hand. Reads need {@code portal.documents.view}; approving and
 * withdrawing need {@code portal.documents.manage} and a company-wide scope.
 * See AGENTS.md, "Documents: previewing and approving the letter".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LetterApprovalService {

    public static final String SAMPLE_WATERMARK = "SAMPLE — NOT VALID";

    private final EstateLetterApprovalRepository approvals;
    private final EstateLetterTermsRepository terms;
    private final EstateLetterTermsReviewRepository reviews;
    private final EstateDocumentsApi estates;
    private final LetterheadService letterheads;
    private final LetterTemplateService letterTemplates;
    private final AllocationLetterRenderer renderer;
    private final StorageApi storage;
    private final AuditApi auditApi;

    /** A rendered sample: the bytes and a file name. */
    public record Sample(byte[] bytes, String fileName) {
    }

    /** What an issued letter is drawn from: an approved, current setup with nothing missing. */
    public record IssuableLetter(UUID approvalId, EstateDocumentFacts facts, PrintedLetterheadDto letterhead,
                                 LetterTemplateService.ResolvedTemplate template, EstateLetterTerms terms,
                                 LetterVersionsDto versions) {
    }

    @Transactional(readOnly = true)
    public LetterApprovalDto status(UUID estateId) {
        return toDto(state(estateId, PortalScopes.company(PortalScopes.current())));
    }

    /**
     * The letter as a buyer would receive it, with placeholders for the buyer
     * and plot, stamped as a sample and with no verification code. Uses the
     * HomeTrue-approved conditions; {@code latest} shows the newest version
     * instead (marked as not yet approved), and so does an estate with none
     * approved yet.
     */
    @Transactional(readOnly = true)
    public Sample preview(UUID estateId, boolean latest, boolean jpeg) {
        UUID tenantId = PortalScopes.company(PortalScopes.current());
        State s = state(estateId, tenantId);
        Optional<EstateLetterTerms> shown = latest || s.approvedTerms().isEmpty() ? s.newestTerms() : s.approvedTerms();
        LetterTemplateService.ResolvedTemplate template = latest
                ? letterTemplates.forEstate(tenantId, s.facts().branchId(), true) : s.template();
        boolean shownApproved = shown.isPresent() && s.approvedTerms().isPresent()
                && shown.get().getId().equals(s.approvedTerms().get().getId()) && template.approved();

        List<String> clauses = LetterClauses.of(template.standardClauses(),
                shown.map(t -> List.of(t.getClauses())).orElse(List.of()), s.facts());
        Instant now = Instant.now();
        Map<String, String> fields = Map.of(
                "buyerName", "[Buyer's full name]",
                "plot", "Plot [number] of about [size] sqm",
                "estate", s.facts().name(),
                "location", Objects.toString(location(s.facts()), ""),
                "applicationDate", "[date of application]",
                "allocationDate", DocumentFormats.date(now));

        PrintedLetterheadDto p = s.letterhead().printed();
        AllocationLetterContent content = new AllocationLetterContent(
                new AllocationLetterContent.Company(p.companyName(), p.rcNumber(), p.address(), p.phone(), p.email(),
                        p.website()),
                bytes(p.logo()), bytes(p.signature()), bytes(p.seal()),
                now,
                List.of("[Buyer's full name]", "[Buyer's phone number]"),
                "Dear [Buyer's name],",
                s.facts().name(),
                location(s.facts()),
                LetterPlaceholders.fill(template.opening(), fields),
                clauses,
                LetterPlaceholders.fill(template.closing(), fields),
                template.signOff(),
                p.signatoryName(),
                p.signatoryTitle(),
                List.of(SAMPLE_WATERMARK, shown.isEmpty() ? "No conditions saved yet"
                        : shownApproved ? "Preview only. Not an allocation."
                        : "Wording not yet approved by HomeTrue"),
                null, null, null);
        byte[] pdf = renderer.pdf(content);
        String name = "Sample-allocation-letter-" + slug(s.facts().name());
        return jpeg ? new Sample(PdfImages.allPagesAsJpeg(pdf), name + ".jpg") : new Sample(pdf, name + ".pdf");
    }

    /**
     * Approves the letter as drawn from {@code request.versions()} — refused if
     * anything has changed since the preview, or if something the letter needs
     * is missing. Approving an already-approved, unchanged letter changes nothing.
     */
    @Transactional
    public LetterApprovalDto approve(UUID estateId, ApproveLetterRequest request) {
        TenantScope scope = PortalScopes.current();
        UUID tenantId = PortalScopes.companyWide(scope);
        State s = state(estateId, tenantId);
        if (!s.blockers().isEmpty()) {
            throw new DocumentException.Conflict("LETTER_NOT_READY", "The letter can't be approved yet: "
                    + String.join(", ", s.blockers()) + ".");
        }
        if (!s.current().equals(request.versions())) {
            throw new DocumentException.Conflict("LETTER_SETUP_CHANGED", "Something on the letter has changed since "
                    + "you previewed it. Preview it again, then approve.");
        }
        if (s.approved()) {
            return toDto(s);
        }
        LetterVersionsDto v = s.current();
        approvals.saveAndFlush(EstateLetterApproval.builder()
                .tenantId(tenantId)
                .branchId(s.facts().branchId())
                .estateId(estateId)
                .action(LetterApprovalAction.APPROVED)
                .actorUserId(scope.userId())
                .termsVersion(v.terms())
                .companyLetterheadVersion(v.companyLetterhead())
                .branchLetterheadVersion(v.branchLetterhead())
                .feesVersion(v.fees())
                .refundTermsVersion(v.refundTerms())
                .defaultTermsVersion(v.defaultTerms())
                .templateSource(v.templateSource())
                .templateVersion(v.template())
                .build());
        auditApi.record(AuditEntryRequest.of(scope.userId(), "estate.letter_approved", "estate", estateId, tenantId,
                "Allocation letter for '" + s.facts().name() + "' approved: " + describe(v) + "."));
        log.info("Allocation letter approved for estate {} by actor {}", estateId, scope.userId());
        return toDto(state(estateId, tenantId));
    }

    /** Stops letters going out until it's approved again. A no-op when it isn't approved. */
    @Transactional
    public LetterApprovalDto withdraw(UUID estateId) {
        TenantScope scope = PortalScopes.current();
        UUID tenantId = PortalScopes.companyWide(scope);
        State s = state(estateId, tenantId);
        if (!s.approved()) {
            return toDto(s);
        }
        approvals.saveAndFlush(EstateLetterApproval.builder()
                .tenantId(tenantId)
                .branchId(s.facts().branchId())
                .estateId(estateId)
                .action(LetterApprovalAction.WITHDRAWN)
                .actorUserId(scope.userId())
                .build());
        auditApi.record(AuditEntryRequest.of(scope.userId(), "estate.letter_approval_withdrawn", "estate", estateId,
                tenantId, "Allocation letter approval for '" + s.facts().name() + "' withdrawn."));
        log.info("Allocation letter approval withdrawn for estate {} by actor {}", estateId, scope.userId());
        return toDto(state(estateId, tenantId));
    }

    /**
     * AL: the setup a letter for this estate is issued from, read under the
     * caller's own scope (finance at verification) — present only while the
     * approval stands and nothing the letter needs is missing. Empty for an
     * estate the caller can't see.
     */
    @Transactional(readOnly = true)
    public Optional<IssuableLetter> issuable(UUID estateId) {
        UUID tenantId = PortalScopes.company(PortalScopes.current());
        if (estates.forDocuments(estateId).filter(f -> tenantId.equals(f.tenantId())).isEmpty()) {
            return Optional.empty();
        }
        State s = state(estateId, tenantId);
        if (!s.approved() || !s.blockers().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new IssuableLetter(s.latest().orElseThrow().getId(), s.facts(), s.letterhead().printed(),
                s.template(), s.approvedTerms().orElseThrow(), s.current()));
    }

    // ------------------------------------------------------------- state

    private record State(EstateDocumentFacts facts, LetterheadService.ResolvedLetterhead letterhead,
                         LetterTemplateService.ResolvedTemplate template, Optional<EstateLetterTerms> approvedTerms, Optional<EstateLetterTerms> newestTerms,
                         LetterVersionsDto current, Optional<EstateLetterApproval> latest, List<String> blockers) {

        boolean approved() {
            return latest.filter(a -> a.getAction() == LetterApprovalAction.APPROVED)
                    .map(a -> versionsOf(a).equals(current)).orElse(false);
        }
    }

    private State state(UUID estateId, UUID tenantId) {
        EstateDocumentFacts facts = estates.forDocuments(estateId)
                .filter(f -> tenantId.equals(f.tenantId()))
                .orElseThrow(() -> new DocumentException.NotFound("ESTATE_NOT_FOUND", "Estate not found."));
        LetterheadService.ResolvedLetterhead letterhead = letterheads.resolve(tenantId, facts.branchId());
        Optional<EstateLetterTerms> approvedTerms = reviews
                .findFirstByEstateIdAndDecisionOrderByTermsVersionDesc(estateId, ReviewDecision.APPROVED)
                .flatMap(r -> terms.findById(r.getTermsId()));
        Optional<EstateLetterTerms> newestTerms = terms.findFirstByEstateIdOrderByVersionDesc(estateId);
        LetterTemplateService.ResolvedTemplate template = letterTemplates.forEstate(tenantId, facts.branchId(), false);
        LetterVersionsDto current = new LetterVersionsDto(
                approvedTerms.map(EstateLetterTerms::getVersion).orElse(null),
                letterhead.companyVersion(),
                letterhead.branchVersion(),
                facts.fees() == null ? null : facts.fees().version(),
                facts.refundTerms() == null ? null : facts.refundTerms().version(),
                facts.defaultTerms() == null ? null : facts.defaultTerms().version(),
                template.source(),
                template.version());
        List<String> blockers = new ArrayList<>();
        if (approvedTerms.isEmpty()) {
            blockers.add("no_approved_terms");
        }
        blockers.addAll(letterhead.missing());
        return new State(facts, letterhead, template, approvedTerms, newestTerms, current,
                approvals.findFirstByEstateIdOrderByCreatedAtDesc(estateId), blockers);
    }

    private static LetterApprovalDto toDto(State s) {
        Optional<EstateLetterApproval> standing = s.latest()
                .filter(a -> a.getAction() == LetterApprovalAction.APPROVED);
        LetterVersionsDto seen = standing.map(LetterApprovalService::versionsOf).orElse(null);
        boolean approved = s.approved();
        boolean newerAwaiting = s.newestTerms().map(EstateLetterTerms::getVersion)
                .filter(newest -> !newest.equals(s.current().terms())).isPresent();
        return new LetterApprovalDto(
                s.facts().estateId(),
                s.facts().name(),
                approved,
                standing.map(EstateLetterApproval::getCreatedAt).orElse(null),
                seen,
                s.current(),
                seen == null || approved ? List.of() : changed(seen, s.current()),
                s.blockers(),
                newerAwaiting);
    }

    private static LetterVersionsDto versionsOf(EstateLetterApproval a) {
        return new LetterVersionsDto(a.getTermsVersion(), a.getCompanyLetterheadVersion(),
                a.getBranchLetterheadVersion(), a.getFeesVersion(), a.getRefundTermsVersion(),
                a.getDefaultTermsVersion(), a.getTemplateSource(), a.getTemplateVersion());
    }

    private static List<String> changed(LetterVersionsDto was, LetterVersionsDto now) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(was.terms(), now.terms())) {
            changed.add("terms");
        }
        if (!Objects.equals(was.companyLetterhead(), now.companyLetterhead())) {
            changed.add("company_letterhead");
        }
        if (!Objects.equals(was.branchLetterhead(), now.branchLetterhead())) {
            changed.add("branch_letterhead");
        }
        if (!Objects.equals(was.fees(), now.fees())) {
            changed.add("fees");
        }
        if (!Objects.equals(was.refundTerms(), now.refundTerms())) {
            changed.add("refund_terms");
        }
        if (!Objects.equals(was.defaultTerms(), now.defaultTerms())) {
            changed.add("default_terms");
        }
        if (!Objects.equals(was.templateSource(), now.templateSource()) || !Objects.equals(was.template(), now.template())) {
            changed.add("template");
        }
        return changed;
    }

    private static String describe(LetterVersionsDto v) {
        return "conditions v" + v.terms() + ", company letterhead v" + v.companyLetterhead()
                + (v.branchLetterhead() == null ? "" : ", branch letterhead v" + v.branchLetterhead())
                + ", fees " + version(v.fees()) + ", refund terms " + version(v.refundTerms())
                + ", default terms " + version(v.defaultTerms()) + ", template " + v.templateSource()
                + (v.template() == null ? "" : " v" + v.template());
    }

    private static String version(Integer v) {
        return v == null ? "none" : "v" + v;
    }

    private byte[] bytes(LetterheadImageDto image) {
        return image == null ? null : storage.read(image.fileId());
    }

    /** "Jikwoyi, Abuja, Federal Capital Territory (Abuja)" — whatever was entered, each once. */
    static String location(EstateDocumentFacts facts) {
        Set<String> parts = new LinkedHashSet<>();
        for (String part : new String[]{facts.area(), facts.city(), facts.state()}) {
            if (part != null && !part.isBlank()) {
                parts.add(part.strip());
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String slug(String name) {
        String slug = name == null ? "" : name.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "estate" : slug;
    }
}
