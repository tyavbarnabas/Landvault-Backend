package com.techcomfort.landvaultbackend.documents.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.documents.dto.ApproveLetterTermsRequest;
import com.techcomfort.landvaultbackend.documents.dto.LetterTermsReviewDto;
import com.techcomfort.landvaultbackend.documents.dto.RejectLetterTermsRequest;
import com.techcomfort.landvaultbackend.documents.internal.service.LetterTermsReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** HomeTrue's review of developers' allocation conditions. See AGENTS.md, "Documents: HomeTrue reviews the conditions". */
@RestController
@RequestMapping("/api/admin/letter-terms-reviews")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_ADMIN_DOCUMENTS)
public class LetterTermsReviewController {

    private final LetterTermsReviewService service;

    @Operation(summary = "List allocation conditions by review status",
            description = """
                    Requires `admin.documents.review`. `pending` (the default) is the queue — each estate's newest \
                    version not yet decided, oldest first. `approved` and `rejected` list decisions, newest first.""")
    @ApiResponses({@ApiResponse(responseCode = "400", description = "`INVALID_PARAMETER`", content = @Content)})
    @GetMapping
    @PreAuthorize("hasAuthority('admin.documents.review')")
    public List<LetterTermsReviewDto> list(@RequestParam(required = false) String status) {
        return service.list(status);
    }

    @Operation(summary = "Get one version for review", description = "Requires `admin.documents.review`.")
    @ApiResponses({@ApiResponse(responseCode = "404", description = "`LETTER_TERMS_NOT_FOUND`", content = @Content)})
    @GetMapping("/{termsId}")
    @PreAuthorize("hasAuthority('admin.documents.review')")
    public LetterTermsReviewDto get(@PathVariable UUID termsId) {
        return service.get(termsId);
    }

    @Operation(summary = "Approve a version of an estate's allocation conditions",
            description = """
                    Requires `admin.documents.review`. Letters for the estate then use this version. Check the \
                    clauses for anything the automatic check can't catch — a sum written another way, a payment \
                    instruction, an account — since every figure on a letter must come from the cost disclosure \
                    and buyers pay only through HomeTrue.""")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "`LETTER_TERMS_NOT_FOUND`", content = @Content),
            @ApiResponse(responseCode = "409", description = """
                    `LETTER_TERMS_ALREADY_DECIDED`; `LETTER_TERMS_SUPERSEDED`: the developer saved a newer \
                    version — review that one.""", content = @Content)})
    @PostMapping("/{termsId}/approve")
    @PreAuthorize("hasAuthority('admin.documents.review')")
    public LetterTermsReviewDto approve(@PathVariable UUID termsId,
                                        @Valid @RequestBody(required = false) ApproveLetterTermsRequest request) {
        return service.approve(termsId, request == null ? new ApproveLetterTermsRequest(null) : request);
    }

    @Operation(summary = "Reject a version of an estate's allocation conditions",
            description = """
                    Requires `admin.documents.review`. The note is required and shown to the developer. Letters keep \
                    using the last approved version, if any; the developer saves a corrected version, which comes \
                    back to the queue.""")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "`VALIDATION_ERROR`: a note is required", content = @Content),
            @ApiResponse(responseCode = "404", description = "`LETTER_TERMS_NOT_FOUND`", content = @Content),
            @ApiResponse(responseCode = "409", description = "`LETTER_TERMS_ALREADY_DECIDED`, `LETTER_TERMS_SUPERSEDED`",
                    content = @Content)})
    @PostMapping("/{termsId}/reject")
    @PreAuthorize("hasAuthority('admin.documents.review')")
    public LetterTermsReviewDto reject(@PathVariable UUID termsId, @Valid @RequestBody RejectLetterTermsRequest request) {
        return service.reject(termsId, request);
    }
}
