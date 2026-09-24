package com.techcomfort.landvaultbackend.checkout.internal.controllers;

import com.techcomfort.landvaultbackend.checkout.dto.CreateTransactionRequest;
import com.techcomfort.landvaultbackend.checkout.dto.TransactionDto;
import com.techcomfort.landvaultbackend.checkout.internal.service.TransactionService;
import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
 * The pending purchase record (TX-1 … TX-3). Always the authenticated
 * buyer's own.
 */
@RestController
@RequestMapping("/api/checkout/transactions")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_CHECKOUT)
public class CheckoutTransactionController {

    private final TransactionService transactionService;

    @Operation(
            summary = "Open a pending purchase",
            description = """
                    Requires `client.checkout.reserve`, and an active hold belonging to the caller.

                    **The price is not in the request and is not recomputed here.** It was computed \
                    server-side and captured the instant the plot was locked, so a tier re-priced \
                    during checkout cannot change what the buyer agreed to. Price fields sent by a \
                    client are ignored.

                    **The result is `pending_payment`, and that is as far as this goes.** \
                    Reservation does not allocate: the plot stays held, never sold, until a \
                    finance-role human verifies a payment. Never present this to a buyer as \
                    complete.

                    One transaction per hold — a repeat POST is refused as a duplicate rather than \
                    opening a second purchase.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The pending transaction"),
            @ApiResponse(responseCode = "400", description = "`INVALID_PAYMENT_PLAN`: a month count "
                    + "without an installment plan, or an installment plan without one",
                    content = @Content()),
            @ApiResponse(responseCode = "404", description = "`RESERVATION_NOT_FOUND`: no such hold "
                    + "of yours", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`RESERVATION_NOT_ACTIVE` (expired, "
                    + "released or converted) or `TRANSACTION_ALREADY_EXISTS`", content = @Content())
    })
    @PostMapping
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<TransactionDto> create(@Valid @RequestBody CreateTransactionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.create(currentUserId(), request));
    }

    @Operation(
            summary = "One of my transactions",
            description = "Requires `client.checkout.reserve`. Another buyer's transaction returns "
                    + "404, not 403.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The transaction"),
            @ApiResponse(responseCode = "404", description = "`TRANSACTION_NOT_FOUND`",
                    content = @Content())
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<TransactionDto> get(@PathVariable UUID id) {
        return ResponseEntity.ok(transactionService.get(currentUserId(), id));
    }

    private UUID currentUserId() {
        TenantScope scope = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
        return scope.userId();
    }
}
