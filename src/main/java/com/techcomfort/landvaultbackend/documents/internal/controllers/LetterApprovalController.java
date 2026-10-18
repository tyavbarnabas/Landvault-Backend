package com.techcomfort.landvaultbackend.documents.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.documents.dto.ApproveLetterRequest;
import com.techcomfort.landvaultbackend.documents.dto.LetterApprovalDto;
import com.techcomfort.landvaultbackend.documents.internal.service.LetterApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** DS-5: preview an estate's allocation letter, and approve it. See AGENTS.md, "Documents: previewing and approving the letter". */
@RestController
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_DOCUMENTS)
public class LetterApprovalController {

    private static final String PREVIEW_DESCRIPTION = """
            Requires `portal.documents.view`. The allocation letter a buyer of this estate would receive, drawn from \
            the letterhead it prints under, the HomeTrue-approved conditions and the cost disclosure, with \
            placeholders for the buyer and plot. Stamped `SAMPLE — NOT VALID` on every page and carries no \
            verification code. `terms=latest` shows the newest version of the conditions instead (marked as not \
            yet approved by HomeTrue), as does an estate with none approved yet.""";

    private final LetterApprovalService service;

    @Operation(summary = "Get whether an estate's allocation letter is approved",
            description = """
                    Requires `portal.documents.view`. `blockers` says what stops approval now; \
                    `changedSinceApproval` what withdrew an earlier approval. Send `currentVersions` back to approve.""")
    @ApiResponses({@ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`", content = @Content)})
    @GetMapping("/api/portal/estates/{estateId}/letter-approval")
    @PreAuthorize("hasAuthority('portal.documents.view')")
    public LetterApprovalDto status(@PathVariable UUID estateId) {
        return service.status(estateId);
    }

    @Operation(summary = "Preview an estate's allocation letter as PDF", description = PREVIEW_DESCRIPTION)
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE)),
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`", content = @Content)})
    @GetMapping(value = "/api/portal/estates/{estateId}/letter-preview/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('portal.documents.view')")
    public ResponseEntity<byte[]> previewPdf(@PathVariable UUID estateId,
                                             @Parameter(description = "`approved` (default) or `latest`")
                                             @RequestParam(required = false) String terms) {
        return file(service.preview(estateId, latest(terms), false), MediaType.APPLICATION_PDF);
    }

    @Operation(summary = "Preview an estate's allocation letter as JPEG",
            description = PREVIEW_DESCRIPTION + " Every page, one below the other, in one image.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.IMAGE_JPEG_VALUE)),
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`", content = @Content)})
    @GetMapping(value = "/api/portal/estates/{estateId}/letter-preview/jpeg", produces = MediaType.IMAGE_JPEG_VALUE)
    @PreAuthorize("hasAuthority('portal.documents.view')")
    public ResponseEntity<byte[]> previewJpeg(@PathVariable UUID estateId,
                                              @Parameter(description = "`approved` (default) or `latest`")
                                              @RequestParam(required = false) String terms) {
        return file(service.preview(estateId, latest(terms), true), MediaType.IMAGE_JPEG);
    }

    @Operation(summary = "Approve an estate's allocation letter",
            description = """
                    Requires `portal.documents.manage` (the Executive Director) and a company-wide view. Send the \
                    `currentVersions` you previewed against. The approval stands until any of them changes — the \
                    conditions, the letterhead, signatory, signature or seal, or the cost disclosure — and then \
                    the letter must be approved again.""")
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "`DOCUMENTS_REQUIRE_COMPANY_WIDE_SCOPE`", content = @Content),
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`", content = @Content),
            @ApiResponse(responseCode = "409", description = """
                    `LETTER_NOT_READY`: something it needs is missing (see `blockers`); `LETTER_SETUP_CHANGED`: \
                    something changed since the preview — preview again.""", content = @Content)})
    @PostMapping("/api/portal/estates/{estateId}/letter-approval")
    @PreAuthorize("hasAuthority('portal.documents.manage')")
    public LetterApprovalDto approve(@PathVariable UUID estateId, @Valid @RequestBody ApproveLetterRequest request) {
        return service.approve(estateId, request);
    }

    @Operation(summary = "Withdraw an estate's allocation letter approval",
            description = "Requires `portal.documents.manage` and a company-wide view. A no-op when it isn't approved.")
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "`DOCUMENTS_REQUIRE_COMPANY_WIDE_SCOPE`", content = @Content),
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`", content = @Content)})
    @PostMapping("/api/portal/estates/{estateId}/letter-approval/withdraw")
    @PreAuthorize("hasAuthority('portal.documents.manage')")
    public LetterApprovalDto withdraw(@PathVariable UUID estateId) {
        return service.withdraw(estateId);
    }

    private static boolean latest(String terms) {
        return "latest".equalsIgnoreCase(terms == null ? "" : terms.strip());
    }

    /**
     * An attachment, like receipts: with {@code inline}, Swagger UI reads the bytes as text, and its download
     * then saves that damaged copy (found in the live walkthrough). The frontend fetches it as a blob either way.
     */
    private static ResponseEntity<byte[]> file(LetterApprovalService.Sample sample, MediaType type) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(sample.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(sample.bytes());
    }
}
