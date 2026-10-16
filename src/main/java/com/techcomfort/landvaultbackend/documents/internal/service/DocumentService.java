package com.techcomfort.landvaultbackend.documents.internal.service;

import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.documents.dto.DocumentDto;
import com.techcomfort.landvaultbackend.documents.dto.DocumentVerificationDto;
import com.techcomfort.landvaultbackend.documents.internal.config.DocumentsProperties;
import com.techcomfort.landvaultbackend.documents.internal.domain.Receipt;
import com.techcomfort.landvaultbackend.documents.internal.exceptions.DocumentException;
import com.techcomfort.landvaultbackend.documents.internal.render.DocumentFormats;
import com.techcomfort.landvaultbackend.documents.internal.render.PdfImages;
import com.techcomfort.landvaultbackend.documents.internal.render.ReceiptContent;
import com.techcomfort.landvaultbackend.documents.internal.render.ReceiptRenderer;
import com.techcomfort.landvaultbackend.documents.internal.repository.ReceiptRepository;
import com.techcomfort.landvaultbackend.payments.PaymentsApi;
import com.techcomfort.landvaultbackend.portfolio.PortfolioApi;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A buyer's documents (receipts today), their PDF and JPEG, and the public
 * verify lookup. See AGENTS.md, "Documents".
 */
@Service
@RequiredArgsConstructor
public class DocumentService {

    public static final String RECEIPT = "receipt";
    private static final String VALID = "valid";
    private static final String REFUNDED = "refunded";

    private final ReceiptRepository receipts;
    private final ReceiptRenderer renderer;
    private final DocumentsProperties properties;
    private final PaymentsApi paymentsApi;
    private final PortfolioApi portfolioApi;

    @Transactional(readOnly = true)
    public PageResponse<DocumentDto> list(UUID buyer, Pageable pageable) {
        Page<Receipt> page = receipts.findByBuyerUserIdOrderByIssuedAtDesc(buyer, pageable);
        Map<UUID, Instant> refunded = refundedAt(page.getContent());
        return PageResponses.from(page, r -> toDto(r, refunded.get(r.getPaymentId())));
    }

    /** Documents for one portfolio entry — by owned-plot id or transaction id, the buyer's own only. */
    @Transactional(readOnly = true)
    public List<DocumentDto> forPortfolioPlot(UUID buyer, UUID ownedPlotOrTransactionId) {
        UUID transactionId = portfolioApi.transactionIdOf(buyer, ownedPlotOrTransactionId)
                .orElseThrow(DocumentException.PlotNotFound::new);
        List<Receipt> found = receipts.findByBuyerUserIdAndTransactionIdOrderByIssuedAtDesc(buyer, transactionId);
        Map<UUID, Instant> refunded = refundedAt(found);
        return found.stream().map(r -> toDto(r, refunded.get(r.getPaymentId()))).toList();
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

    /** DV-2: public. Says the document is genuine and what it is about — no amount, no personal details. */
    @Transactional(readOnly = true)
    public DocumentVerificationDto verify(String code) {
        String normalised = VerificationCodes.normalise(code);
        if (normalised == null) {
            throw new DocumentException.NotAHomeTrueDocument();
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
