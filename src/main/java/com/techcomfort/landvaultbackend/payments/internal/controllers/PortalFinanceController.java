package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.FinanceDecisionDto;
import com.techcomfort.landvaultbackend.payments.dto.FinanceQueueItemDto;
import com.techcomfort.landvaultbackend.payments.dto.RejectPaymentRequest;
import com.techcomfort.landvaultbackend.payments.internal.service.FinanceVerificationService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** FV-2, FV-3: a developer's finance staff verify payments. See {@link FinanceVerificationService}. */
@RestController
@RequestMapping("/api/portal/finance/transactions")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_FINANCE)
public class PortalFinanceController {

    private final FinanceVerificationService finance;

    @Operation(summary = "Purchases waiting for finance",
            description = """
                    Requires `portal.payments.verify` (finance officer, Executive Director). Your own company's \\
                    sales only; a branch-scoped officer sees only their branch's. Each shows the **agreed price \\
                    beside what Paystack recorded** (amount, channel, time, card last 4) and `amountsMatch`.""")
    @GetMapping
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public ResponseEntity<List<FinanceQueueItemDto>> queue() {
        return ResponseEntity.ok(finance.queue());
    }

    @Operation(summary = "Verify a payment and allocate the plot",
            description = """
                    Requires `portal.payments.verify`. Needs a payment confirmed by Paystack for exactly the agreed \\
                    amount. Then, **together or not at all**: the plot becomes sold, the transaction `verified`, the \\
                    reservation `converted`. If any part can't happen, nothing changes.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verified; the plot is sold"),
            @ApiResponse(responseCode = "404", description = "`TRANSACTION_NOT_FOUND` (another company's, or another branch's)", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`TRANSACTION_NOT_AWAITING_FINANCE`, `PAYMENT_NOT_CONFIRMED`, `PLOT_NOT_RESERVED` — nothing was changed", content = @Content())
    })
    @PostMapping("/{transactionId}/verify")
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public ResponseEntity<FinanceDecisionDto> verify(@PathVariable UUID transactionId) {
        return ResponseEntity.ok(finance.verify(transactionId));
    }

    @Operation(summary = "Reject a payment",
            description = """
                    Requires `portal.payments.verify`; `reason` is required. The transaction becomes `rejected`, the \\
                    plot goes back on sale, and the payment is flagged for refund (refunds are handled by hand for now).""")
    @PostMapping("/{transactionId}/reject")
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public ResponseEntity<FinanceDecisionDto> reject(@PathVariable UUID transactionId,
                                                     @Valid @RequestBody RejectPaymentRequest request) {
        return ResponseEntity.ok(finance.reject(transactionId, request.reason()));
    }
}
