package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.BankDto;
import com.techcomfort.landvaultbackend.payments.dto.BuyerRefundDto;
import com.techcomfort.landvaultbackend.payments.dto.PaymentDto;
import com.techcomfort.landvaultbackend.payments.dto.RefundAccountRequest;
import com.techcomfort.landvaultbackend.payments.internal.service.PaymentConfirmationService;
import com.techcomfort.landvaultbackend.payments.internal.service.PaymentService;
import com.techcomfort.landvaultbackend.payments.internal.service.RefundService;
import com.techcomfort.landvaultbackend.payments.internal.service.SettlementAccountService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * PY-1 and PY-3: a buyer starts paying for their own transaction, and asks
 * for it to be confirmed. Also the buyer's side of a refund.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PAYMENTS)
public class PaymentController {

    private final PaymentService service;
    private final PaymentConfirmationService confirmation;
    private final RefundService refunds;
    private final SettlementAccountService banks;

    @Operation(summary = "Start paying for a transaction",
            description = """
                    Requires `client.checkout.reserve`. Returns a Paystack payment link: send the buyer \
                    to `authorizationUrl`; Paystack returns them to the frontend afterwards.

                    - The amount is never sent by the client: the price **agreed at reservation**; on an \
                    instalment plan the **deposit**, then — once the deposit is verified (`on_plan`) — the \
                    **next instalment** from the fixed schedule, one at a time (the next only after the last is \
                    verified). With `?balance=true`, everything still owed on the plan in one payment. A late \
                    instalment carries the declared penalty it has reached today, fixed now and shown as \
                    `penaltyAmount` (included in `amount`); a pay-off carries every late instalment's.
                    - Outright and instalment purchases; NGN only.
                    - Pressing pay again within 30 minutes returns **the same open link** (200) instead \
                    of opening a second payment (201).
                    - **Landing back on the frontend proves nothing.** A payment counts only once \
                    HomeTrue has verified it with Paystack.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "A new payment link"),
            @ApiResponse(responseCode = "200", description = "The open link from a moment ago, handed back"),
            @ApiResponse(responseCode = "400", description = "`CURRENCY_NOT_SUPPORTED`, `PAYMENT_PLAN_NOT_SUPPORTED`, `INVALID_AMOUNT`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`TRANSACTION_NOT_FOUND` (including someone else's)", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`TRANSACTION_NOT_PAYABLE`: already past waiting for payment (or a plan paid in full); `OFFER_NOT_ACCEPTED`: accept the offer letter first; `INSTALMENT_AWAITING_VERIFICATION`: the last instalment isn't verified yet; `NOTHING_TO_PAY_OFF`: `balance=true` on a purchase with no active plan", content = @Content()),
            @ApiResponse(responseCode = "502", description = "`PAYMENT_PROVIDER_REFUSED`", content = @Content()),
            @ApiResponse(responseCode = "503", description = "`PAYMENT_PROVIDER_UNAVAILABLE`: try again shortly", content = @Content())
    })
    @PostMapping("/transactions/{transactionId}/payments")
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<PaymentDto> start(@PathVariable UUID transactionId,
                                            @RequestParam(defaultValue = "false") boolean balance) {
        PaymentService.Started started = service.start(transactionId, balance);
        return ResponseEntity.status(started.created() ? HttpStatus.CREATED : HttpStatus.OK).body(started.payment());
    }

    @Operation(summary = "Ask HomeTrue to confirm a payment with Paystack",
            description = """
                    Requires `client.checkout.reserve`; only your own payment (404 otherwise). Call it from \
                    the page Paystack returns the buyer to. **It claims nothing**: HomeTrue asks Paystack \
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

    @Operation(summary = "Your refund for a payment",
            description = """
                    Requires `client.checkout.reserve`; your own payment only (404 otherwise). `status`: pending, \
                    processing, needs-attention, processed, failed. **`needsAccount: true` means the money can't be \
                    returned until you give a bank account in your own name** — Paystack can't send a bank-transfer \
                    payment back on its own.""")
    @GetMapping("/payments/{reference}/refund")
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<BuyerRefundDto> refund(@PathVariable String reference) {
        return ResponseEntity.ok(refunds.forBuyer(reference));
    }

    @Operation(summary = "Banks you can receive a refund in")
    @GetMapping("/payments/banks")
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<List<BankDto>> refundBanks() {
        return ResponseEntity.ok(banks.banks());
    }

    @Operation(summary = "Give the account your refund should go to",
            description = """
                    Requires `client.checkout.reserve`; your own payment, only while its refund is \
                    `needs-attention`. Send the bank `code` and 10-digit number — **the bank supplies the name**, \
                    and HomeTrue checks it against your own. You can replace it until HomeTrue sends the refund.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saved; HomeTrue will send the refund to it"),
            @ApiResponse(responseCode = "400", description = "`UNKNOWN_BANK`, `ACCOUNT_NOT_RESOLVED`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`PAYMENT_NOT_FOUND`, `REFUND_NOT_FOUND`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`REFUND_NOT_AWAITING_ACCOUNT`", content = @Content())
    })
    @PostMapping("/payments/{reference}/refund-account")
    @PreAuthorize("hasAuthority('client.checkout.reserve')")
    public ResponseEntity<BuyerRefundDto> refundAccount(@PathVariable String reference,
                                                        @Valid @RequestBody RefundAccountRequest request) {
        return ResponseEntity.ok(refunds.submitAccount(reference, request.bankCode(), request.accountNumber()));
    }
}
