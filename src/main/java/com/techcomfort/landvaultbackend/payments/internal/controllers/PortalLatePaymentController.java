package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.FinanceDecisionDto;
import com.techcomfort.landvaultbackend.payments.dto.LatePaymentDto;
import com.techcomfort.landvaultbackend.payments.dto.LatePaymentRefundRequest;
import com.techcomfort.landvaultbackend.payments.internal.service.LatePaymentService;
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

/** Late money: the developer's finance allocates or refunds a payment that arrived after its purchase ended. */
@RestController
@RequestMapping("/api/portal/finance/late-payments")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_FINANCE)
public class PortalLatePaymentController {

    private final LatePaymentService latePayments;

    @Operation(summary = "Payments that arrived after their purchase ended",
            description = """
                    Requires `portal.payments.verify`. Your company's abandoned purchases that were paid after \\
                    all. `plotAvailable` false means someone else has the plot — refund is the only choice.""")
    @GetMapping
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public ResponseEntity<List<LatePaymentDto>> list() {
        return ResponseEntity.ok(latePayments.list());
    }

    @Operation(summary = "Allocate the plot to the late payer",
            description = """
                    Requires `portal.payments.verify`. Needs Paystack's confirmed amount to equal the agreed price. \\
                    **Together or not at all**: the plot becomes sold and the purchase `verified` — only if the \\
                    plot is still for sale. The buyer is emailed. The sale then appears for payout like any other.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Allocated"),
            @ApiResponse(responseCode = "404", description = "`LATE_PAYMENT_NOT_FOUND`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`PLOT_NO_LONGER_AVAILABLE`, `PAYMENT_NOT_CONFIRMED` — nothing changed", content = @Content())
    })
    @PostMapping("/{transactionId}/allocate")
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public ResponseEntity<FinanceDecisionDto> allocate(@PathVariable UUID transactionId) {
        return ResponseEntity.ok(latePayments.allocate(transactionId));
    }

    @Operation(summary = "Refund the late payer",
            description = """
                    Requires `portal.payments.verify`; `reason` is required and the buyer sees it. The payment \\
                    joins LandVault's refunds-due list; LandVault returns the full amount. The plot is untouched.""")
    @PostMapping("/{transactionId}/refund")
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public ResponseEntity<FinanceDecisionDto> refund(@PathVariable UUID transactionId,
                                                     @Valid @RequestBody LatePaymentRefundRequest request) {
        return ResponseEntity.ok(latePayments.refund(transactionId, request.reason()));
    }
}
