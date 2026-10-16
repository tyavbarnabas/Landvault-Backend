package com.techcomfort.landvaultbackend.documents.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.documents.dto.DocumentVerificationDto;
import com.techcomfort.landvaultbackend.documents.internal.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** DV-2: the public "is this genuine?" lookup behind a document's QR code. */
@RestController
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_DOCUMENTS)
public class DocumentVerificationController {

    private final DocumentService service;

    @Operation(summary = "Check a document is genuine",
            description = """
                    **Public**, GET only, rate limited per connection. The code printed on a HomeTrue document \
                    (dashes and case don't matter). Says whether HomeTrue issued it and what it is about — the \
                    developer, estate and plot, the date, and the holder's initials. **Never an amount, and never \
                    the holder's name, address or phone**: anyone holding a copy can open this.

                    A receipt whose payment was later returned reads `refunded`. An unknown or malformed code \
                    is one 404, `NOT_A_HOMETRUE_DOCUMENT`, whatever the reason.""")
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", description = "`NOT_A_HOMETRUE_DOCUMENT`", content = @Content),
            @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`", content = @Content)})
    @GetMapping("/api/documents/verify/{code}")
    public ResponseEntity<DocumentVerificationDto> verify(@PathVariable String code) {
        return ResponseEntity.ok(service.verify(code));
    }
}
