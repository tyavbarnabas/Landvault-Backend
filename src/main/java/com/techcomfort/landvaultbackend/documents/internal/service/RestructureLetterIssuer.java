package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.documents.dto.LetterheadImageDto;
import com.techcomfort.landvaultbackend.documents.dto.PrintedLetterheadDto;
import com.techcomfort.landvaultbackend.documents.internal.domain.OfferLetter;
import com.techcomfort.landvaultbackend.documents.internal.domain.RestructureLetter;
import com.techcomfort.landvaultbackend.documents.internal.render.DocumentFormats;
import com.techcomfort.landvaultbackend.documents.internal.render.RestructureClauses;
import com.techcomfort.landvaultbackend.documents.internal.repository.OfferLetterRepository;
import com.techcomfort.landvaultbackend.documents.internal.repository.RestructureLetterRepository;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.inventory.PlotDocumentFacts;
import com.techcomfort.landvaultbackend.payments.RestructureLetterRequest;
import com.techcomfort.landvaultbackend.payments.RestructureLetters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 5c: issues the restructure letter (HT-RST-) with a developer's
 * proposal, inside that transaction (MANDATORY), so a proposal never exists
 * without the letter the buyer accepts. Drawn like the offer letter: the
 * developer's letterhead and signatory from the estate's approved setup (read
 * under the selling company's scope, {@link SellerLetterSetup}), HomeTrue's
 * wording. Payments' implementation of {@link RestructureLetters}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestructureLetterIssuer implements RestructureLetters {

    private final RestructureLetterRepository restructureLetters;
    private final OfferLetterRepository offers;
    private final SellerLetterSetup sellerSetup;
    private final IdentityApi identityApi;
    private final AuditApi auditApi;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LetterRef> issue(RestructureLetterRequest request) {
        Optional<RestructureLetter> existing = restructureLetters.findByRestructureId(request.restructureId());
        if (existing.isPresent()) {
            return existing.map(l -> new LetterRef(l.getId(), l.getNumber()));
        }
        Optional<SellerLetterSetup.Setup> found = sellerSetup.read(request.sellerTenantId(), request.buyerUserId(),
                request.estateId(), request.plotId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        LetterApprovalService.IssuableLetter setup = found.get().letter();
        PlotDocumentFacts plot = found.get().plot();
        Optional<OfferLetter> offer = offers.findByTransactionId(request.transactionId());

        String buyerName = identityApi.fullNameOf(request.buyerUserId())
                .orElseThrow(() -> new IllegalStateException("No buyer " + request.buyerUserId()));
        // The address as copied onto the purchase is on the offer; a later one is never printed.
        List<String> buyerLines = offer.map(o -> List.of(o.getBuyerLines())).orElseGet(() -> {
            List<String> lines = new ArrayList<>(List.of(buyerName));
            identityApi.phoneOf(request.buyerUserId()).ifPresent(lines::add);
            return lines;
        });

        Instant issuedAt = Instant.now();
        String location = LetterApprovalService.location(setup.facts());
        PrintedLetterheadDto p = setup.letterhead();
        RestructureLetter letter = restructureLetters.saveAndFlush(RestructureLetter.builder()
                .number(number(issuedAt, restructureLetters.nextNumber()))
                .verificationCode(VerificationCodes.generate())
                .restructureId(request.restructureId())
                .transactionId(request.transactionId())
                .offerLetterId(offer.map(OfferLetter::getId).orElse(null))
                .buyerUserId(request.buyerUserId())
                .sellerTenantId(request.sellerTenantId())
                .branchId(setup.facts().branchId())
                .estateId(request.estateId())
                .plotId(request.plotId())
                .issuedAt(issuedAt)
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
                .opening(RestructureClauses.opening(DocumentFormats.date(request.requestedAt()),
                        offer.map(OfferLetter::getNumber).orElse(null), AllocationLetterIssuer.plotPhrase(plot),
                        setup.facts().name(), location, p.companyName()))
                .clauses(RestructureClauses.clauses(request).toArray(String[]::new))
                .closing(RestructureClauses.closing(request.expiresOn()))
                .signOff(RestructureClauses.SIGN_OFF)
                .build());
        auditApi.record(AuditEntryRequest.of(null, "restructure_letter.issued", "restructure_letter",
                letter.getId(), request.sellerTenantId(), "Restructure letter " + letter.getNumber() + " issued for "
                        + plot.label() + ", " + setup.facts().name() + " (" + request.newInstalments().size()
                        + " new instalments; open until " + request.expiresOn() + ")."));
        log.info("Restructure letter {} issued for transaction {}", letter.getNumber(), request.transactionId());
        return Optional.of(new LetterRef(letter.getId(), letter.getNumber()));
    }

    /** {@code HT-RST-2026-000123}: the year it was issued, in Nigeria, and a number that never repeats. */
    static String number(Instant issuedAt, long sequence) {
        return "HT-RST-" + issuedAt.atZone(DocumentFormats.NIGERIA).getYear() + "-" + String.format("%06d", sequence);
    }

    private static UUID fileId(LetterheadImageDto image) {
        return image == null ? null : image.fileId();
    }
}
