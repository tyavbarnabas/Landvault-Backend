package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.RejectRestructureRequest;
import com.techcomfort.landvaultbackend.payments.dto.RestructureDto;
import com.techcomfort.landvaultbackend.payments.dto.RestructureNoteRequest;
import com.techcomfort.landvaultbackend.payments.dto.RestructureQueueItemDto;
import com.techcomfort.landvaultbackend.payments.dto.RestructureTermsRequest;
import com.techcomfort.landvaultbackend.payments.internal.service.RestructureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Phase 5c: the developer's finance decides on buyers' restructure requests. See {@link RestructureService}. */
@RestController
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_FINANCE)
public class PortalRestructureController {

    private final RestructureService service;

    @Operation(summary = "Open restructure requests and proposals",
            description = """
                    Requires `portal.payments.verify`. Your company's open restructures, oldest first; a branch \
                    officer sees only their branch's. `requested` is yours to decide; `proposed` is waiting for the \
                    buyer (until `expiresOn`).""")
    @GetMapping("/api/portal/finance/restructures")
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public List<RestructureQueueItemDto> queue(
            @Parameter(description = "`requested` or `proposed`; both when left out.")
            @RequestParam(required = false) String status) {
        return service.queue(status);
    }

    @Operation(summary = "A plan's restructures", description = "Requires `portal.payments.verify`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The plan's restructures"),
            @ApiResponse(responseCode = "404", description = "`TRANSACTION_NOT_FOUND`", content = @Content())
    })
    @GetMapping("/api/portal/plans/{transactionId}/restructure")
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public RestructureDto get(@PathVariable UUID transactionId) {
        return service.forSeller(transactionId);
    }

    @Operation(summary = "Preview new terms",
            description = """
                    Requires `portal.payments.verify`. What proposing these terms would mean, worked out by \
                    HomeTrue: what is still owed spread over `instalmentCount` new instalments from `firstDueDate`, \
                    numbering carried on from the paid ones, and the penalties already reached carried onto the \
                    first. Nothing is written.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The figures"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_ERROR`; `INVALID_RESTRUCTURE_TERMS` (more than 60 "
                    + "instalments in all, a first date not after today or more than 3 months away)", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`PLAN_NOT_RESTRUCTURABLE`; `PAYMENT_AWAITING_FINANCE`", content = @Content())
    })
    @PostMapping("/api/portal/plans/{transactionId}/restructure/preview")
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public RestructureDto.Proposal preview(@PathVariable UUID transactionId,
                                           @Valid @RequestBody RestructureTermsRequest request) {
        return service.preview(transactionId, request.instalmentCount(), request.frequency(), request.firstDueDate());
    }

    @Operation(summary = "Propose new terms",
            description = """
                    Requires `portal.payments.verify`; answers the buyer's open request. The figures are the \
                    preview's. A restructure letter (HT-RST-) is issued on your letterhead and the buyer is \
                    emailed; they have 14 days to accept, or it lapses. Nothing changes until they accept. To change \
                    a proposal, withdraw it and propose again.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Proposed"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_ERROR`; `INVALID_RESTRUCTURE_TERMS`", content = @Content()),
            @ApiResponse(responseCode = "403", description = "`OWN_PURCHASE`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`RESTRUCTURE_NOT_OPEN` (no request waiting); "
                    + "`PAYMENT_AWAITING_FINANCE`; `LETTER_NOT_APPROVED`; `PLAN_NOT_RESTRUCTURABLE`", content = @Content())
    })
    @PostMapping("/api/portal/plans/{transactionId}/restructure/propose")
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public RestructureDto propose(@PathVariable UUID transactionId,
                                  @Valid @RequestBody RestructureTermsRequest request) {
        return service.propose(transactionId, request.instalmentCount(), request.frequency(), request.firstDueDate());
    }

    @Operation(summary = "Decline the request", description = "Requires `portal.payments.verify`. The reason is emailed to the buyer.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Declined"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_ERROR`: a reason is required", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`RESTRUCTURE_NOT_OPEN`", content = @Content())
    })
    @PostMapping("/api/portal/plans/{transactionId}/restructure/reject")
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public RestructureDto reject(@PathVariable UUID transactionId, @Valid @RequestBody RejectRestructureRequest request) {
        return service.reject(transactionId, request.reason());
    }

    @Operation(summary = "Withdraw your proposal",
            description = "Requires `portal.payments.verify`. The letter can no longer be accepted and the buyer is "
                    + "emailed. Their request stays open, so you can propose different terms, or decline.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawn"),
            @ApiResponse(responseCode = "409", description = "`RESTRUCTURE_NOT_OPEN`", content = @Content())
    })
    @PostMapping("/api/portal/plans/{transactionId}/restructure/withdraw")
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public RestructureDto withdraw(@PathVariable UUID transactionId,
                                   @Valid @RequestBody(required = false) RestructureNoteRequest request) {
        return service.withdraw(transactionId, request == null ? null : request.note());
    }
}
