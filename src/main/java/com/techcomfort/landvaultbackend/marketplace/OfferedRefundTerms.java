package com.techcomfort.landvaultbackend.marketplace;

import com.techcomfort.landvaultbackend.common.RefundAppliesTo;

import java.math.BigDecimal;

/**
 * An estate's refund deduction as published: what the developer keeps if a
 * buyer withdraws. Copied onto an instalment purchase as its escrow share (3c).
 */
public record OfferedRefundTerms(BigDecimal deductionPct, RefundAppliesTo appliesTo) {
}
