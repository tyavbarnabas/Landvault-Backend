package com.techcomfort.landvaultbackend.inventory.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.inventory.dto.EstateTitleDto;
import com.techcomfort.landvaultbackend.inventory.dto.RequestTitleDocumentUploadRequest;
import com.techcomfort.landvaultbackend.inventory.dto.SubmitEstateTitleRequest;
import com.techcomfort.landvaultbackend.inventory.dto.TitleDocumentDto;
import com.techcomfort.landvaultbackend.inventory.internal.service.EstateTitleService;
import com.techcomfort.landvaultbackend.storage.DownloadLink;
import com.techcomfort.landvaultbackend.storage.UploadTicket;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** An estate's title, as the developer declares it. See {@link EstateTitleService} and AGENTS.md, "Estate titles". */
@RestController
@RequestMapping("/api/portal/estates/{estateId}/title")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_ESTATES)
public class PortalEstateTitleController {

    private final EstateTitleService service;

    @Operation(summary = "The estate's title",
            description = """
                    Requires `portal.estates.view`. `status` is `pending` (declared, not yet checked), \
                    `verified` or `rejected`; only HomeTrue decides. A rejection carries `decisionNote`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`, `TITLE_NOT_RECORDED`", content = @Content())
    })
    @GetMapping
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<EstateTitleDto> get(@PathVariable UUID estateId) {
        return ResponseEntity.ok(service.get(estateId));
    }

    @Operation(summary = "Start a title document upload",
            description = """
                    Requires `portal.estates.manage`. Returns a link the browser uploads the file to directly \
                    (`PUT` with exactly the returned headers, within 10 minutes), then call \
                    `.../documents/{fileId}/confirm`.

                    - `kind`: `title_document` (the C of O, R of O, consent or Gazette page), `survey_plan` or `deed`.
                    - PDF, JPG or PNG (`FILE_TYPE_NOT_ALLOWED`); at most **10,485,760 bytes** (`FILE_TOO_LARGE`).
                    - **Private**: these carry owners' names and addresses. Never public; opened only through \
                    a 5-minute download link. Kept exactly as uploaded.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Upload permitted"),
            @ApiResponse(responseCode = "400", description = "`FILE_TYPE_NOT_ALLOWED`, `FILE_TOO_LARGE`, an unknown `kind`", content = @Content()),
            @ApiResponse(responseCode = "403", description = "`ESTATE_READ_ONLY_FOR_BRANCH`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`", content = @Content())
    })
    @PostMapping("/documents/uploads")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<UploadTicket> requestUpload(@PathVariable UUID estateId,
                                                      @Valid @RequestBody RequestTitleDocumentUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.requestUpload(estateId, request));
    }

    @Operation(summary = "Confirm an uploaded title document",
            description = """
                    Requires `portal.estates.manage`, and must be called by the person who uploaded it. Storage \
                    checks what arrived (size, and the type by its first bytes). The document is not part of \
                    the title until a submission (`PUT .../title`) names it. Confirming again returns the same \
                    document (200).""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Document confirmed"),
            @ApiResponse(responseCode = "200", description = "Already confirmed"),
            @ApiResponse(responseCode = "400", description = "`UPLOAD_SIZE_MISMATCH`, `UPLOAD_CONTENT_MISMATCH` — the file was deleted; upload again", content = @Content()),
            @ApiResponse(responseCode = "403", description = "`ESTATE_READ_ONLY_FOR_BRANCH`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`, `FILE_NOT_FOUND`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`UPLOAD_NOT_RECEIVED`, `UPLOAD_EXPIRED`", content = @Content())
    })
    @PostMapping("/documents/{fileId}/confirm")
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<TitleDocumentDto> confirm(@PathVariable UUID estateId, @PathVariable UUID fileId) {
        EstateTitleService.Confirmed result = service.confirm(estateId, fileId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.document());
    }

    @Operation(summary = "A link to one of the estate's title documents",
            description = "Requires `portal.estates.view`. Expires in 5 minutes; never store or share it.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`, `FILE_NOT_FOUND`", content = @Content())
    })
    @GetMapping("/documents/{fileId}/download")
    @PreAuthorize("hasAuthority('portal.estates.view')")
    public ResponseEntity<DownloadLink> download(@PathVariable UUID estateId, @PathVariable UUID fileId) {
        return ResponseEntity.ok(service.downloadLink(estateId, fileId));
    }

    @Operation(summary = "Declare the estate's title (create or replace)",
            description = """
                    Requires `portal.estates.manage`. Always comes back `pending`: **HomeTrue verifies, the \
                    developer only declares.** Replacing a title — even a verified one — clears the decision, \
                    because the verification was of the documents shown then; buyers see it as declared until \
                    HomeTrue checks the new one.

                    Required: `titleType`, `titleNumber` (at most 100), `titleDocumentFileId`, \
                    `surveyPlanFileId`. Optional: `issuedDate` (not in the future), `deedFileId`. Each file \
                    must be a confirmed title document of **this** estate, of the matching kind.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The title, pending review"),
            @ApiResponse(responseCode = "400", description = "`TITLE_DOCUMENT_REQUIRED`, `SURVEY_PLAN_REQUIRED`, `TITLE_DOCUMENT_NOT_FOUND`, `ISSUED_DATE_IN_FUTURE`, `INVALID_ISSUED_DATE`, `VALIDATION_ERROR`", content = @Content()),
            @ApiResponse(responseCode = "403", description = "`ESTATE_READ_ONLY_FOR_BRANCH`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`", content = @Content())
    })
    @PutMapping
    @PreAuthorize("hasAuthority('portal.estates.manage')")
    public ResponseEntity<EstateTitleDto> submit(@PathVariable UUID estateId,
                                                 @Valid @RequestBody SubmitEstateTitleRequest request) {
        return ResponseEntity.ok(service.submit(estateId, request));
    }
}
