package com.techcomfort.landvaultbackend.inventory.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.TitleStatus;
import com.techcomfort.landvaultbackend.inventory.dto.CreateVerificationCheckRequest;
import com.techcomfort.landvaultbackend.inventory.dto.RejectTitleRequest;
import com.techcomfort.landvaultbackend.inventory.dto.TitleReviewDto;
import com.techcomfort.landvaultbackend.inventory.dto.VerificationCheckDto;
import com.techcomfort.landvaultbackend.inventory.dto.VerifyTitleRequest;
import com.techcomfort.landvaultbackend.inventory.internal.service.AdminTitleReviewService;
import com.techcomfort.landvaultbackend.storage.DownloadLink;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * HomeTrue's review of declared titles, and an estate's other due-diligence
 * checks. {@code admin.titles.review}. See AGENTS.md, "Estate titles".
 */
@RestController
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_ADMIN_TITLES)
public class AdminTitleReviewController {

    private final AdminTitleReviewService service;

    @Operation(summary = "Titles by review status",
            description = "Requires `admin.titles.review`. `status` defaults to `pending`. Pending oldest first "
                    + "(the queue); verified and rejected newest first.")
    @GetMapping("/api/admin/title-reviews")
    @PreAuthorize("hasAuthority('admin.titles.review')")
    public ResponseEntity<List<TitleReviewDto>> list(@RequestParam(defaultValue = "pending") String status) {
        return ResponseEntity.ok(service.list(TitleStatus.fromValue(status)));
    }

    @Operation(summary = "A link to one of the title's documents",
            description = "Requires `admin.titles.review`. Only the documents of the submission under review. "
                    + "Expires in 5 minutes.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "`TITLE_NOT_FOUND`, `FILE_NOT_FOUND`", content = @Content())
    })
    @GetMapping("/api/admin/title-reviews/{titleId}/documents/{fileId}/download")
    @PreAuthorize("hasAuthority('admin.titles.review')")
    public ResponseEntity<DownloadLink> download(@PathVariable UUID titleId, @PathVariable UUID fileId) {
        return ResponseEntity.ok(service.downloadLink(titleId, fileId));
    }

    @Operation(summary = "Verify a title",
            description = """
                    Requires `admin.titles.review`. `verificationSource`: `manual_review` (you compared the \
                    documents) or `registry_search` (an official search at the land registry, report in hand). \
                    `agis`, `qoreid` and `cac_registry` are reserved for integrations that don't exist yet \
                    (`VERIFICATION_SOURCE_NOT_ALLOWED`).

                    Send the `submittedAt` you reviewed: if the developer resubmitted since, 409 \
                    `TITLE_CHANGED`. The estate's title check becomes `verified` with the same source, and the \
                    marketplace shows the title as verified by HomeTrue.""")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "`VERIFICATION_SOURCE_NOT_ALLOWED`, `VALIDATION_ERROR`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`TITLE_NOT_FOUND`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`TITLE_NOT_PENDING`, `TITLE_CHANGED`, `TITLE_DOCUMENTS_MISSING`", content = @Content())
    })
    @PostMapping("/api/admin/title-reviews/{titleId}/verify")
    @PreAuthorize("hasAuthority('admin.titles.review')")
    public ResponseEntity<TitleReviewDto> verify(@PathVariable UUID titleId,
                                                 @Valid @RequestBody VerifyTitleRequest request) {
        return ResponseEntity.ok(service.verify(titleId, request));
    }

    @Operation(summary = "Reject a title",
            description = """
                    Requires `admin.titles.review`. `note` is required — **the developer sees it**, so say what \
                    to fix. Send the `submittedAt` you reviewed (409 `TITLE_CHANGED` otherwise). The estate's \
                    title check becomes `failed`. The developer can resubmit.""")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "`VALIDATION_ERROR` (no note)", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`TITLE_NOT_FOUND`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`TITLE_NOT_PENDING`, `TITLE_CHANGED`", content = @Content())
    })
    @PostMapping("/api/admin/title-reviews/{titleId}/reject")
    @PreAuthorize("hasAuthority('admin.titles.review')")
    public ResponseEntity<TitleReviewDto> reject(@PathVariable UUID titleId,
                                                 @Valid @RequestBody RejectTitleRequest request) {
        return ResponseEntity.ok(service.reject(titleId, request));
    }

    @Operation(summary = "Record an estate's due-diligence check",
            description = """
                    Requires `admin.titles.review`. AGIS registration or encroachment status — the title check \
                    follows the title's own review (`TITLE_CHECK_FOLLOWS_REVIEW`). Developers can no longer \
                    record checks on their own estates.

                    **`not_checked` is the default and must never render as positive.** A `verified` check \
                    needs a `verificationSource`, and a person may only claim `manual_review` or \
                    `registry_search` (`VERIFICATION_SOURCE_NOT_ALLOWED`). Recording the same type again \
                    updates it.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The recorded check"),
            @ApiResponse(responseCode = "400", description = "A verified check with no source, `TITLE_CHECK_FOLLOWS_REVIEW`, `VERIFICATION_SOURCE_NOT_ALLOWED`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`", content = @Content())
    })
    @PostMapping("/api/admin/estates/{id}/verification-checks")
    @PreAuthorize("hasAuthority('admin.titles.review')")
    public ResponseEntity<VerificationCheckDto> recordVerificationCheck(
            @PathVariable UUID id, @Valid @RequestBody CreateVerificationCheckRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.recordVerificationCheck(id, request));
    }
}
