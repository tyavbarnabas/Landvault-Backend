package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.internal.service.PaymentSweepService;
import com.techcomfort.landvaultbackend.payments.internal.service.PaymentSweeper;
import com.techcomfort.landvaultbackend.payments.internal.service.PayoutOutcomeService;
import com.techcomfort.landvaultbackend.payments.internal.service.PayoutSweeper;
import com.techcomfort.landvaultbackend.payments.internal.service.RefundOutcomeService;
import com.techcomfort.landvaultbackend.payments.internal.service.RefundSweeper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Development only: runs the payment, payout and refund sweeps now instead of waiting
 * for their schedules, so they can be tried by hand. Not present outside the dev
 * profile at all.
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev/payments")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PAYMENTS)
public class DevPaymentSweepController {

    private final PaymentSweeper sweeper;
    private final PayoutSweeper payoutSweeper;
    private final RefundSweeper refundSweeper;

    @Operation(summary = "DEV ONLY: run the payment sweep now",
            description = "Super Admin (`admin.tenants.manage`). Settles purchases past their hold plus the grace period "
                    + "and returns how many ended each way. Exists only under the dev profile.")
    @PostMapping("/sweep")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<Map<PaymentSweepService.Outcome, Integer>> sweep() {
        return ResponseEntity.ok(sweeper.sweep());
    }

    @Operation(summary = "DEV ONLY: run the payout sweep now",
            description = "Super Admin (`admin.tenants.manage`). Asks Paystack about payouts stuck in `sending` or "
                    + "`pending` past their thresholds and records its answer. Exists only under the dev profile.")
    @PostMapping("/payout-sweep")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<Map<PayoutOutcomeService.Outcome, Integer>> payoutSweep() {
        return ResponseEntity.ok(payoutSweeper.sweep());
    }

    @Operation(summary = "DEV ONLY: run the refund sweep now",
            description = "Super Admin (`admin.tenants.manage`). Asks Paystack about refunds it accepted but hasn't "
                    + "finished, past the threshold. Exists only under the dev profile.")
    @PostMapping("/refund-sweep")
    @PreAuthorize("hasAuthority('admin.tenants.manage')")
    public ResponseEntity<Map<RefundOutcomeService.Outcome, Integer>> refundSweep() {
        return ResponseEntity.ok(refundSweeper.sweep());
    }
}
