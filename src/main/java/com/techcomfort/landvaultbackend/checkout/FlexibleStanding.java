package com.techcomfort.landvaultbackend.checkout;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Flexible contribution, phase 4: where a running flexible plan stands today —
 * what's paid and left, when it would count as quiet, its date to finish by,
 * whether the developer may give notice now, and any notice running. Worked
 * out on every read (except the notice record), never stored. Nothing on a
 * flexible plan is ever "late".
 */
@Schema(name = "FlexibleStanding")
public record FlexibleStanding(
        @Schema(description = "What finance has verified.") BigDecimal paid,
        @Schema(description = "What is left of the plan total.") BigDecimal remaining,
        @Schema(nullable = true, description = "A payment Paystack confirmed that finance hasn't verified yet.")
        BigDecimal awaitingFinance,
        @Schema(description = "The day (Lagos) of the last payment Paystack confirmed — finance's speed never "
                + "makes a buyer look inactive.") LocalDate lastPaymentOn,
        @Schema(description = "From this day, with no payment since, the plan counts as quiet and the developer "
                + "may give notice.") LocalDate quietFrom,
        @Schema(nullable = true, description = "The date to finish by; null when the plan has none.")
        LocalDate finishBy,
        @Schema(nullable = true, description = "Why notice may be given today: `inactivity` or `finish_by` (past "
                + "the date to finish by). Null when it may not.") String noticeReason,
        @Schema(nullable = true, description = "The notice running, if one has been given.") Notice notice
) {

    /** Notice may be given today. */
    public boolean noticeAllowedNow() {
        return noticeReason != null;
    }

    @Schema(name = "FlexibleNotice")
    public record Notice(
            @Schema(description = "`inactivity` (a verified payment ends it) or `finish_by` (only finishing the plan "
                    + "ends it).") String trigger,
            @Schema(description = "The developer's reason, as the buyer was told it.") String reason,
            Instant givenAt,
            @Schema(description = "The last day (Lagos) the buyer may act.") LocalDate deadline,
            @Schema(description = "From this day the developer may end the plan, if the reason still holds.")
            LocalDate confirmableFrom
    ) {
    }
}
