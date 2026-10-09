package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.RefundDto;
import com.techcomfort.landvaultbackend.payments.dto.RefundDueDto;
import com.techcomfort.landvaultbackend.payments.dto.StartRefundRequest;
import com.techcomfort.landvaultbackend.payments.internal.enums.RefundStatus;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.service.RefundService;
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

/** Refunds, LandVault's side: a Super Admin returns money owed to buyers. See {@link RefundService}. */
@RestController
@RequestMapping("/api/admin/refunds")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_ADMIN_PAYOUTS)
public class AdminRefundController {

    private final RefundService refunds;

    @Operation(summary = "Payments owed back to buyers",
            description = """
                    Requires `admin.payouts.manage`. Payments marked as owed back (e.g. finance rejected the \\
                    purchase) with no refund under way. `amount` is what will be returned: the full amount paid.""")
    @GetMapping("/due")
    @PreAuthorize("hasAuthority('admin.payouts.manage')")
    public ResponseEntity<List<RefundDueDto>> due() {
        return ResponseEntity.ok(refunds.due());
    }

    @Operation(summary = "Refunds", description = "Requires `admin.payouts.manage`. `status` filters (e.g. `needs-attention`); `all` (default) lists every refund.")
    @GetMapping
    @PreAuthorize("hasAuthority('admin.payouts.manage')")
    public ResponseEntity<List<RefundDto>> list(@RequestParam(defaultValue = "all") String status) {
        return ResponseEntity.ok(refunds.list(filter(status)));
    }

    @Operation(summary = "One refund, with the buyer's account and name check")
    @GetMapping("/{refundId}")
    @PreAuthorize("hasAuthority('admin.payouts.manage')")
    public ResponseEntity<RefundDto> get(@PathVariable UUID refundId) {
        return ResponseEntity.ok(refunds.get(refundId));
    }

    @Operation(summary = "Refund a payment",
            description = """
                    Requires `admin.payouts.manage` and **your own 2FA**. Returns the full amount paid, once. A \\
                    bank-transfer payment usually comes back `needs-attention`: the buyer is asked for an account, \\
                    then send it with `/{id}/send-to-account`. A refund kept as `sending` (no answer) may be sent \\
                    again — Paystack never refunds more than was paid.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Requested — see `status`"),
            @ApiResponse(responseCode = "403", description = "`TWO_FACTOR_REQUIRED`", content = @Content()),
            @ApiResponse(responseCode = "404", description = "`REFUND_NOT_DUE`", content = @Content()),
            @ApiResponse(responseCode = "409", description = "`REFUND_IN_PROGRESS`", content = @Content()),
            @ApiResponse(responseCode = "502", description = "`PAYMENT_PROVIDER_REFUSED` — recorded as failed; may be retried", content = @Content()),
            @ApiResponse(responseCode = "503", description = "`REFUND_OUTCOME_UNKNOWN` — kept as sending", content = @Content())
    })
    @PostMapping
    @PreAuthorize("hasAuthority('admin.payouts.manage')")
    public ResponseEntity<RefundDto> start(@Valid @RequestBody StartRefundRequest request) {
        return ResponseEntity.ok(refunds.start(request.paymentReference().trim()));
    }

    @Operation(summary = "Send a refund to the account the buyer gave",
            description = """
                    Requires `admin.payouts.manage` and your own 2FA. Only for `needs-attention` once the buyer has \\
                    given an account. Check `accountNameMatchesBuyer` first — a warning, not a block.""")
    @PostMapping("/{refundId}/send-to-account")
    @PreAuthorize("hasAuthority('admin.payouts.manage')")
    public ResponseEntity<RefundDto> sendToAccount(@PathVariable UUID refundId) {
        return ResponseEntity.ok(refunds.sendToAccount(refundId));
    }

    private static RefundStatus filter(String status) {
        if ("all".equalsIgnoreCase(status.trim())) {
            return null;
        }
        try {
            return RefundStatus.valueOf(status.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new PaymentException.UnknownStatusFilter(status);
        }
    }
}
