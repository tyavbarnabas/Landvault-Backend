package com.techcomfort.landvaultbackend.payments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why the late payment is being returned rather than allocated — the buyer is told. */
public record LatePaymentRefundRequest(@NotBlank @Size(max = 500) String reason) {
}
