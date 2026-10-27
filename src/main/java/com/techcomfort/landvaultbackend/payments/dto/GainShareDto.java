package com.techcomfort.landvaultbackend.payments.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Flexible contribution, phase 5: a buyer's claim on the gain of the plot's
 * next completed sale, after they left their flexible plan or it was ended.
 */
@Schema(name = "GainShare")
public record GainShareDto(
        UUID id,
        @Schema(description = "The purchase you left (or that was ended).") UUID purchaseId,
        @Schema(description = "`open` (waiting for the plot's next completed sale through HomeTrue), `owed` (it sold "
                + "for more — see `amount`) or `no_gain` (it sold for no more, so nothing is owed).") String status,
        @Schema(description = "Your agreed price: the gain is measured from it.") BigDecimal agreedPrice,
        String currency,
        @Schema(description = "Your share of any gain, as offered when you bought.") BigDecimal yourSharePct,
        Instant openedAt,
        @Schema(nullable = true) BigDecimal salePrice,
        @Schema(nullable = true, description = "The sale price less your agreed price.") BigDecimal gain,
        @Schema(nullable = true, description = "What you are owed.") BigDecimal amount,
        @Schema(nullable = true) Instant settledAt,
        @Schema(description = "What has reached your account so far.") BigDecimal paid,
        @Schema(description = "True while an owed share waits for your bank account.") boolean accountNeeded,
        @Schema(nullable = true) Account account
) {

    @Schema(name = "GainShareAccount")
    public record Account(String bankName, String accountLast4,
                          @Schema(description = "The name the bank has on the account.") String accountName) {
    }
}
