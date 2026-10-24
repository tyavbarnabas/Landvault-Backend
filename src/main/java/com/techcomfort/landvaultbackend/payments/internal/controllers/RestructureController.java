package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.AcceptRestructureRequest;
import com.techcomfort.landvaultbackend.payments.dto.RequestRestructureRequest;
import com.techcomfort.landvaultbackend.payments.dto.RestructureDto;
import com.techcomfort.landvaultbackend.payments.internal.service.RestructureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Phase 5c: a buyer asks to restructure their own plan, and answers the developer's proposal. */
@RestController
@RequestMapping("/api/transactions/{transactionId}/restructure")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PAYMENTS)
public class RestructureController {

    private final RestructureService service;

    @Operation(summary = "My plan's restructure requests",
            description = """
                    Requires `client.checkout.reserve`; the buyer's own purchase. The open request or proposal (`open`) \
                    and every earlier one. A proposal shows the new instalments, the penalties carried onto the \
                    first, the last day to accept (`expiresOn`) and the restructure letter to read.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The plan's restructures"),
            @ApiResponse(responseCode = "404", description = "`TRANSACTION_NOT_FOUND`", content = @Content())
    })
    @GetMapping
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public RestructureDto get(@PathVariable UUID transactionId) {
        return service.forBuyer(transactionId);
    }

    @Operation(summary = "Ask for my remaining instalments to be spread differently",
            description = """
                    Requires `client.checkout.reserve`; a running plan. The developer's finance decides: they \
                    propose new terms (the total owed never changes) or decline. One request at a time. The \
                    developer is emailed.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Requested"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_ERROR`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`TRANSACTION_NOT_FOUND`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`PLAN_NOT_RESTRUCTURABLE`; `RESTRUCTURE_ALREADY_OPEN`", content = @Content())
    })
    @PostMapping
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public RestructureDto request(@PathVariable UUID transactionId,
                                  @Valid @RequestBody RequestRestructureRequest request) {
        return service.request(transactionId, request.reason(), request.preferredInstalments());
    }

    @Operation(summary = "Withdraw my request", description = "Only while the developer hasn't proposed or declined.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawn"),
            @ApiResponse(responseCode = "409", description = "`RESTRUCTURE_NOT_OPEN`", content = @Content())
    })
    @PostMapping("/cancel")
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public RestructureDto cancel(@PathVariable UUID transactionId) {
        return service.cancel(transactionId);
    }

    @Operation(summary = "Accept the proposal",
            description = """
                    Name the proposal (`restructureId`) whose letter you read. It takes effect at once: the new \
                    instalments replace the unpaid ones, paid ones stay paid, penalties already reached are payable \
                    with the first new instalment, and lateness restarts on the new dates (a running notice of \
                    revocation ends). Refused if it has lapsed, or if what is owed changed since it was proposed.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Accepted: the new schedule is in force"),
            @ApiResponse(responseCode = "409", description = "`RESTRUCTURE_NOT_FOUND` (not the open proposal); "
                    + "`RESTRUCTURE_LAPSED`; `RESTRUCTURE_OUT_OF_DATE`; `PAYMENT_AWAITING_FINANCE`; "
                    + "`PLAN_NOT_RESTRUCTURABLE`", content = @Content())
    })
    @PostMapping("/accept")
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public RestructureDto accept(@PathVariable UUID transactionId, @Valid @RequestBody AcceptRestructureRequest request,
                                 @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return service.accept(transactionId, request.restructureId(), userAgent);
    }

    @Operation(summary = "Decline the proposal", description = "Your current schedule carries on. You may ask again.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Declined"),
            @ApiResponse(responseCode = "409", description = "`RESTRUCTURE_NOT_FOUND`", content = @Content())
    })
    @PostMapping("/decline")
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public RestructureDto decline(@PathVariable UUID transactionId,
                                  @Valid @RequestBody AcceptRestructureRequest request) {
        return service.decline(transactionId, request.restructureId());
    }
}
