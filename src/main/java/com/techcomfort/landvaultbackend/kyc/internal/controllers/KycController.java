package com.techcomfort.landvaultbackend.kyc.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.kyc.dto.KycRecordDto;
import com.techcomfort.landvaultbackend.kyc.dto.SubmitKycRequest;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A buyer's own verification (KY-1 … KY-3).
 * <p>
 * The buyer is always the authenticated caller — there is no user id in the
 * path or the body, so one buyer cannot read or submit another's.
 */
@RestController
@RequestMapping("/api/kyc")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_KYC)
public class KycController {

    private final KycService kycService;

    @Operation(
            summary = "My verification status",
            description = """
                    Requires `client.kyc.manage`. Always the signed-in buyer's own record.

                    **Verification gates buying, never browsing or signing up.** Nobody is asked for \
                    a passport to look at land; this is reached for the first time at the first \
                    reservation.

                    A buyer who has never submitted gets `unsubmitted` with every required document \
                    `missing` — an honest absence, not a row that was created just by looking. The \
                    required set is decided by the country captured at registration: a local buyer \
                    submits an NIN alone, a diaspora buyer a passport and proof of address.

                    Verification is held once, at platform level, and works with every company on \
                    the marketplace.""")
    @ApiResponse(responseCode = "200", description = "The buyer's record, or the unsubmitted shape")
    @GetMapping
    @PreAuthorize("hasAuthority('client.kyc.manage')")
    public ResponseEntity<KycRecordDto> status() {
        return ResponseEntity.ok(kycService.statusFor(currentUserId()));
    }

    @Operation(
            summary = "Submit or resubmit identity documents",
            description = """
                    Requires `client.kyc.manage`.

                    **Files are metadata only** — object storage is not built, so this records a file \
                    name rather than receiving bytes.

                    **Resubmission after a rejection replaces only what failed.** A document that was \
                    already accepted stays accepted; the buyer does not start the whole verification \
                    again. Submitting against an already-approved verification changes nothing.

                    Review is manual and is not part of this call: the record becomes `submitted`, \
                    never `approved`. The NIN is encrypted at rest and is never returned by any \
                    route.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated record"),
            @ApiResponse(responseCode = "400", description = "`MISSING_KYC_DOCUMENTS`: the submission "
                    + "doesn't carry every document this buyer's country requires",
                    content = @Content())
    })
    @PostMapping
    @PreAuthorize("hasAuthority('client.kyc.manage')")
    public ResponseEntity<KycRecordDto> submit(@Valid @RequestBody SubmitKycRequest request) {
        return ResponseEntity.ok(kycService.submit(currentUserId(), request));
    }

    private UUID currentUserId() {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        return scope.userId();
    }
}
