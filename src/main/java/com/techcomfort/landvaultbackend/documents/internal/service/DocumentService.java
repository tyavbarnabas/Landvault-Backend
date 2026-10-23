package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.checkout.CheckoutApi;
import com.techcomfort.landvaultbackend.checkout.PayableTransaction;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.documents.dto.DocumentDto;
import com.techcomfort.landvaultbackend.documents.dto.DocumentVerificationDto;
import com.techcomfort.landvaultbackend.documents.internal.config.DocumentsProperties;
import com.techcomfort.landvaultbackend.documents.internal.domain.AllocationLetter;
import com.techcomfort.landvaultbackend.documents.internal.domain.OfferLetter;
import com.techcomfort.landvaultbackend.documents.internal.domain.OfferLetterAcceptance;
import com.techcomfort.landvaultbackend.documents.internal.domain.Receipt;
import com.techcomfort.landvaultbackend.documents.internal.enums.LetterKind;
import com.techcomfort.landvaultbackend.documents.internal.exceptions.DocumentException;
import com.techcomfort.landvaultbackend.documents.internal.render.AllocationLetterContent;
import com.techcomfort.landvaultbackend.documents.internal.render.AllocationLetterRenderer;
import com.techcomfort.landvaultbackend.documents.internal.render.DocumentFormats;
import com.techcomfort.landvaultbackend.documents.internal.render.PdfImages;
import com.techcomfort.landvaultbackend.documents.internal.render.ReceiptContent;
import com.techcomfort.landvaultbackend.documents.internal.render.ReceiptRenderer;
import com.techcomfort.landvaultbackend.documents.internal.repository.AllocationLetterRepository;
import com.techcomfort.landvaultbackend.documents.internal.repository.OfferLetterAcceptanceRepository;
import com.techcomfort.landvaultbackend.documents.internal.repository.OfferLetterRepository;
import com.techcomfort.landvaultbackend.documents.internal.repository.ReceiptRepository;
import com.techcomfort.landvaultbackend.payments.PaymentsApi;
import com.techcomfort.landvaultbackend.portfolio.PortfolioApi;
import com.techcomfort.landvaultbackend.storage.StorageApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A buyer's documents (receipts, offer and allocation letters), their PDF and
 * JPEG, accepting an offer, and the public verify lookup. See AGENTS.md,
 * "Documents".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    public static final String RECEIPT = "receipt";
    public static final String ALLOCATION_LETTER = "allocation_letter";
    public static final String OFFER_LETTER = "offer_letter";
    public static final String PROVISIONAL_ALLOCATION_LETTER = "provisional_allocation_letter";
    private static final String ISSUED = "issued";
    private static final String ACCEPTED = "accepted";
    private static final String LAPSED = "lapsed";
    private static final int USER_AGENT_MAX = 255;
    private static final String VALID = "valid";
    private static final String REFUNDED = "refunded";
    private static final String WITHDRAWN = "withdrawn";

    private final ReceiptRepository receipts;
    private final AllocationLetterRepository letters;
    private final ReceiptRenderer renderer;
    private final AllocationLetterRenderer letterRenderer;
    private final StorageApi storage;
    private final DocumentsProperties properties;
    private final PaymentsApi paymentsApi;
    private final PortfolioApi portfolioApi;
    private final OfferLetterRepository offers;
    private final OfferLetterAcceptanceRepository acceptances;
    private final CheckoutApi checkoutApi;
    private final AuditApi auditApi;

    /**
     * Receipts and letters together, newest first. Paged in memory: a buyer
     * has a handful of documents per plot, never thousands.
     */
    @Transactional(readOnly = true)
    public PageResponse<DocumentDto> list(UUID buyer, Pageable pageable) {
        List<Receipt> found = receipts.findAllByBuyerUserIdOrderByIssuedAtDesc(buyer);
        Map<UUID, Instant> refunded = refundedAt(found);
        List<DocumentDto> all = new ArrayList<>(found.stream().map(r -> toDto(r, refunded.get(r.getPaymentId()))).toList());
        letters.findByBuyerUserIdOrderByIssuedAtDesc(buyer).forEach(l -> all.add(toDto(l)));
        all.addAll(offerDtos(offers.findByBuyerUserIdOrderByIssuedAtDesc(buyer)));
        all.sort(Comparator.comparing(DocumentDto::issuedAt).reversed());
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return PageResponses.from(new PageImpl<>(all.subList(from, to), pageable, all.size()));
    }

    /** Documents for one portfolio entry — by owned-plot id or transaction id, the buyer's own only. */
    @Transactional(readOnly = true)
    public List<DocumentDto> forPortfolioPlot(UUID buyer, UUID ownedPlotOrTransactionId) {
        UUID transactionId = portfolioApi.transactionIdOf(buyer, ownedPlotOrTransactionId)
                .orElseThrow(DocumentException.PlotNotFound::new);
        List<Receipt> found = receipts.findByBuyerUserIdAndTransactionIdOrderByIssuedAtDesc(buyer, transactionId);
        Map<UUID, Instant> refunded = refundedAt(found);
        List<DocumentDto> all = new ArrayList<>();
        letters.findByBuyerUserIdAndTransactionIdOrderByIssuedAtDesc(buyer, transactionId).forEach(l -> all.add(toDto(l)));
        found.forEach(r -> all.add(toDto(r, refunded.get(r.getPaymentId()))));
        offers.findByBuyerUserIdAndTransactionId(buyer, transactionId).ifPresent(o -> all.addAll(offerDtos(List.of(o))));
        return all;
    }

    @Transactional(readOnly = true)
    public DocumentDto receipt(UUID buyer, UUID receiptId) {
        Receipt receipt = own(buyer, receiptId);
        return toDto(receipt, refundedAt(List.of(receipt)).get(receipt.getPaymentId()));
    }

    @Transactional(readOnly = true)
    public RenderedDocument receiptPdf(UUID buyer, UUID receiptId) {
        Receipt receipt = own(buyer, receiptId);
        return new RenderedDocument(fileName(receipt, "pdf"), renderer.pdf(content(receipt)));
    }

    @Transactional(readOnly = true)
    public RenderedDocument receiptJpeg(UUID buyer, UUID receiptId) {
        Receipt receipt = own(buyer, receiptId);
        return new RenderedDocument(fileName(receipt, "jpg"),
                PdfImages.firstPageAsJpeg(renderer.pdf(content(receipt))));
    }

    @Transactional(readOnly = true)
    public DocumentDto allocationLetter(UUID buyer, UUID letterId) {
        return toDto(ownLetter(buyer, letterId));
    }

    @Transactional(readOnly = true)
    public RenderedDocument allocationLetterPdf(UUID buyer, UUID letterId) {
        AllocationLetter letter = ownLetter(buyer, letterId);
        return new RenderedDocument("HomeTrue-" + letter.getNumber() + ".pdf", letterRenderer.pdf(content(letter)));
    }

    /** Every page, stacked: a letter's second page must not vanish when it's shared as a picture. */
    @Transactional(readOnly = true)
    public RenderedDocument allocationLetterJpeg(UUID buyer, UUID letterId) {
        AllocationLetter letter = ownLetter(buyer, letterId);
        return new RenderedDocument("HomeTrue-" + letter.getNumber() + ".jpg",
                PdfImages.allPagesAsJpeg(letterRenderer.pdf(content(letter))));
    }

    @Transactional(readOnly = true)
    public DocumentDto offerLetter(UUID buyer, UUID offerId) {
        return offerDtos(List.of(ownOffer(buyer, offerId))).getFirst();
    }

    @Transactional(readOnly = true)
    public RenderedDocument offerLetterPdf(UUID buyer, UUID offerId) {
        OfferLetter offer = ownOffer(buyer, offerId);
        return new RenderedDocument("HomeTrue-" + offer.getNumber() + ".pdf", letterRenderer.pdf(content(offer)));
    }

    @Transactional(readOnly = true)
    public RenderedDocument offerLetterJpeg(UUID buyer, UUID offerId) {
        OfferLetter offer = ownOffer(buyer, offerId);
        return new RenderedDocument("HomeTrue-" + offer.getNumber() + ".jpg",
                PdfImages.allPagesAsJpeg(letterRenderer.pdf(content(offer))));
    }

    /**
     * OL: the buyer accepts the offer letter they name — their own, while its
     * purchase is still waiting for payment. Who and when come from the
     * session; accepting again changes nothing. Serialized per offer by an
     * advisory lock (the tables can't be updated, so there's no row to lock).
     */
    @Transactional
    public DocumentDto acceptOffer(UUID buyer, UUID offerId, String userAgent) {
        OfferLetter offer = ownOffer(buyer, offerId);
        acceptances.lockOffer(offer.getId());
        Optional<OfferLetterAcceptance> existing = acceptances.findByOfferLetterId(offer.getId());
        if (existing.isPresent()) {
            return offerDtos(List.of(offer)).getFirst();
        }
        PayableTransaction purchase = checkoutApi.transactionForBuyer(offer.getTransactionId(), buyer)
                .orElseThrow(DocumentException.DocumentNotFound::new);
        if (!purchase.isPendingPayment()) {
            throw new DocumentException.Conflict("OFFER_NOT_OPEN",
                    "This offer can no longer be accepted: the purchase is " + purchase.status() + ".");
        }
        OfferLetterAcceptance acceptance = acceptances.saveAndFlush(OfferLetterAcceptance.builder()
                .offerLetterId(offer.getId())
                .buyerUserId(buyer)
                .acceptedAt(Instant.now())
                .userAgent(userAgent == null || userAgent.length() <= USER_AGENT_MAX
                        ? userAgent : userAgent.substring(0, USER_AGENT_MAX))
                .build());
        auditApi.record(AuditEntryRequest.of(buyer, "offer_letter.accepted", "offer_letter", offer.getId(),
                offer.getSellerTenantId(), "Offer letter " + offer.getNumber() + " accepted (conditions v"
                        + offer.getTermsVersion() + ", fees " + version(offer.getFeesVersion()) + ", refund terms "
                        + version(offer.getRefundTermsVersion()) + ", default terms "
                        + version(offer.getDefaultTermsVersion()) + ")."));
        log.info("Offer letter {} accepted for transaction {}", offer.getNumber(), offer.getTransactionId());
        return toDto(offer, status(offer, acceptance), acceptance);
    }

    /** DV-2: public. Says the document is genuine and what it is about — no amount, no personal details. */
    @Transactional(readOnly = true)
    public DocumentVerificationDto verify(String code) {
        String normalised = VerificationCodes.normalise(code);
        if (normalised == null) {
            throw new DocumentException.NotAHomeTrueDocument();
        }
        Optional<OfferLetter> offer = offers.findByVerificationCode(normalised);
        if (offer.isPresent()) {
            OfferLetter o = offer.get();
            return new DocumentVerificationDto(status(o, acceptances.findByOfferLetterId(o.getId()).orElse(null)),
                    OFFER_LETTER, o.getNumber(), o.getIssuedAt(), "HomeTrue", o.getCompanyName(), o.getEstateName(),
                    o.getEstateLocation(), o.getPlotLabel(), initials(o.getBuyerName()), null);
        }
        Optional<AllocationLetter> letter = letters.findByVerificationCode(normalised);
        if (letter.isPresent()) {
            AllocationLetter l = letter.get();
            return new DocumentVerificationDto(status(l), letterType(l), l.getNumber(), l.getIssuedAt(), "HomeTrue",
                    l.getCompanyName(), l.getEstateName(), l.getEstateLocation(), l.getPlotLabel(),
                    initials(l.getBuyerName()), null);
        }
        Receipt receipt = receipts.findByVerificationCode(normalised)
                .orElseThrow(DocumentException.NotAHomeTrueDocument::new);
        Instant refundedAt = refundedAt(List.of(receipt)).get(receipt.getPaymentId());
        return new DocumentVerificationDto(refundedAt == null ? VALID : REFUNDED, RECEIPT, receipt.getNumber(),
                receipt.getIssuedAt(), "HomeTrue", receipt.getSellerName(), receipt.getEstateName(),
                receipt.getEstateLocation(), receipt.plotLabel(), initials(receipt.getBuyerName()), refundedAt);
    }

    // --- helpers ---

    private Receipt own(UUID buyer, UUID receiptId) {
        return receipts.findByIdAndBuyerUserId(receiptId, buyer).orElseThrow(DocumentException.DocumentNotFound::new);
    }

    private AllocationLetter ownLetter(UUID buyer, UUID letterId) {
        return letters.findByIdAndBuyerUserId(letterId, buyer).orElseThrow(DocumentException.DocumentNotFound::new);
    }

    private OfferLetter ownOffer(UUID buyer, UUID offerId) {
        return offers.findByIdAndBuyerUserId(offerId, buyer).orElseThrow(DocumentException.DocumentNotFound::new);
    }

    /** Offers with their acceptances, one query for all of them. */
    private List<DocumentDto> offerDtos(List<OfferLetter> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, OfferLetterAcceptance> accepted = acceptances
                .findByOfferLetterIdIn(found.stream().map(OfferLetter::getId).toList()).stream()
                .collect(Collectors.toMap(OfferLetterAcceptance::getOfferLetterId, Function.identity()));
        return found.stream().map(o -> {
            OfferLetterAcceptance a = accepted.get(o.getId());
            return toDto(o, status(o, a), a);
        }).toList();
    }

    /** Lapsed is never stored: the purchase was rejected or abandoned. Otherwise accepted or still issued. */
    private String status(OfferLetter offer, OfferLetterAcceptance acceptance) {
        String purchase = checkoutApi.transactionForBuyer(offer.getTransactionId(), offer.getBuyerUserId())
                .map(PayableTransaction::status).orElse(null);
        if ("rejected".equals(purchase) || "abandoned".equals(purchase)) {
            return LAPSED;
        }
        return acceptance == null ? ISSUED : ACCEPTED;
    }

    /**
     * Phase 5a, never stored: a provisional letter whose buyer withdrew from
     * the plan no longer holds the plot. A final letter is always valid.
     */
    private String status(AllocationLetter letter) {
        if (letter.getKind() != LetterKind.PROVISIONAL) {
            return VALID;
        }
        String purchase = checkoutApi.transactionForBuyer(letter.getTransactionId(), letter.getBuyerUserId())
                .map(PayableTransaction::status).orElse(null);
        return "withdrawn".equals(purchase) ? WITHDRAWN : VALID;
    }

    private DocumentDto toDto(OfferLetter o, String status, OfferLetterAcceptance acceptance) {
        String base = "/api/documents/offer-letters/" + o.getId();
        return new DocumentDto(o.getId(), OFFER_LETTER,
                "Offer letter " + o.getNumber() + " — " + o.getPlotLabel() + ", " + o.getEstateName(),
                o.getNumber(), LocalDate.ofInstant(o.getIssuedAt(), DocumentFormats.NIGERIA), o.getIssuedAt(), status,
                VerificationCodes.display(o.getVerificationCode()), properties.verifyUrlFor(o.getVerificationCode()),
                null, o.getPlotId(), o.getTransactionId(), null, null, base + "/pdf", base + "/jpeg",
                acceptance == null ? null : acceptance.getAcceptedAt());
    }

    /** The offer exactly as issued, price first; accepted ones say when. */
    private AllocationLetterContent content(OfferLetter o) {
        List<String> clauses = new ArrayList<>(List.of(o.getPriceClauses()));
        clauses.addAll(List.of(o.getClauses()));
        Optional<OfferLetterAcceptance> accepted = acceptances.findByOfferLetterId(o.getId());
        return new AllocationLetterContent(
                new AllocationLetterContent.Company(o.getCompanyName(), o.getCompanyRcNumber(), o.getCompanyAddress(),
                        o.getCompanyPhone(), o.getCompanyEmail(), o.getCompanyWebsite()),
                image(o.getLogoFileId()), image(o.getSignatureFileId()), image(o.getSealFileId()),
                o.getIssuedAt(),
                List.of(o.getBuyerLines()),
                o.getSalutation(),
                o.getEstateName(),
                o.getEstateLocation(),
                o.getOpening(),
                clauses,
                o.getClosing(),
                o.getSignOff(),
                o.getSignatoryName(),
                o.getSignatoryTitle(),
                List.of(),
                o.getNumber(),
                properties.verifyUrlFor(o.getVerificationCode()),
                o.getVerificationCode(),
                AllocationLetterContent.OFFER_TITLE,
                accepted.map(a -> "Accepted on " + DocumentFormats.dateTime(a.getAcceptedAt())).orElse(null));
    }

    private static String version(Integer v) {
        return v == null ? "none" : "v" + v;
    }

    private static String letterType(AllocationLetter l) {
        return l.getKind() == LetterKind.PROVISIONAL ? PROVISIONAL_ALLOCATION_LETTER : ALLOCATION_LETTER;
    }

    private Map<UUID, Instant> refundedAt(List<Receipt> found) {
        return paymentsApi.refundedAt(found.stream().map(Receipt::getPaymentId).toList());
    }

    private DocumentDto toDto(Receipt r, Instant refundedAt) {
        String base = "/api/documents/receipts/" + r.getId();
        return new DocumentDto(r.getId(), RECEIPT,
                "Receipt " + r.getNumber() + " — " + r.plotLabel() + ", " + r.getEstateName(), r.getNumber(),
                LocalDate.ofInstant(r.getIssuedAt(), DocumentFormats.NIGERIA), r.getIssuedAt(), VALID,
                VerificationCodes.display(r.getVerificationCode()), properties.verifyUrlFor(r.getVerificationCode()),
                null, r.getPlotId(), r.getTransactionId(), null, refundedAt, base + "/pdf", base + "/jpeg", null);
    }

    private DocumentDto toDto(AllocationLetter l) {
        String base = "/api/documents/allocation-letters/" + l.getId();
        return new DocumentDto(l.getId(), letterType(l),
                (l.getKind() == LetterKind.PROVISIONAL ? "Provisional allocation letter " : "Allocation letter ")
                        + l.getNumber() + " — " + l.getPlotLabel() + ", " + l.getEstateName(),
                l.getNumber(), LocalDate.ofInstant(l.getIssuedAt(), DocumentFormats.NIGERIA), l.getIssuedAt(), status(l),
                VerificationCodes.display(l.getVerificationCode()), properties.verifyUrlFor(l.getVerificationCode()),
                null, l.getPlotId(), l.getTransactionId(), null, null, base + "/pdf", base + "/jpeg", null);
    }

    /** The letter exactly as issued: every word from the stored copy, the images from the files it named. */
    private AllocationLetterContent content(AllocationLetter l) {
        return new AllocationLetterContent(
                new AllocationLetterContent.Company(l.getCompanyName(), l.getCompanyRcNumber(), l.getCompanyAddress(),
                        l.getCompanyPhone(), l.getCompanyEmail(), l.getCompanyWebsite()),
                image(l.getLogoFileId()), image(l.getSignatureFileId()), image(l.getSealFileId()),
                l.getIssuedAt(),
                List.of(l.getBuyerLines()),
                l.getSalutation(),
                l.getEstateName(),
                l.getEstateLocation(),
                l.getOpening(),
                List.of(l.getClauses()),
                l.getClosing(),
                l.getSignOff(),
                l.getSignatoryName(),
                l.getSignatoryTitle(),
                List.of(),
                l.getNumber(),
                properties.verifyUrlFor(l.getVerificationCode()),
                l.getVerificationCode(),
                l.getKind() == LetterKind.PROVISIONAL ? AllocationLetterContent.PROVISIONAL_TITLE
                        : AllocationLetterContent.ALLOCATION_TITLE,
                null);
    }

    private byte[] image(UUID fileId) {
        return fileId == null ? null : storage.read(fileId);
    }

    private ReceiptContent content(Receipt r) {
        return new ReceiptContent(r.getNumber(), r.getIssuedAt(), r.getBuyerName(), r.getSellerName(),
                r.getSellerRcNumber(), r.getEstateName(), r.getEstateLocation(), r.plotLabel(), r.getPlotSizeSqm(),
                r.isCorner(), r.getAmount(), r.getCurrency(), r.getChannel(), r.getLast4(), r.getPaymentReference(),
                r.getPaidAt(), r.getVerificationCode(), properties.verifyUrlFor(r.getVerificationCode()));
    }

    private static String fileName(Receipt r, String extension) {
        return "HomeTrue-" + r.getNumber() + "." + extension;
    }

    /** "Tyav Barnabas Sughter" → "T. B. S." — enough to recognise, not enough to identify. */
    static String initials(String name) {
        return Arrays.stream(name.trim().split("\\s+"))
                .filter(part -> !part.isEmpty())
                .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT) + ".")
                .collect(Collectors.joining(" "));
    }

    /** A drawn document and the name to save it under. */
    public record RenderedDocument(String fileName, byte[] bytes) {
    }
}
