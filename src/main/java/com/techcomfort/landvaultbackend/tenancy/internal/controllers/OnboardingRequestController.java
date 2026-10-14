package com.techcomfort.landvaultbackend.tenancy.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.tenancy.dto.OnboardingRequestReceivedResponse;
import com.techcomfort.landvaultbackend.tenancy.dto.OnboardingRequestSubmission;
import com.techcomfort.landvaultbackend.tenancy.internal.exceptions.TenancyException;
import com.techcomfort.landvaultbackend.tenancy.internal.service.OnboardingRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The public front door for developer companies. No login; rate-limited; grants nothing. */
@RestController
@RequestMapping("/api/onboarding-requests")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_ONBOARDING)
public class OnboardingRequestController {

    private static final OnboardingRequestReceivedResponse RECEIVED = new OnboardingRequestReceivedResponse(
            "Thank you. We have received your request and our team will be in touch.");

    private final OnboardingRequestService service;

    @Operation(summary = "Ask to be onboarded as a developer",
            description = """
                    Public. A developer company leaves its details and how it would like to be contacted; \
                    HomeTrue is emailed and the company gets a confirmation. **Nothing is created** — no \
                    account, no company — until a HomeTrue admin has spoken to them.

                    **Every accepted submission gets the same 202 and body**, including a repeat from an \
                    email that already has a request waiting (that one is not stored and sends no email). \
                    The reply never says whether a company or email is already known to HomeTrue.

                    Rate-limited per client address (`landvault.onboarding.submissions-per-window`); \
                    beyond it, 429 with `Retry-After`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Received"),
            @ApiResponse(responseCode = "400", description = "A field is missing or invalid, or a state isn't a Nigerian state",
                    content = @Content()),
            @ApiResponse(responseCode = "429", description = "Too many submissions from this address", content = @Content())
    })
    @SecurityRequirements
    @PostMapping
    public ResponseEntity<OnboardingRequestReceivedResponse> submit(
            @Valid @RequestBody OnboardingRequestSubmission submission) {
        try {
            service.submit(submission);
        } catch (TenancyException.OnboardingRequestAlreadyOpen ignored) {
            // Same answer as a success — see the operation description.
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(RECEIVED);
    }
}
