package com.techcomfort.landvaultbackend.documents.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.documents.dto.LetterheadDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterheadOverviewDto;
import com.techcomfort.landvaultbackend.documents.dto.RequestLetterheadImageUploadRequest;
import com.techcomfort.landvaultbackend.documents.dto.SetSignatoryRequest;
import com.techcomfort.landvaultbackend.documents.internal.service.LetterheadService;
import com.techcomfort.landvaultbackend.storage.DownloadLink;
import com.techcomfort.landvaultbackend.storage.UploadTicket;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Letterheads for generated letters (DS-1, DS-2): the company's, and any
 * branch's own. {@code {target}} is {@code company} or a branch id. See
 * AGENTS.md, "Documents: letterheads".
 */
@RestController
@RequestMapping("/api/portal/letterheads")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_DOCUMENTS)
public class LetterheadController {

    private static final String TARGET = "`company`, or the id of one of your company's branches.";
    private static final String MANAGE = """
            Requires `portal.documents.manage` (the Executive Director) and a company-wide view: a caller \
            narrowed to one branch is refused with `DOCUMENTS_REQUIRE_COMPANY_WIDE_SCOPE`. Raises the \
            letterhead's `version`; a change that changes nothing does nothing.""";

    private final LetterheadService service;

    @Operation(summary = "List letterheads",
            description = """
                    Requires `portal.documents.view`. The company's letterhead and each branch's the caller can \
                    see (a branch manager: their own). Each shows what it sets itself, what a letter would \
                    print (`printed`, with the company's details filled in where a branch sets nothing), and what \
                    is still `missing` before a letter can be issued: a signatory name, title and signature.""")
    @GetMapping
    @PreAuthorize("hasAuthority('portal.documents.view')")
    public LetterheadOverviewDto overview() {
        return service.overview();
    }

    @Operation(summary = "Set the signatory",
            description = MANAGE + """
                     \n\nThe person whose name and title are printed under the signature. On a branch, this \
                    makes the branch use its own signer — who then needs their own signature too; the company \
                    signer's signature is never printed beside another person's name.""")
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "`DOCUMENTS_REQUIRE_COMPANY_WIDE_SCOPE`", content = @Content),
            @ApiResponse(responseCode = "404", description = "`BRANCH_NOT_FOUND`", content = @Content)})
    @PutMapping("/{target}/signatory")
    @PreAuthorize("hasAuthority('portal.documents.manage')")
    public LetterheadDto setSignatory(@Parameter(description = TARGET) @PathVariable String target,
                                      @Valid @RequestBody SetSignatoryRequest request) {
        return service.setSignatory(target, request);
    }

    @Operation(summary = "Remove the signatory",
            description = MANAGE + "\n\nRemoves the name, title and signature together. A branch then uses the company's signer.")
    @DeleteMapping("/{target}/signatory")
    @PreAuthorize("hasAuthority('portal.documents.manage')")
    public LetterheadDto clearSignatory(@Parameter(description = TARGET) @PathVariable String target) {
        return service.clearSignatory(target);
    }

    @Operation(summary = "Start an image upload",
            description = MANAGE + """
                    \n\nA logo, signature or seal: PNG (best — keeps a transparent background) or JPEG, at most \
                    2 MB. PUT the file to `uploadUrl` with exactly the returned `headers`, then confirm it. The \
                    file goes to private storage and is never given a public address; location and camera details \
                    are removed when it is confirmed.""")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "`UNKNOWN_IMAGE_KIND`, `FILE_TYPE_NOT_ALLOWED`, `FILE_TOO_LARGE`",
                    content = @Content)})
    @PostMapping("/{target}/images/uploads")
    @PreAuthorize("hasAuthority('portal.documents.manage')")
    public ResponseEntity<UploadTicket> requestImageUpload(@Parameter(description = TARGET) @PathVariable String target,
                                                           @Valid @RequestBody RequestLetterheadImageUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.requestImageUpload(target, request));
    }

    @Operation(summary = "Confirm an image upload",
            description = MANAGE + """
                    \n\nThe file becomes this letterhead's logo, signature or seal (whichever it was uploaded as), \
                    replacing any earlier one. Letters already issued keep the image they were issued with.""")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "`UPLOAD_SIZE_MISMATCH`, `UPLOAD_CONTENT_MISMATCH`, `IMAGE_UNREADABLE`",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "`FILE_NOT_FOUND`, `BRANCH_NOT_FOUND`", content = @Content)})
    @PostMapping("/{target}/images/{fileId}/confirm")
    @PreAuthorize("hasAuthority('portal.documents.manage')")
    public LetterheadDto confirmImage(@Parameter(description = TARGET) @PathVariable String target,
                                      @PathVariable UUID fileId) {
        return service.confirmImage(target, fileId);
    }

    @Operation(summary = "Remove an image",
            description = MANAGE + "\n\n`logo`, `signature` or `seal`. A branch then uses the company's.")
    @DeleteMapping("/{target}/images/{kind}")
    @PreAuthorize("hasAuthority('portal.documents.manage')")
    public LetterheadDto removeImage(@Parameter(description = TARGET) @PathVariable String target,
                                     @Parameter(description = "`logo`, `signature` or `seal`.") @PathVariable String kind) {
        return service.removeImage(target, kind);
    }

    @Operation(summary = "Get a link to a letterhead image",
            description = """
                    Requires `portal.documents.view`. A link to one of the company's current letterhead images, \
                    valid for five minutes — for showing it on the settings screen. Never stored or shared.""")
    @ApiResponses({@ApiResponse(responseCode = "404", description = "`LETTERHEAD_FILE_NOT_FOUND`", content = @Content)})
    @GetMapping("/files/{fileId}/link")
    @PreAuthorize("hasAuthority('portal.documents.view')")
    public DownloadLink imageLink(@PathVariable UUID fileId) {
        return service.imageLink(fileId);
    }
}
