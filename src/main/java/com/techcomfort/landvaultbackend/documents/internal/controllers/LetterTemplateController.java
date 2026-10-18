package com.techcomfort.landvaultbackend.documents.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.documents.dto.LetterTemplateDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterTemplateVersionDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterTemplatesOverviewDto;
import com.techcomfort.landvaultbackend.documents.dto.SaveLetterTemplateRequest;
import com.techcomfort.landvaultbackend.documents.internal.service.LetterTemplateService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The company's and each branch's wording for allocation letters. See AGENTS.md, "Documents: letter templates". */
@RestController
@RequestMapping("/api/portal/letter-templates")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_DOCUMENTS)
public class LetterTemplateController {

    private final LetterTemplateService service;

    @Operation(summary = "Get the company's and branches' letter templates",
            description = """
                    Requires `portal.documents.view`. For the company and each branch the caller can see: the newest \
                    saved version, the approved one letters use, and whose wording applies today (`inUse`). Also \
                    the fill-in fields and HomeTrue's default wording.""")
    @GetMapping
    @PreAuthorize("hasAuthority('portal.documents.view')")
    public LetterTemplatesOverviewDto overview() {
        return service.overview();
    }

    @Operation(summary = "Save the company's or a branch's letter template",
            description = """
                    Requires `portal.documents.manage` (the Executive Director) and a company-wide view. `target` \
                    is `company` or a branch id. Saves a new version for HomeTrue to review; letters keep using the \
                    approved version until then. The same template again changes nothing.
                    
                    **Refused with 400 `TEMPLATE_REFUSED`** (every problem at once in `fieldErrors`, keyed \
                    `opening`, `closing`, `signOff`, `standardClauses[i]`; nothing saved): an opening without \
                    {plot} and {estate}, an unknown fill-in field, or an amount or bank details anywhere.""")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "`TEMPLATE_REFUSED`, `VALIDATION_ERROR`, `INVALID_PARAMETER`",
                    content = @Content),
            @ApiResponse(responseCode = "403", description = "`DOCUMENTS_REQUIRE_COMPANY_WIDE_SCOPE`", content = @Content),
            @ApiResponse(responseCode = "404", description = "`BRANCH_NOT_FOUND`", content = @Content),
            @ApiResponse(responseCode = "409", description = "`LETTER_TEMPLATE_CHANGED`: saved by someone else at the same moment",
                    content = @Content)})
    @PutMapping("/{target}")
    @PreAuthorize("hasAuthority('portal.documents.manage')")
    public LetterTemplateDto save(@PathVariable String target, @Valid @RequestBody SaveLetterTemplateRequest request) {
        return service.save(target, request);
    }

    @Operation(summary = "List earlier versions of a letter template",
            description = "Requires `portal.documents.view`. Newest first, each with HomeTrue's decision.")
    @ApiResponses({@ApiResponse(responseCode = "404", description = "`BRANCH_NOT_FOUND`", content = @Content)})
    @GetMapping("/{target}/versions")
    @PreAuthorize("hasAuthority('portal.documents.view')")
    public List<LetterTemplateVersionDto> versions(@PathVariable String target) {
        return service.versions(target);
    }
}
