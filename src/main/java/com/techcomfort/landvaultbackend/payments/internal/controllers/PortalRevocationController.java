package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.CancelRevocationRequest;
import com.techcomfort.landvaultbackend.payments.dto.RevocationDto;
import com.techcomfort.landvaultbackend.payments.dto.StartRevocationRequest;
import com.techcomfort.landvaultbackend.payments.internal.service.RevocationService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Phase 5b: the developer revokes an instalment plan the buyer has stopped paying. See {@link RevocationService}. */
@RestController
@RequestMapping("/api/portal/plans/{transactionId}/revocation")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_FINANCE)
public class PortalRevocationController {

    private final RevocationService service;

    @Operation(summary = "Where a plan stands for revocation",
            description = """
                    Requires `portal.plans.revoke` (Executive Director) and the company-wide view. The terms \
                    copied onto the purchase when it opened (`afterMonthsUnpaid`, `noticeDays`), whether notice \
                    can be given today, what is overdue, the open notice, and what revoking would return to the \
                    buyer — exactly a withdrawal's figures. Every earlier notice is in `history`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The plan's revocation standing"),
            @ApiResponse(responseCode = "403", description = "`REVOCATION_REQUIRES_COMPANY_WIDE_SCOPE`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`TRANSACTION_NOT_FOUND`: not your company's instalment purchase", content = @Content())
    })
    @GetMapping
    @PreAuthorize("hasAuthority('portal.plans.revoke')")
    public RevocationDto get(@PathVariable UUID transactionId) {
        return service.get(transactionId);
    }

    @Operation(summary = "Give the buyer notice of revocation",
            description = """
                    Requires `portal.plans.revoke` and the company-wide view. Only once an instalment is unpaid \
                    `afterMonthsUnpaid` whole months past its due date — the number the buyer was shown before \
                    paying. A payment waiting for your finance to verify counts as paid.

                    The buyer is emailed your reason, what is overdue, the deadline (`noticeDays` from today, \
                    Lagos) and what a revocation would return. Paying what is overdue before then ends the notice \
                    (once finance verifies it). They may also withdraw. Nothing changes on the plan yet.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Notice given"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_ERROR`: `reason` missing or over 500 characters", content = @Content()),
            @ApiResponse(responseCode = "403", description = "`REVOCATION_REQUIRES_COMPANY_WIDE_SCOPE`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`TRANSACTION_NOT_FOUND`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`PLAN_NOT_REVOCABLE` (not running); `REVOCATION_TERMS_MISSING`; "
                    + "`REVOCATION_NOT_DUE` (nobody is that far behind — says when they would be); "
                    + "`REVOCATION_ALREADY_NOTIFIED`", content = @Content())
    })
    @PostMapping
    @PreAuthorize("hasAuthority('portal.plans.revoke')")
    public RevocationDto start(@PathVariable UUID transactionId, @Valid @RequestBody StartRevocationRequest request) {
        return service.start(transactionId, request.reason());
    }

    @Operation(summary = "Withdraw the notice",
            description = """
                    Requires `portal.plans.revoke` and the company-wide view. The plan carries on as before; the \
                    buyer is emailed, with your note if you give one.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Notice withdrawn"),
            @ApiResponse(responseCode = "404", description = "`TRANSACTION_NOT_FOUND`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`NO_OPEN_REVOCATION`", content = @Content())
    })
    @PostMapping("/cancel")
    @PreAuthorize("hasAuthority('portal.plans.revoke')")
    public RevocationDto cancel(@PathVariable UUID transactionId,
                                @Valid @RequestBody(required = false) CancelRevocationRequest request) {
        return service.cancel(transactionId, request == null ? null : request.note());
    }

    @Operation(summary = "Confirm the revocation",
            description = """
                    Requires `portal.plans.revoke` and the company-wide view. Only after the notice's deadline, \
                    and only if the buyer is still that far behind.

                    At once: the purchase becomes `revoked`, the plot goes back on sale with its original \
                    availability, the provisional allocation letter reads `revoked`, and each payment's part is \
                    owed back to the buyer exactly as on a withdrawal — a HomeTrue Super Admin sends the refunds. \
                    Your shares already verified stay yours; the share of a payment your finance hasn't verified \
                    is paid to you once you verify it. The buyer is emailed.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Revoked: what was settled"),
            @ApiResponse(responseCode = "404", description = "`TRANSACTION_NOT_FOUND`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`NO_OPEN_REVOCATION`; `REVOCATION_NOTICE_RUNNING` (the "
                    + "deadline hasn't passed); `REVOCATION_NOT_DUE` (the buyer caught up, or a payment awaits your "
                    + "finance); `PLAN_NOT_REVOCABLE`", content = @Content())
    })
    @PostMapping("/confirm")
    @PreAuthorize("hasAuthority('portal.plans.revoke')")
    public RevocationDto confirm(@PathVariable UUID transactionId) {
        return service.confirm(transactionId);
    }
}
