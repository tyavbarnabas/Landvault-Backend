package com.techcomfort.landvaultbackend.documents.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.PageResponses;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.documents.dto.DocumentDto;
import com.techcomfort.landvaultbackend.documents.internal.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** A buyer's document vault. Read-only: documents are issued by events, never by a client. See AGENTS.md, "Documents". */
@RestController
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_DOCUMENTS)
public class DocumentController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentService service;

    @Operation(summary = "My documents",
            description = """
                    Requires `client.documents.view`. Every document HomeTrue has issued to the caller, newest \
                    first — receipts and allocation letters. Paged (`limit` default 20, max 100).

                    A receipt is issued the moment Paystack confirms a payment; an allocation letter the moment \
                    the developer's finance verifies the sale. Their PDF and JPEG are drawn on demand from the copy \
                    kept when they were issued, so they never change.""")
    @GetMapping("/api/documents")
    @PreAuthorize("hasAuthority('client.documents.view')")
    public ResponseEntity<PageResponse<DocumentDto>> list(@RequestParam(required = false) String cursor,
                                                         @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(service.list(currentUserId(), PageResponses.pageable(cursor,
                limit == null || limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE))));
    }

    @Operation(summary = "Documents for one of my plots",
            description = """
                    Requires `client.documents.view`. By portfolio id or transaction id — the same ids \
                    `/api/portfolio/plots/{id}` accepts. Another buyer's plot is a 404.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", description = "`PORTFOLIO_PLOT_NOT_FOUND`", content = @Content)})
    @GetMapping("/api/portfolio/plots/{id}/documents")
    @PreAuthorize("hasAuthority('client.documents.view')")
    public ResponseEntity<List<DocumentDto>> forPlot(@PathVariable UUID id) {
        return ResponseEntity.ok(service.forPortfolioPlot(currentUserId(), id));
    }

    @Operation(summary = "One of my receipts", description = "Requires `client.documents.view`. Own receipts only (404 otherwise).")
    @ApiResponses({
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", description = "`DOCUMENT_NOT_FOUND`", content = @Content)})
    @GetMapping("/api/documents/receipts/{id}")
    @PreAuthorize("hasAuthority('client.documents.view')")
    public ResponseEntity<DocumentDto> receipt(@PathVariable UUID id) {
        return ResponseEntity.ok(service.receipt(currentUserId(), id));
    }

    @Operation(summary = "Download a receipt as PDF",
            description = """
                    Requires `client.documents.view`. The official copy: A4, with a QR code anyone can scan to \
                    check it is genuine. Sent as an attachment named `HomeTrue-<number>.pdf` — fetch it as a \
                    blob to hand to the phone's share sheet.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE)),
            @ApiResponse(responseCode = "404", description = "`DOCUMENT_NOT_FOUND`", content = @Content)})
    @GetMapping(value = "/api/documents/receipts/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('client.documents.view')")
    public ResponseEntity<byte[]> receiptPdf(@PathVariable UUID id) {
        return file(service.receiptPdf(currentUserId(), id), MediaType.APPLICATION_PDF);
    }

    @Operation(summary = "Download a receipt as JPEG",
            description = """
                    Requires `client.documents.view`. The same page as the PDF, as an image (150 dpi), for apps \
                    that share pictures more easily than files.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.IMAGE_JPEG_VALUE)),
            @ApiResponse(responseCode = "404", description = "`DOCUMENT_NOT_FOUND`", content = @Content)})
    @GetMapping(value = "/api/documents/receipts/{id}/jpeg", produces = MediaType.IMAGE_JPEG_VALUE)
    @PreAuthorize("hasAuthority('client.documents.view')")
    public ResponseEntity<byte[]> receiptJpeg(@PathVariable UUID id) {
        return file(service.receiptJpeg(currentUserId(), id), MediaType.IMAGE_JPEG);
    }

    @Operation(summary = "One of my allocation letters",
            description = "Requires `client.documents.view`. Own letters only (404 otherwise).")
    @ApiResponses({
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", description = "`DOCUMENT_NOT_FOUND`", content = @Content)})
    @GetMapping("/api/documents/allocation-letters/{id}")
    @PreAuthorize("hasAuthority('client.documents.view')")
    public ResponseEntity<DocumentDto> allocationLetter(@PathVariable UUID id) {
        return ResponseEntity.ok(service.allocationLetter(currentUserId(), id));
    }

    @Operation(summary = "Download an allocation letter as PDF",
            description = """
                    Requires `client.documents.view`. The official copy: the developer's letterhead, wording and \
                    signature, HomeTrue's disclosure clauses, and a QR code anyone can scan to check it is genuine. \
                    Sent as an attachment named `HomeTrue-<number>.pdf`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE)),
            @ApiResponse(responseCode = "404", description = "`DOCUMENT_NOT_FOUND`", content = @Content)})
    @GetMapping(value = "/api/documents/allocation-letters/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('client.documents.view')")
    public ResponseEntity<byte[]> allocationLetterPdf(@PathVariable UUID id) {
        return file(service.allocationLetterPdf(currentUserId(), id), MediaType.APPLICATION_PDF);
    }

    @Operation(summary = "Download an allocation letter as JPEG",
            description = """
                    Requires `client.documents.view`. Every page of the letter stacked into one image (150 dpi), \
                    for apps that share pictures more easily than files.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.IMAGE_JPEG_VALUE)),
            @ApiResponse(responseCode = "404", description = "`DOCUMENT_NOT_FOUND`", content = @Content)})
    @GetMapping(value = "/api/documents/allocation-letters/{id}/jpeg", produces = MediaType.IMAGE_JPEG_VALUE)
    @PreAuthorize("hasAuthority('client.documents.view')")
    public ResponseEntity<byte[]> allocationLetterJpeg(@PathVariable UUID id) {
        return file(service.allocationLetterJpeg(currentUserId(), id), MediaType.IMAGE_JPEG);
    }

    private static ResponseEntity<byte[]> file(DocumentService.RenderedDocument document, MediaType type) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(document.fileName()).build().toString())
                // A document names a person: never kept by a shared cache.
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(document.bytes());
    }

    private static UUID currentUserId() {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        return scope.userId();
    }
}
