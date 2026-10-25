package com.techcomfort.landvaultbackend.portfolio.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/** What is coming back on a withdrawn or revoked plan, worked out on every read. */
@Schema(name = "PlanRefund")
public record PlanRefundDto(
        @Schema(description = "Everything owed back so far. It can grow if finance later rejects a payment that "
                + "was waiting for them when the plan ended.") BigDecimal amount,
        @Schema(description = "What Paystack reports as back with the buyer.") BigDecimal refunded,
        String currency,
        @Schema(description = "`none` (nothing is owed back — the developer's declared share covered it all), "
                + "`pending` (nothing back yet), `partly_refunded` or `refunded` (all of `amount` is back).")
        String status,
        @Schema(description = "True while a bank-transfer payment can't be returned until the buyer gives a bank "
                + "account (`POST /api/payments/{reference}/refund-account`).") boolean needsYourAccount
) {
}
