package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.documents.dto.DocumentDto;
import com.techcomfort.landvaultbackend.documents.dto.DocumentVerificationDto;
import com.techcomfort.landvaultbackend.documents.internal.config.DocumentsProperties;
import com.techcomfort.landvaultbackend.documents.internal.domain.AllocationLetter;
import com.techcomfort.landvaultbackend.documents.internal.domain.Receipt;
import com.techcomfort.landvaultbackend.documents.internal.exceptions.DocumentException;
import com.techcomfort.landvaultbackend.documents.internal.render.AllocationLetterContent;
import com.techcomfort.landvaultbackend.documents.internal.render.AllocationLetterRenderer;
import com.techcomfort.landvaultbackend.documents.internal.render.DocumentFormats;
import com.techcomfort.landvaultbackend.documents.internal.render.PdfImages;
import com.techcomfort.landvaultbackend.documents.internal.render.ReceiptContent;
import com.techcomfort.landvaultbackend.documents.internal.render.ReceiptRenderer;
import com.techcomfort.landvaultbackend.documents.internal.repository.AllocationLetterRepository;
import com.techcomfort.landvaultbackend.documents.internal.repository.ReceiptRepository;
import com.techcomfort.landvaultbackend.payments.PaymentsApi;
import com.techcomfort.landvaultbackend.portfolio.PortfolioApi;
import com.techcomfort.landvaultbackend.storage.StorageApi;
import lombok.RequiredArgsConstructor;
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
import java.util.stream.Collectors;

/**
 * A buyer's documents (receipts and allocation letters), their PDF and JPEG,
 * and the public verify lookup. See AGENTS.md, "Documents".
 */
@Service
@RequiredArgsConstructor
public class DocumentService {

    public static final String RECEIPT = "receipt";
    public static final String ALLOCATION_LETTER = "allocation_letter";
    private static final String VALID = "valid";
    private static final String REFUNDED = "refunded";

    private final ReceiptRepository receipts;
    private final AllocationLetterRepository letters;
    private final ReceiptRenderer renderer;
    private final AllocationLetterRenderer letterRenderer;
    private final StorageApi storage;
    private final DocumentsProperties properties;
    private final PaymentsApi paymentsApi;
    private final PortfolioApi portfolioApi;

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
        letters.findByBuyerUserIdAndTransactionId(buyer, transactionId).ifPresent(l -> all.add(toDto(l)));
        found.forEach(r -> all.add(toDto(r, refunded.get(r.getPaymentId()))));
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

    /** DV-2: public. Says the document is genuine and what it is about — no amount, no personal details. */
    @Transactional(readOnly = true)
    public DocumentVerificationDto verify(String code) {
        String normalised = VerificationCodes.normalise(code);
        if (normalised == null) {
            throw new DocumentException.NotAHomeTrueDocument();
        }
        Optional<AllocationLetter> letter = letters.findByVerificationCode(normalised);
        if (letter.isPresent()) {
            AllocationLetter l = letter.get();
            return new DocumentVerificationDto(VALID, ALLOCATION_LETTER, l.getNumber(), l.getIssuedAt(), "HomeTrue",
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

    private Map<UUID, Instant> refundedAt(List<Receipt> found) {
        return paymentsApi.refundedAt(found.stream().map(Receipt::getPaymentId).toList());
    }

    private DocumentDto toDto(Receipt r, Instant refundedAt) {
        String base = "/api/documents/receipts/" + r.getId();
        return new DocumentDto(r.getId(), RECEIPT,
                "Receipt " + r.getNumber() + " — " + r.plotLabel() + ", " + r.getEstateName(), r.getNumber(),
                LocalDate.ofInstant(r.getIssuedAt(), DocumentFormats.NIGERIA), r.getIssuedAt(), VALID,
                VerificationCodes.display(r.getVerificationCode()), properties.verifyUrlFor(r.getVerificationCode()),
                null, r.getPlotId(), r.getTransactionId(), null, refundedAt, base + "/pdf", base + "/jpeg");
    }

    private DocumentDto toDto(AllocationLetter l) {
        String base = "/api/documents/allocation-letters/" + l.getId();
        return new DocumentDto(l.getId(), ALLOCATION_LETTER,
                "Allocation letter " + l.getNumber() + " — " + l.getPlotLabel() + ", " + l.getEstateName(),
                l.getNumber(), LocalDate.ofInstant(l.getIssuedAt(), DocumentFormats.NIGERIA), l.getIssuedAt(), VALID,
                VerificationCodes.display(l.getVerificationCode()), properties.verifyUrlFor(l.getVerificationCode()),
                null, l.getPlotId(), l.getTransactionId(), null, null, base + "/pdf", base + "/jpeg");
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
                l.getVerificationCode());
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
