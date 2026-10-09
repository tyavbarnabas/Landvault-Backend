package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.PaymentDto;
import com.techcomfort.landvaultbackend.payments.internal.service.PaymentConfirmationService;
import com.techcomfort.landvaultbackend.payments.internal.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** PY-1 and PY-3: a buyer starts paying for their own transaction, and asks for it to be confirmed. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PAYMENTS)
public class PaymentController {

    private final PaymentService service;
    private final PaymentConfirmationService confirmation;

    @Operation(summary = "Start paying for a transaction",
            description = """
                    Requires `client.checkout.reserve`. Returns a Paystack payment link: send the buyer \
                    to `authorizationUrl`; Paystack returns them to the frontend afterwards.

                    - The amount is the price **agreed at reservation**, never sent by the client.
                    - Outright purchases only; NGN only.
                    - Pressing pay again within 30 minutes returns **the same open link** (200) instead \
                    of opening a second payment (201).
                    - **Landing back on the frontend proves nothing.** A payment counts only once \
                    LandVault has verified it with Paystack.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "A new payment link"),
            @ApiResponse(responseCode = "200", description = "The open link from a moment ago, handed back"),
            @ApiResponse(responseCode = "400", description = "`CURRENCY_NOT_SUPPORTED`, `PAYMENT_PLAN_NOT_SUPPORTED`, `INVALID_AMOUNT`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`TRANSACTION_NOT_FOUND` (including someone else's)", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`TRANSACTION_NOT_PAYABLE`: already past waiting for payment", content = @Content()),
            @ApiResponse(responseCode = "502", description = "`PAYMENT_PROVIDER_REFUSED`", content = @Content()),
            @ApiResponse(responseCode = "503", description = "`PAYMENT_PROVIDER_UNAVAILABLE`: try again shortly", content = @Content())
    })
    @PostMapping("/transactions/{transactionId}/payments")
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<PaymentDto> start(@PathVariable UUID transactionId) {
        PaymentService.Started started = service.start(transactionId);
        return ResponseEntity.status(started.created() ? HttpStatus.CREATED : HttpStatus.OK).body(started.payment());
    }

    @Operation(summary = "Ask LandVault to confirm a payment with Paystack",
            description = """
                    Requires `client.checkout.reserve`; only your own payment (404 otherwise). Call it from \
                    the page Paystack returns the buyer to. **It claims nothing**: LandVault asks Paystack \
                    itself, and calling it repeatedly is safe.

                    - `succeeded`: Paystack confirmed exactly the agreed amount. The transaction moves to \
                    `awaiting_finance` — **the plot is not yours yet**; finance verifies first.
                    - `failed`: show `gatewayResponse` (e.g. "Insufficient Funds") so the buyer knows \
                    whether to retry or use another method. Paying again starts a new attempt.
                    - `mismatched`: Paystack reported a different amount — not treated as paid; our team reviews it.
                    - `initialized`: not finished yet (a bank transfer can take minutes). Check again shortly.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The payment as it now stands"),
            @ApiResponse(responseCode = "404", description = "`PAYMENT_NOT_FOUND` (including someone else's)", content = @Content()),
            @ApiResponse(responseCode = "503", description = "`PAYMENT_PROVIDER_UNAVAILABLE`: try again shortly", content = @Content())
    })
    @PostMapping("/payments/{reference}/verify")
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<PaymentDto> verify(@PathVariable String reference) {
        return ResponseEntity.ok(confirmation.confirmForBuyer(reference));
    }
}
