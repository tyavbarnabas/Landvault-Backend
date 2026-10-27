package com.techcomfort.landvaultbackend.payments.dto;

import com.techcomfort.landvaultbackend.checkout.FlexibleStanding;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/** Flexible contribution, phase 4: one of a developer's running flexible plans, and where it stands today. */
@Schema(name = "PortalFlexiblePlan")
public record PortalFlexiblePlanDto(
        UUID transactionId,
        String transactionReference,
        UUID estateId,
        String estateName,
        UUID plotId,
        String plotLabel,
        String buyerEmail,
        String planName,
        String currency,
        @Schema(description = "What finishing the plan costs.") BigDecimal planTotal,
        @Schema(description = "Paid and left, the last payment, when it counts as quiet, the date to finish by, "
                + "whether notice may be given today (`noticeReason`), and any notice running. Give notice through "
                + "`POST /api/portal/plans/{transactionId}/revocation`.") FlexibleStanding standing
) {
}
