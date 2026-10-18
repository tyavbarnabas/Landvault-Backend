package com.techcomfort.landvaultbackend.documents.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.documents.dto.ApproveLetterTermsRequest;
import com.techcomfort.landvaultbackend.documents.dto.LetterTemplateReviewDto;
import com.techcomfort.landvaultbackend.documents.dto.RejectLetterTermsRequest;
import com.techcomfort.landvaultbackend.documents.internal.service.LetterTemplateReviewService;
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

/** HomeTrue's review of company and branch letter templates. See AGENTS.md, "Documents: letter templates". */
@RestController
@RequestMapping("/api/admin/letter-template-reviews")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_ADMIN_DOCUMENTS)
public class LetterTemplateReviewController {

    private final LetterTemplateReviewService service;

    @Operation(summary = "List letter templates by review status",
            description = """
                    Requires `admin.documents.review`. `pending` (the default) is the queue — each company's and \
                    branch's newest version not yet decided, oldest first.""")
    @ApiResponses({@ApiResponse(responseCode = "400", description = "`INVALID_PARAMETER`", content = @Content)})
    @GetMapping
    @PreAuthorize("hasAuthority('admin.documents.review')")
    public List<LetterTemplateReviewDto> list(@RequestParam(required = false) String status) {
        return service.list(status);
    }

    @Operation(summary = "Get one letter template version for review", description = "Requires `admin.documents.review`.")
    @ApiResponses({@ApiResponse(responseCode = "404", description = "`LETTER_TEMPLATE_NOT_FOUND`", content = @Content)})
    @GetMapping("/{templateId}")
    @PreAuthorize("hasAuthority('admin.documents.review')")
    public LetterTemplateReviewDto get(@PathVariable UUID templateId) {
        return service.get(templateId);
    }

    @Operation(summary = "Approve a letter template version",
            description = """
                    Requires `admin.documents.review`. Letters then use it. Read the opening, closing and standard \
                    conditions for anything the automatic check can't catch — a sum written another way, a payment \
                    instruction, an account, or wording that misstates what the buyer is getting.""")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "`LETTER_TEMPLATE_NOT_FOUND`", content = @Content),
            @ApiResponse(responseCode = "409", description = "`LETTER_TEMPLATE_ALREADY_DECIDED`, `LETTER_TEMPLATE_SUPERSEDED`",
                    content = @Content)})
    @PostMapping("/{templateId}/approve")
    @PreAuthorize("hasAuthority('admin.documents.review')")
    public LetterTemplateReviewDto approve(@PathVariable UUID templateId,
                                           @Valid @RequestBody(required = false) ApproveLetterTermsRequest request) {
        return service.approve(templateId, request == null ? new ApproveLetterTermsRequest(null) : request);
    }

    @Operation(summary = "Reject a letter template version",
            description = "Requires `admin.documents.review`. The note is required and shown to the developer.")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "`VALIDATION_ERROR`: a note is required", content = @Content),
            @ApiResponse(responseCode = "404", description = "`LETTER_TEMPLATE_NOT_FOUND`", content = @Content),
            @ApiResponse(responseCode = "409", description = "`LETTER_TEMPLATE_ALREADY_DECIDED`, `LETTER_TEMPLATE_SUPERSEDED`",
                    content = @Content)})
    @PostMapping("/{templateId}/reject")
    @PreAuthorize("hasAuthority('admin.documents.review')")
    public LetterTemplateReviewDto reject(@PathVariable UUID templateId,
                                          @Valid @RequestBody RejectLetterTermsRequest request) {
        return service.reject(templateId, request);
    }
}
