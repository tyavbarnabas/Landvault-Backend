package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why finance rejected a payment. Recorded in the audit log and on the payment's refund flag. */
public record RejectPaymentRequest(
        @Schema(example = "Payment was charged back by the buyer's bank.") @NotBlank @Size(max = 500) String reason
) {
}
