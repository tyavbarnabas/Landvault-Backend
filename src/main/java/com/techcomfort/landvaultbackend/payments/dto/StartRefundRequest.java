package com.techcomfort.landvaultbackend.payments.dto;

import jakarta.validation.constraints.NotBlank;

/** Which payment to refund. The amount is never in the request — it is the full amount paid. */
public record StartRefundRequest(@NotBlank String paymentReference) {
}
