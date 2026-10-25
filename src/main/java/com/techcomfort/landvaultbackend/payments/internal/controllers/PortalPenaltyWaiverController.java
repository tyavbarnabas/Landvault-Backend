package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.PenaltyWaiversDto;
import com.techcomfort.landvaultbackend.payments.dto.WaivePenaltyRequest;
import com.techcomfort.landvaultbackend.payments.internal.service.PenaltyWaiverService;
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

/** The developer's finance waives late-payment penalties on a plan. See {@link PenaltyWaiverService}. */
@RestController
@RequestMapping("/api/portal/plans/{transactionId}/penalty-waivers")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_FINANCE)
public class PortalPenaltyWaiverController {

    private final PenaltyWaiverService service;

    @Operation(summary = "A plan's penalties and waivers",
            description = """
                    Requires `portal.payments.verify`; your company's plans (a branch officer, their branch's). Each \
                    unpaid instalment carrying a penalty today — `reached` (including one carried by a restructure), \
                    `waived` so far and `outstanding` — and every waiver made.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The penalties and waivers"),
            @ApiResponse(responseCode = "404", description = "`TRANSACTION_NOT_FOUND`", content = @Content())
    })
    @GetMapping
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public PenaltyWaiversDto get(@PathVariable UUID transactionId) {
        return service.view(transactionId);
    }

    @Operation(summary = "Waive a penalty",
            description = """
                    Requires `portal.payments.verify`. Waives `amount` — up to the instalment's `outstanding` \
                    penalty — of a penalty not yet paid, with a reason the buyer is emailed. It comes off what the \
                    buyer sees and is charged at once. A fixed amount: if the instalment stays unpaid and reaches a \
                    higher penalty step, the difference is owed. A penalty already paid can't be waived; a payment \
                    already started keeps the penalty it was started with. Never on your own purchase.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Waived: the plan's penalties and waivers now"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_ERROR`; `WAIVER_TOO_LARGE`", content = @Content()),
            @ApiResponse(responseCode = "403", description = "`OWN_PURCHASE`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`TRANSACTION_NOT_FOUND`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`PENALTY_NOT_WAIVABLE`: not a running plan, the "
                    + "instalment is paid, or it carries no penalty", content = @Content())
    })
    @PostMapping
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public PenaltyWaiversDto waive(@PathVariable UUID transactionId, @Valid @RequestBody WaivePenaltyRequest request) {
        return service.waive(transactionId, request.instalmentSequence(), request.amount(), request.reason());
    }
}
