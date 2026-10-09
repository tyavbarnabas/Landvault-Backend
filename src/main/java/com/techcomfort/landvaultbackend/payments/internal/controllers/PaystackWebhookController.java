package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.internal.service.PaystackWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paystack calls this, not a person — public, so the signature is the only
 * proof of origin. The body is taken as raw bytes on purpose: the signature
 * covers the exact bytes, and parsing first would change them.
 */
@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PAYMENTS)
public class PaystackWebhookController {

    private final PaystackWebhookService webhooks;

    @Operation(summary = "Paystack's webhook (called by Paystack, not by clients)",
            description = """
                    Public — Paystack has no login. **The `x-paystack-signature` header is the only proof \\
                    of origin**: an HMAC-SHA512 of the raw body with the secret key. Anything unsigned or \\
                    mismatched is refused (401) and not stored. A signed call is stored exactly as received, \\
                    then a `charge.success` makes LandVault verify the payment with Paystack itself. Safe to \\
                    receive more than once.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Received (and acted on, where it applies)"),
            @ApiResponse(responseCode = "401", description = "`WEBHOOK_SIGNATURE_INVALID`: not from Paystack", content = @Content()),
            @ApiResponse(responseCode = "503", description = "Stored, but confirming failed — Paystack retries", content = @Content())
    })
    @SecurityRequirements
    @PostMapping("/paystack")
    public ResponseEntity<Void> paystack(@RequestBody byte[] rawBody,
                                         @RequestHeader(value = "x-paystack-signature", required = false) String signature) {
        webhooks.handle(rawBody, signature);
        return ResponseEntity.ok().build();
    }
}
