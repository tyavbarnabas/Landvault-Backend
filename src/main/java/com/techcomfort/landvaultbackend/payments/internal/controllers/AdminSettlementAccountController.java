package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.RejectSettlementAccountRequest;
import com.techcomfort.landvaultbackend.payments.dto.SettlementAccountReviewDto;
import com.techcomfort.landvaultbackend.payments.internal.enums.SettlementAccountStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.service.SettlementAccountService;
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

/** TR-1/TR-2, LandVault's side: approving where companies are paid. See {@link SettlementAccountService}. */
@RestController
@RequestMapping("/api/admin/settlement-accounts")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_ADMIN_PAYOUTS)
public class AdminSettlementAccountController {

    private final SettlementAccountService settlement;

    @Operation(summary = "Payout accounts to review",
            description = """
                    Requires `admin.payouts.manage`. `status` defaults to `pending`; `all` lists every submission. \\
                    Each carries two **warnings, never blocks**: `nameMatchesCompany` (the bank's name for the \\
                    account against the company's names) and `matchesOnboardingAccount` (against the account \\
                    declared at onboarding; null when none was).""")
    @GetMapping
    @PreAuthorize("hasAuthority('admin.payouts.manage')")
    public ResponseEntity<List<SettlementAccountReviewDto>> list(
            @RequestParam(defaultValue = "pending") String status) {
        return ResponseEntity.ok(settlement.forReview(filter(status)));
    }

    private static SettlementAccountStatus filter(String status) {
        if ("all".equalsIgnoreCase(status.trim())) {
            return null;
        }
        try {
            return SettlementAccountStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PaymentException.UnknownStatusFilter(status);
        }
    }

    @Operation(summary = "One payout account, with its warnings")
    @GetMapping("/{accountId}")
    @PreAuthorize("hasAuthority('admin.payouts.manage')")
    public ResponseEntity<SettlementAccountReviewDto> get(@PathVariable UUID accountId) {
        return ResponseEntity.ok(settlement.reviewOne(accountId));
    }

    @Operation(summary = "Approve a payout account",
            description = """
                    Requires `admin.payouts.manage`. Registers the account with Paystack as a transfer recipient \\
                    and makes it the company's payout account; the previous one becomes `superseded`. If Paystack \\
                    is unavailable nothing changes — try again.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Approved"),
            @ApiResponse(responseCode = "409", description = "`SETTLEMENT_ACCOUNT_NOT_PENDING`", content = @Content()),
            @ApiResponse(responseCode = "502", description = "`PAYMENT_PROVIDER_REFUSED` — nothing changed", content = @Content()),
            @ApiResponse(responseCode = "503", description = "`PAYMENT_PROVIDER_UNAVAILABLE` — nothing changed", content = @Content())
    })
    @PostMapping("/{accountId}/approve")
    @PreAuthorize("hasAuthority('admin.payouts.manage')")
    public ResponseEntity<SettlementAccountReviewDto> approve(@PathVariable UUID accountId) {
        return ResponseEntity.ok(settlement.approve(accountId));
    }

    @Operation(summary = "Reject a payout account",
            description = "Requires `admin.payouts.manage`; `reason` is required and shown to the company.")
    @PostMapping("/{accountId}/reject")
    @PreAuthorize("hasAuthority('admin.payouts.manage')")
    public ResponseEntity<SettlementAccountReviewDto> reject(@PathVariable UUID accountId,
                                                             @Valid @RequestBody RejectSettlementAccountRequest request) {
        return ResponseEntity.ok(settlement.reject(accountId, request.reason()));
    }
}
