package com.techcomfort.landvaultbackend.kyc.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.kyc.dto.KycDecisionRequest;
import com.techcomfort.landvaultbackend.kyc.dto.KycRecordDto;
import com.techcomfort.landvaultbackend.kyc.internal.service.KycService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Manual review of a buyer's verification, gated on
 * {@code admin.kyc.review}.
 * <p>
 * The reviewer is the authenticated caller, resolved from
 * {@code TenantContext} — never a field in the request body, the same rule
 * as every other decision recorded in this system.
 */
@RestController
@RequestMapping("/api/admin/kyc")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_ADMIN_KYC)
public class AdminKycController {

    private final KycService kycService;

    @Operation(
            summary = "One buyer's verification",
            description = "Requires `admin.kyc.review`. The submitted NIN is not returned here "
                    + "either — no route exposes it.")
    @ApiResponse(responseCode = "200", description = "The buyer's record")
    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('admin.kyc.review')")
    public ResponseEntity<KycRecordDto> get(@PathVariable UUID userId) {
        return ResponseEntity.ok(kycService.statusFor(userId));
    }

    @Operation(
            summary = "Approve or reject a buyer's verification",
            description = """
                    Requires `admin.kyc.review`. **Manual review — no registry is called.** The \
                    decision is recorded as `manual_review`, so a future automated check against \
                    NIMC stays distinguishable from a human reading a scan.

                    **A rejection must name the documents that failed** and carry a reason, which is \
                    copied onto each of them. Every other document on the submission is approved by \
                    the same decision, so the buyer resubmits only what failed.

                    The reviewer is the authenticated caller, never a field in the body.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The decided record"),
            @ApiResponse(responseCode = "400", description = "`INVALID_KYC_DECISION`: not "
                    + "approved/rejected, a rejection with no reason or no named document, or a "
                    + "document this buyer was never asked for", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`KYC_RECORD_NOT_FOUND`: that buyer has "
                    + "submitted nothing", content = @Content())
    })
    @PostMapping("/{userId}/decision")
    @PreAuthorize("hasAuthority('admin.kyc.review')")
    public ResponseEntity<KycRecordDto> decide(
            @PathVariable UUID userId, @Valid @RequestBody KycDecisionRequest request) {

        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        return ResponseEntity.ok(kycService.decide(userId, scope.userId(), request));
    }
}
