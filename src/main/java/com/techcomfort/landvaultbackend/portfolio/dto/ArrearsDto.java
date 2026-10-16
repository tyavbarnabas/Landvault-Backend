package com.techcomfort.landvaultbackend.portfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The frontend's {@code ArrearsInfo}. Never populated yet: nothing can fall into arrears until installments exist. */
public record ArrearsDto(
        BigDecimal amountOwed,
        LocalDate overdueSinceDate,
        int gracePeriodDays,
        LocalDate graceEndsDate,
        BigDecimal penaltyAmount,
        String penaltyReason
) {
}
