package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.PayoutDto;
import com.techcomfort.landvaultbackend.payments.internal.service.PayoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** TR-3: a company sees what it has been paid, so a failed payout is noticed. Read-only. */
@RestController
@RequestMapping("/api/portal/payouts")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_FINANCE)
public class PortalPayoutController {

    private final PayoutService payouts;

    @Operation(summary = "Your company's payouts",
            description = """
                    Requires `portal.payments.verify` (finance officer, Executive Director), company-wide. Every \\
                    payout LandVault has sent or tried to send you, newest first: each part, its amount, status \\
                    (`pending`, `success`, `failed`, `reversed`, …), Paystack's reason, and the account it went to \\
                    (last 4 digits). Read-only — LandVault sends and retries payouts.""")
    @GetMapping
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public ResponseEntity<List<PayoutDto>> list() {
        return ResponseEntity.ok(payouts.forCompany());
    }
}
