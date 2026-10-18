package com.techcomfort.landvaultbackend.documents.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.documents.dto.EstateLetterTermsDto;
import com.techcomfort.landvaultbackend.documents.dto.LetterTermsVersionDto;
import com.techcomfort.landvaultbackend.documents.dto.SaveLetterTermsRequest;
import com.techcomfort.landvaultbackend.documents.internal.service.EstateLetterTermsService;
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
import java.util.UUID;

/** Each estate's allocation conditions (DS-3, DS-4). See AGENTS.md, "Documents: allocation terms". */
@RestController
@RequestMapping("/api/portal/estates/{estateId}/letter-terms")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_DOCUMENTS)
public class EstateLetterTermsController {

    private final EstateLetterTermsService service;

    @Operation(summary = "Get an estate's allocation conditions",
            description = """
                    Requires `portal.documents.view`. The developer's own clauses, and the ones HomeTrue writes \
                    from the estate's cost disclosure (`generatedClauses`), which follow them on the letter. \
                    `missingDisclosure` names any disclosure part not declared yet.""")
    @ApiResponses({@ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`", content = @Content)})
    @GetMapping
    @PreAuthorize("hasAuthority('portal.documents.view')")
    public EstateLetterTermsDto get(@PathVariable UUID estateId) {
        return service.get(estateId);
    }

    @Operation(summary = "Save an estate's allocation conditions",
            description = """
                    Requires `portal.documents.manage` (the Executive Director) and a company-wide view \
                    (`DOCUMENTS_REQUIRE_COMPANY_WIDE_SCOPE`). Saves the clauses, in order, as a new version; \
                    earlier versions are kept, and the same clauses again change nothing.
                    
                    **Refused with 400 `CLAUSE_REFUSED`** (every problem at once, in `fieldErrors` keyed \
                    `clauses[i]`; nothing saved) when a clause contains an amount — fees and figures come \
                    from the cost disclosure and are added automatically — or bank details: buyers pay only \
                    through HomeTrue. Sizes in square metres and percentages are fine.""")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "`CLAUSE_REFUSED`, `VALIDATION_ERROR`", content = @Content),
            @ApiResponse(responseCode = "403", description = "`DOCUMENTS_REQUIRE_COMPANY_WIDE_SCOPE`", content = @Content),
            @ApiResponse(responseCode = "404", description = "`ESTATE_NOT_FOUND`", content = @Content),
            @ApiResponse(responseCode = "409", description = "`LETTER_TERMS_CHANGED`: saved by someone else at the same moment",
                    content = @Content)})
    @PutMapping
    @PreAuthorize("hasAuthority('portal.documents.manage')")
    public EstateLetterTermsDto save(@PathVariable UUID estateId, @Valid @RequestBody SaveLetterTermsRequest request) {
        return service.save(estateId, request);
    }

    @Operation(summary = "List earlier versions of an estate's allocation conditions",
            description = "Requires `portal.documents.view`. Newest first.")
    @GetMapping("/versions")
    @PreAuthorize("hasAuthority('portal.documents.view')")
    public List<LetterTermsVersionDto> versions(@PathVariable UUID estateId) {
        return service.versions(estateId);
    }
}
