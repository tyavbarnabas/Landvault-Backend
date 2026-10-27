package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.GainShareDto;
import com.techcomfort.landvaultbackend.payments.dto.RefundAccountRequest;
import com.techcomfort.landvaultbackend.payments.internal.service.GainShareService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Flexible contribution, phase 5: a buyer's claims on the gain of a plot they left. See {@link GainShareService}. */
@RestController
@RequestMapping("/api/gain-shares")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PAYMENTS)
public class GainShareController {

    private final GainShareService gainShares;

    @Operation(summary = "Your gain shares",
            description = """
                    Requires `client.checkout.reserve`. One per flexible plan you left (or that was ended), newest \
                    first. `open` until the plot's next sale through HomeTrue is fully paid and verified; then \
                    `owed` — your share of the price above your agreed one, in `amount` — or `no_gain` when it sold \
                    for no more. While `accountNeeded`, give the account it should go to. A HomeTrue Super Admin \
                    sends it, out of the developer's payout for that sale.""")
    @GetMapping
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<List<GainShareDto>> mine() {
        return ResponseEntity.ok(gainShares.mine());
    }

    @Operation(summary = "Give the bank account for a gain share",
            description = """
                    Requires `client.checkout.reserve`; your own gain share only. The name on the account is what \
                    the bank returns, never typed. May be replaced until HomeTrue starts sending it.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The gain share, with the account"),
            @ApiResponse(responseCode = "400", description = "`UNKNOWN_BANK`, `ACCOUNT_NOT_RESOLVED`, `VALIDATION_ERROR`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`GAIN_SHARE_NOT_FOUND`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`GAIN_SHARE_NOT_OWED`, `GAIN_SHARE_ALREADY_SENDING`", content = @Content()),
            @ApiResponse(responseCode = "503", description = "`PAYMENT_PROVIDER_UNAVAILABLE`", content = @Content())
    })
    @PutMapping("/{claimId}/account")
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<GainShareDto> giveAccount(@PathVariable UUID claimId,
                                                    @Valid @RequestBody RefundAccountRequest request) {
        return ResponseEntity.ok(gainShares.giveAccount(claimId, request.bankCode(), request.accountNumber()));
    }
}
