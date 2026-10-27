package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.PlotAllocated;
import com.techcomfort.landvaultbackend.documents.dto.LetterVersionsDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterheadImageDto;
import com.techcomfort.landvaultbackend.documents.dto.PrintedLetterheadDto;
import com.techcomfort.landvaultbackend.documents.internal.domain.AllocationLetter;
import com.techcomfort.landvaultbackend.documents.internal.domain.OfferLetter;
import com.techcomfort.landvaultbackend.documents.internal.enums.LetterKind;
import com.techcomfort.landvaultbackend.documents.internal.render.DocumentFormats;
import com.techcomfort.landvaultbackend.documents.internal.render.LetterClauses;
import com.techcomfort.landvaultbackend.documents.internal.render.LetterPlaceholders;
import com.techcomfort.landvaultbackend.documents.internal.repository.AllocationLetterRepository;
import com.techcomfort.landvaultbackend.documents.internal.repository.OfferLetterAcceptanceRepository;
import com.techcomfort.landvaultbackend.documents.internal.repository.OfferLetterRepository;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.inventory.EstateDocumentsApi;
import com.techcomfort.landvaultbackend.inventory.PlotDocumentFacts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * AL: issues the buyer's allocation letter when finance allocates a sale,
 * inside the allocating transaction (a plain synchronous listener, MANDATORY —
 * the {@code PlotAllocated} pattern). The plot becoming SOLD and its letter
 * commit together or not at all. Never make this asynchronous or after-commit.
 * <p>
 * Checkout refuses to allocate before this can succeed ({@code AllocationLetterGate}),
 * so a failure here is a bug, and is loud: it rolls the sale back.
 * <p>
 * OL: when the buyer accepted an offer, its conditions and the versions they
 * came from are copied, never the estate's current ones — the developer's
 * later changes apply to new buyers. Only the letterhead, signatory and
 * template wording follow the current approved setup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AllocationLetterIssuer {

    private final AllocationLetterRepository letters;
    private final OfferLetterRepository offers;
    private final OfferLetterAcceptanceRepository acceptances;
    private final LetterApprovalService approvals;
    private final EstateDocumentsApi estates;
    private final IdentityApi identityApi;
    private final AuditApi auditApi;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(PlotAllocated event) {
        LetterKind kind = event.provisional() ? LetterKind.PROVISIONAL : LetterKind.ALLOCATION;
        if (letters.existsByTransactionIdAndKind(event.transactionId(), kind)) {
            return;
        }
        LetterApprovalService.IssuableLetter setup = approvals.issuable(event.estateId())
                .orElseThrow(() -> new IllegalStateException(
                        "No approved allocation letter for estate " + event.estateId() + "; the gate should have refused"));
        PlotDocumentFacts plot = estates.plotForDocuments(event.plotId())
                .orElseThrow(() -> new IllegalStateException("Plot " + event.plotId() + " not readable for its letter"));
        String buyerName = identityApi.fullNameOf(event.buyerUserId())
                .orElseThrow(() -> new IllegalStateException("No buyer " + event.buyerUserId()));
        List<String> buyerLines = new ArrayList<>(List.of(buyerName));
        // The copy taken when the purchase was opened, never the account's
        // current address. None on purchases from before addresses existed.
        if (event.buyerAddress() != null) {
            buyerLines.addAll(event.buyerAddress().printedLines());
        }
        identityApi.phoneOf(event.buyerUserId()).ifPresent(buyerLines::add);

        Instant issuedAt = Instant.now();
        String location = LetterApprovalService.location(setup.facts());
        Map<String, String> fields = Map.of(
                "buyerName", buyerName,
                "plot", plotPhrase(plot),
                "estate", setup.facts().name(),
                "location", Objects.toString(location, ""),
                "applicationDate", DocumentFormats.date(event.reservedAt()),
                "allocationDate", DocumentFormats.date(issuedAt));
        Optional<OfferLetter> offer = offers.findByTransactionId(event.transactionId())
                .filter(o -> acceptances.findByOfferLetterId(o.getId()).isPresent());
        boolean flexible = "flexible".equals(event.plan());
        List<String> clauses = new ArrayList<>();
        // Flexible contribution: the plan's own terms (leaving, the gain share, selling, a quiet plan) come
        // first, as the offer stated them — they replace the estate's instalment-style withdrawal and
        // revocation terms that follow, and say so.
        if (flexible) {
            offer.ifPresent(o -> clauses.addAll(List.of(o.getPriceClauses())));
        }
        clauses.addAll(offer.map(o -> List.of(o.getClauses())).orElseGet(() -> LetterClauses.of(
                setup.template().standardClauses(), List.of(setup.terms().getClauses()), setup.facts())));
        if (kind == LetterKind.PROVISIONAL) {
            clauses.add(provisionalClause(offer.map(OfferLetter::getNumber).orElse(null), flexible));
        }

        PrintedLetterheadDto p = setup.letterhead();
        LetterVersionsDto v = setup.versions();
        AllocationLetter letter = letters.saveAndFlush(AllocationLetter.builder()
                .kind(kind)
                .number(kind == LetterKind.PROVISIONAL ? provisionalNumber(issuedAt, letters.nextProvisionalNumber())
                        : number(issuedAt, letters.nextNumber()))
                .verificationCode(VerificationCodes.generate())
                .transactionId(event.transactionId())
                .buyerUserId(event.buyerUserId())
                .sellerTenantId(event.sellerTenantId())
                .branchId(setup.facts().branchId())
                .estateId(event.estateId())
                .plotId(event.plotId())
                .issuedAt(issuedAt)
                .issuedBy(event.actorUserId())
                .companyName(p.companyName())
                .companyRcNumber(p.rcNumber())
                .companyAddress(p.address())
                .companyPhone(p.phone())
                .companyEmail(p.email())
                .companyWebsite(p.website())
                .logoFileId(fileId(p.logo()))
                .signatureFileId(fileId(p.signature()))
                .sealFileId(fileId(p.seal()))
                .signatoryName(p.signatoryName())
                .signatoryTitle(p.signatoryTitle())
                .buyerName(buyerName)
                .buyerLines(buyerLines.toArray(String[]::new))
                .salutation("Dear " + buyerName + ",")
                .estateName(setup.facts().name())
                .estateLocation(location)
                .plotLabel(plot.label())
                .opening(LetterPlaceholders.fill(setup.template().opening(), fields))
                .clauses(clauses.toArray(String[]::new))
                .closing(LetterPlaceholders.fill(setup.template().closing(), fields))
                .signOff(setup.template().signOff())
                .approvalId(setup.approvalId())
                .termsId(offer.map(OfferLetter::getTermsId).orElse(setup.terms().getId()))
                .termsVersion(offer.map(OfferLetter::getTermsVersion).orElse(v.terms()))
                .templateSource(v.templateSource())
                .templateVersion(v.template())
                .companyLetterheadVersion(v.companyLetterhead())
                .branchLetterheadVersion(v.branchLetterhead())
                .feesVersion(offer.isPresent() ? offer.get().getFeesVersion() : v.fees())
                .refundTermsVersion(offer.isPresent() ? offer.get().getRefundTermsVersion() : v.refundTerms())
                .defaultTermsVersion(offer.isPresent() ? offer.get().getDefaultTermsVersion() : v.defaultTerms())
                .offerLetterId(offer.map(OfferLetter::getId).orElse(null))
                .build());
        auditApi.record(AuditEntryRequest.of(event.actorUserId(), kind == LetterKind.PROVISIONAL
                        ? "allocation_letter.provisional_issued" : "allocation_letter.issued", "allocation_letter",
                letter.getId(), event.sellerTenantId(), (kind == LetterKind.PROVISIONAL ? "Provisional allocation letter "
                        : "Allocation letter ") + letter.getNumber() + " issued for "
                        + plot.label() + ", " + setup.facts().name() + offer.map(o -> " on the terms of offer "
                        + o.getNumber()).orElse("") + " (conditions v" + letter.getTermsVersion() + ", template "
                        + v.templateSource() + (v.template() == null ? "" : " v" + v.template()) + ")."));
        log.info("Allocation letter {} issued for transaction {}", letter.getNumber(), event.transactionId());
    }

    /** {@code HT-ALC-2026-000123}: the year it was issued, in Nigeria, and a number that never repeats. */
    static String number(Instant issuedAt, long sequence) {
        return "HT-ALC-" + issuedAt.atZone(DocumentFormats.NIGERIA).getYear() + "-" + String.format("%06d", sequence);
    }

    /** {@code HT-PAL-2026-000123}: a provisional letter's own sequence, so neither numbering has gaps. */
    static String provisionalNumber(Instant issuedAt, long sequence) {
        return "HT-PAL-" + issuedAt.atZone(DocumentFormats.NIGERIA).getYear() + "-" + String.format("%06d", sequence);
    }

    /** HomeTrue's fixed line on a provisional letter (decision 4): what makes it final, and what ends it. */
    static String provisionalClause(String offerNumber, boolean flexible) {
        String offer = offerNumber == null ? "" : " (" + offerNumber + ")";
        return flexible
                ? "That this allocation is provisional: it becomes final, and the letter of allocation is issued, "
                + "when the plan total in your accepted offer" + offer + " has been paid and verified. If you leave "
                + "the plan, or it is ended, the allocation ends on the terms of that offer."
                : "That this allocation is provisional: it becomes final, and the letter of allocation is issued, when "
                + "every instalment in your accepted offer" + offer
                + " has been paid and verified. If the plan is revoked, the allocation ends on the terms of that "
                + "offer.";
    }

    /** "Block A, Plot 7 of about 500 sqm" — the sample's "Plot [number] of about [size] sqm", filled in. */
    static String plotPhrase(PlotDocumentFacts plot) {
        String size = DocumentFormats.size(plot.nominalSizeSqm());
        return size == null ? plot.label() : plot.label() + " of about " + size;
    }

    private static UUID fileId(LetterheadImageDto image) {
        return image == null ? null : image.fileId();
    }
}
