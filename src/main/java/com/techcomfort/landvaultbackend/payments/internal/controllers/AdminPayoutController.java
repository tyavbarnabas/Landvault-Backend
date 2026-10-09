package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.PayoutDto;
import com.techcomfort.landvaultbackend.payments.dto.PayoutOtpRequest;
import com.techcomfort.landvaultbackend.payments.dto.ReadyPayoutDto;
import com.techcomfort.landvaultbackend.payments.dto.SendPayoutRequest;
import com.techcomfort.landvaultbackend.payments.internal.enums.PayoutStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.service.PayoutService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** TR-1, step 8b: a Super Admin pays companies for verified sales. See {@link PayoutService}. */
@RestController
@RequestMapping("/api/admin/payouts")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_ADMIN_PAYOUTS)
public class AdminPayoutController {

    private final PayoutService payouts;

    @Operation(summary = "Verified sales waiting to be paid out",
            description = """
                    Requires `admin.payouts.manage`. Each sale shows the amount owed (the agreed price), the \\
                    approved account it would go to, and `blockers` — empty when it can be paid now, otherwise \\
                    `NO_APPROVED_PAYOUT_ACCOUNT` or `COMPANY_NOT_ACTIVE`. `lastAttempt` shows a previous failed, \\
                    reversed or cancelled payout.""")
    @GetMapping("/ready")
    @PreAuthorize("hasAuthority('admin.payouts.manage')")
    public ResponseEntity<List<ReadyPayoutDto>> ready() {
        return ResponseEntity.ok(payouts.ready());
    }

    @Operation(summary = "Payout attempts", description = "Requires `admin.payouts.manage`. `status` filters; `all` (default) lists every attempt, newest first.")
    @GetMapping
    @PreAuthorize("hasAuthority('admin.payouts.manage')")
    public ResponseEntity<List<PayoutDto>> list(@RequestParam(defaultValue = "all") String status) {
        return ResponseEntity.ok(payouts.list(filter(status)));
    }

    @Operation(summary = "Pay out a verified sale",
            description = """
                    Requires `admin.payouts.manage` **and two-factor authentication on your own account**. The \\
                    amount and the account come from the sale and the company's approved account, never the \\
                    request. Paystack usually replies `awaiting_otp`: it has sent a code to the Paystack account \\
                    owner — enter it with `/{id}/otp`. A sale that is `sending` (a lost reply) can be sent again \\
                    safely: the same reference is reused and Paystack is asked first.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Started — usually `awaiting_otp`"),
            @ApiResponse(responseCode = "403", description = "`TWO_FACTOR_REQUIRED`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`SALE_NOT_PAYABLE` — not a verified sale", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`PAYOUT_IN_PROGRESS`, `COMPANY_NOT_ACTIVE`, `NO_APPROVED_PAYOUT_ACCOUNT`", content = @Content()),
            @ApiResponse(responseCode = "502", description = "`PAYMENT_PROVIDER_REFUSED` — Paystack said no (e.g. balance too low); recorded as `failed`", content = @Content()),
            @ApiResponse(responseCode = "503", description = "`PAYOUT_OUTCOME_UNKNOWN` — kept as `sending`; send again, it's safe", content = @Content())
    })
    @PostMapping
    @PreAuthorize("hasAuthority('admin.payouts.manage')")
    public ResponseEntity<PayoutDto> send(@Valid @RequestBody SendPayoutRequest request) {
        return ResponseEntity.ok(payouts.send(request.transactionId()));
    }

    @Operation(summary = "Enter the code Paystack sent",
            description = """
                    Requires `admin.payouts.manage` and your own 2FA. The code goes straight to Paystack and is \\
                    never stored or logged. A wrong code is `OTP_REJECTED` and the payout stays `awaiting_otp`.""")
    @PostMapping("/{payoutId}/otp")
    @PreAuthorize("hasAuthority('admin.payouts.manage')")
    public ResponseEntity<PayoutDto> otp(@PathVariable UUID payoutId, @Valid @RequestBody PayoutOtpRequest request) {
        return ResponseEntity.ok(payouts.submitOtp(payoutId, request.otp()));
    }

    @Operation(summary = "Ask Paystack to send the code again")
    @PostMapping("/{payoutId}/resend-otp")
    @PreAuthorize("hasAuthority('admin.payouts.manage')")
    public ResponseEntity<PayoutDto> resendOtp(@PathVariable UUID payoutId) {
        return ResponseEntity.ok(payouts.resendOtp(payoutId));
    }

    @Operation(summary = "Cancel a payout still waiting for its code",
            description = "Nothing was sent — Paystack moves money only once the code is entered. The sale can then be paid again.")
    @PostMapping("/{payoutId}/cancel")
    @PreAuthorize("hasAuthority('admin.payouts.manage')")
    public ResponseEntity<PayoutDto> cancel(@PathVariable UUID payoutId) {
        return ResponseEntity.ok(payouts.cancel(payoutId));
    }

    private static PayoutStatus filter(String status) {
        if ("all".equalsIgnoreCase(status.trim())) {
            return null;
        }
        try {
            return PayoutStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PaymentException.UnknownStatusFilter(status);
        }
    }
}
