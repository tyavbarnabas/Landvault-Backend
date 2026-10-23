package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.WithdrawRequest;
import com.techcomfort.landvaultbackend.payments.dto.WithdrawalDto;
import com.techcomfort.landvaultbackend.payments.internal.service.WithdrawalService;
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

/** Phase 5a: a buyer withdraws from their own instalment plan — quote first, then confirm. */
@RestController
@RequestMapping("/api/transactions/{transactionId}/withdrawal")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PAYMENTS)
public class WithdrawalController {

    private final WithdrawalService service;

    @Operation(summary = "What withdrawing from the plan would return",
            description = """
                    Requires `client.checkout.reserve`; the buyer's own purchase only.

                    While the plan runs (`on_plan`) this is a **quote** — nothing happens. Payment by payment: \
                    what comes back to the buyer and what the developer keeps, on the refund terms copied onto \
                    the purchase when it opened. A payment Paystack confirmed that the developer's finance hasn't \
                    verified yet (`awaitingFinance`) counts the same as a verified one.

                    Once withdrawn, the same route shows what was settled, with each refund's status.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The quote, or the settled withdrawal"),
            @ApiResponse(responseCode = "404", description = "`TRANSACTION_NOT_FOUND` (including someone else's)", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`PLAN_NOT_WITHDRAWABLE`: not a running instalment plan (outright, waiting for the deposit, paid off)", content = @Content())
    })
    @GetMapping
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public WithdrawalDto get(@PathVariable UUID transactionId) {
        return service.get(transactionId);
    }

    @Operation(summary = "Withdraw from the plan",
            description = """
                    Requires `client.checkout.reserve`; the buyer's own running plan only. No one's approval is \
                    needed. Send back the quote's `refund`: if the figures changed since (409 \
                    `WITHDRAWAL_QUOTE_CHANGED`), nothing happens — fetch the quote again.

                    At once: the purchase becomes `withdrawn`, the plot goes back on sale, the provisional \
                    allocation letter reads `withdrawn`, and each payment's part is owed back. HomeTrue sends \
                    the refunds (a Super Admin, payment by payment). The developer's shares stay theirs; the \
                    share of a payment their finance hasn't verified is paid to them once they verify it — if \
                    they reject it instead, that share comes back too. The buyer and the developer are emailed.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawn: what was settled"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_ERROR`: `refund` missing, `reason` over 500 characters", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`TRANSACTION_NOT_FOUND` (including someone else's)", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`PLAN_NOT_WITHDRAWABLE`; `WITHDRAWAL_QUOTE_CHANGED`", content = @Content())
    })
    @PostMapping
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public WithdrawalDto withdraw(@PathVariable UUID transactionId, @Valid @RequestBody WithdrawRequest request) {
        return service.withdraw(transactionId, request.refund(), request.reason());
    }
}
