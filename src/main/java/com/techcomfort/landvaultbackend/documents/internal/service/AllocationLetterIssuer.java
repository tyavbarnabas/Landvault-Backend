package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.PlotAllocated;
import com.techcomfort.landvaultbackend.documents.dto.LetterVersionsDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterheadImageDto;
import com.techcomfort.landvaultbackend.documents.dto.PrintedLetterheadDto;
import com.techcomfort.landvaultbackend.documents.internal.domain.AllocationLetter;
import com.techcomfort.landvaultbackend.documents.internal.render.DocumentFormats;
import com.techcomfort.landvaultbackend.documents.internal.render.LetterClauses;
import com.techcomfort.landvaultbackend.documents.internal.render.LetterPlaceholders;
import com.techcomfort.landvaultbackend.documents.internal.repository.AllocationLetterRepository;
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
import java.util.UUID;

/**
 * AL: issues the buyer's allocation letter when finance allocates a sale,
 * inside the allocating transaction (a plain synchronous listener, MANDATORY —
 * the {@code PlotAllocated} pattern). The plot becoming SOLD and its letter
 * commit together or not at all. Never make this asynchronous or after-commit.
 * <p>
 * Checkout refuses to allocate before this can succeed ({@code AllocationLetterGate}),
 * so a failure here is a bug, and is loud: it rolls the sale back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AllocationLetterIssuer {

    private final AllocationLetterRepository letters;
    private final LetterApprovalService approvals;
    private final EstateDocumentsApi estates;
    private final IdentityApi identityApi;
    private final AuditApi auditApi;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(PlotAllocated event) {
        if (letters.existsByTransactionId(event.transactionId())) {
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
        List<String> clauses = LetterClauses.of(setup.template().standardClauses(),
                List.of(setup.terms().getClauses()), setup.facts());

        PrintedLetterheadDto p = setup.letterhead();
        LetterVersionsDto v = setup.versions();
        AllocationLetter letter = letters.saveAndFlush(AllocationLetter.builder()
                .number(number(issuedAt, letters.nextNumber()))
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
                .termsId(setup.terms().getId())
                .termsVersion(v.terms())
                .templateSource(v.templateSource())
                .templateVersion(v.template())
                .companyLetterheadVersion(v.companyLetterhead())
                .branchLetterheadVersion(v.branchLetterhead())
                .feesVersion(v.fees())
                .refundTermsVersion(v.refundTerms())
                .defaultTermsVersion(v.defaultTerms())
                .build());
        auditApi.record(AuditEntryRequest.of(event.actorUserId(), "allocation_letter.issued", "allocation_letter",
                letter.getId(), event.sellerTenantId(), "Allocation letter " + letter.getNumber() + " issued for "
                        + plot.label() + ", " + setup.facts().name() + " (conditions v" + v.terms() + ", template "
                        + v.templateSource() + (v.template() == null ? "" : " v" + v.template()) + ")."));
        log.info("Allocation letter {} issued for transaction {}", letter.getNumber(), event.transactionId());
    }

    /** {@code HT-ALC-2026-000123}: the year it was issued, in Nigeria, and a number that never repeats. */
    static String number(Instant issuedAt, long sequence) {
        return "HT-ALC-" + issuedAt.atZone(DocumentFormats.NIGERIA).getYear() + "-" + String.format("%06d", sequence);
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
