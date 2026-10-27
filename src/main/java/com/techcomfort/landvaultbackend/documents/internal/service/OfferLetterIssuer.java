package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.NewPurchase;
import com.techcomfort.landvaultbackend.checkout.OfferLetters;
import com.techcomfort.landvaultbackend.checkout.OfferSummary;
import com.techcomfort.landvaultbackend.documents.dto.LetterVersionsDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterheadImageDto;
import com.techcomfort.landvaultbackend.documents.dto.PrintedLetterheadDto;
import com.techcomfort.landvaultbackend.documents.internal.domain.OfferLetter;
import com.techcomfort.landvaultbackend.documents.internal.domain.OfferLetterAcceptance;
import com.techcomfort.landvaultbackend.documents.internal.render.DocumentFormats;
import com.techcomfort.landvaultbackend.documents.internal.render.LetterClauses;
import com.techcomfort.landvaultbackend.documents.internal.render.OfferClauses;
import com.techcomfort.landvaultbackend.documents.internal.repository.OfferLetterAcceptanceRepository;
import com.techcomfort.landvaultbackend.documents.internal.repository.OfferLetterRepository;
import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.inventory.PlotDocumentFacts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * OL: issues the offer letter when a buyer opens a purchase, inside that
 * transaction (MANDATORY), so a purchase never exists without the terms it was
 * offered on. Drawn from the estate's approved letter setup — the developer's
 * letterhead and signatory, HomeTrue's wording — read under the selling
 * company's scope ({@link SellerLetterSetup}). Checkout's implementation of
 * {@link OfferLetters}. See AGENTS.md, "Documents: the offer letter".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfferLetterIssuer implements OfferLetters {

    private final OfferLetterRepository offers;
    private final OfferLetterAcceptanceRepository acceptances;
    private final SellerLetterSetup sellerSetup;
    private final IdentityApi identityApi;
    private final AuditApi auditApi;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OfferSummary> issue(NewPurchase purchase) {
        Optional<OfferLetter> existing = offers.findByTransactionId(purchase.transactionId());
        if (existing.isPresent()) {
            return existing.map(o -> summary(o, null));
        }
        Optional<SellerLetterSetup.Setup> found = sellerSetup.read(purchase.sellerTenantId(), purchase.buyerUserId(),
                purchase.estateId(), purchase.plotId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        LetterApprovalService.IssuableLetter setup = found.get().letter();
        PlotDocumentFacts plot = found.get().plot();

        String buyerName = identityApi.fullNameOf(purchase.buyerUserId())
                .orElseThrow(() -> new IllegalStateException("No buyer " + purchase.buyerUserId()));
        List<String> buyerLines = new ArrayList<>(List.of(buyerName));
        if (purchase.buyerAddress() != null) {
            buyerLines.addAll(purchase.buyerAddress().printedLines());
        }
        identityApi.phoneOf(purchase.buyerUserId()).ifPresent(buyerLines::add);

        Instant issuedAt = Instant.now();
        String location = LetterApprovalService.location(setup.facts());
        PrintedLetterheadDto p = setup.letterhead();
        LetterVersionsDto v = setup.versions();
        OfferLetter offer = offers.saveAndFlush(OfferLetter.builder()
                .number(number(issuedAt, offers.nextNumber()))
                .verificationCode(VerificationCodes.generate())
                .transactionId(purchase.transactionId())
                .buyerUserId(purchase.buyerUserId())
                .sellerTenantId(purchase.sellerTenantId())
                .branchId(setup.facts().branchId())
                .estateId(purchase.estateId())
                .plotId(purchase.plotId())
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
                .opening(OfferClauses.opening(DocumentFormats.date(purchase.reservedAt()),
                        AllocationLetterIssuer.plotPhrase(plot), setup.facts().name(), location, p.companyName()))
                .priceClauses(priceClauses(purchase, setup, p.companyName()).toArray(String[]::new))
                // The same conditions, in the same order, as the allocation letter
                // would print today — which copies these when the sale is allocated.
                .clauses(LetterClauses.of(setup.template().standardClauses(),
                        List.of(setup.terms().getClauses()), setup.facts()).toArray(String[]::new))
                .closing(OfferClauses.CLOSING)
                .signOff(OfferClauses.SIGN_OFF)
                .basePrice(purchase.basePrice())
                .cornerPremiumPct(purchase.cornerPremiumPct())
                .totalPrice(purchase.totalPrice())
                .currency(purchase.currency().name())
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
        auditApi.record(AuditEntryRequest.of(purchase.buyerUserId(), "offer_letter.issued", "offer_letter",
                offer.getId(), purchase.sellerTenantId(), "Offer letter " + offer.getNumber() + " issued for "
                        + plot.label() + ", " + setup.facts().name() + " (conditions v" + v.terms() + ", fees "
                        + (v.fees() == null ? "none" : "v" + v.fees()) + ")."));
        log.info("Offer letter {} issued for transaction {}", offer.getNumber(), purchase.transactionId());
        return Optional.of(summary(offer, null));
    }

    /** Outright, instalment or flexible: HomeTrue's fixed wording for the plan the purchase was opened on. */
    private static List<String> priceClauses(NewPurchase purchase, LetterApprovalService.IssuableLetter setup,
                                             String company) {
        if (purchase.schedule() != null) {
            return OfferClauses.plan(purchase.schedule(), purchase.basePrice(), purchase.cornerPremiumPct(),
                    purchase.totalPrice(), purchase.currency(), setup.facts());
        }
        if (purchase.flexible() != null) {
            return OfferClauses.flexible(purchase.flexible(), purchase.basePrice(), purchase.cornerPremiumPct(),
                    purchase.totalPrice(), purchase.currency(), company, setup.facts());
        }
        return OfferClauses.price(purchase.basePrice(), purchase.cornerPremiumPct(), purchase.totalPrice(),
                purchase.currency(), setup.facts().fees());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, OfferSummary> forTransactions(Collection<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return Map.of();
        }
        List<OfferLetter> found = offers.findByTransactionIdIn(transactionIds);
        Map<UUID, OfferLetterAcceptance> accepted = acceptances
                .findByOfferLetterIdIn(found.stream().map(OfferLetter::getId).toList()).stream()
                .collect(Collectors.toMap(OfferLetterAcceptance::getOfferLetterId, Function.identity()));
        return found.stream().collect(Collectors.toMap(OfferLetter::getTransactionId,
                o -> summary(o, accepted.get(o.getId()))));
    }

    /** {@code HT-OFR-2026-000123}: the year it was issued, in Nigeria, and a number that never repeats. */
    static String number(Instant issuedAt, long sequence) {
        return "HT-OFR-" + issuedAt.atZone(DocumentFormats.NIGERIA).getYear() + "-" + String.format("%06d", sequence);
    }

    private static OfferSummary summary(OfferLetter offer, OfferLetterAcceptance acceptance) {
        return new OfferSummary(offer.getId(), offer.getNumber(), offer.getIssuedAt(),
                acceptance == null ? null : acceptance.getAcceptedAt());
    }

    private static UUID fileId(LetterheadImageDto image) {
        return image == null ? null : image.fileId();
    }
}
