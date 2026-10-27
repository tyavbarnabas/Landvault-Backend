package com.techcomfort.landvaultbackend.marketplace;

import com.techcomfort.landvaultbackend.common.FlexiblePlanCalculator;

import java.util.UUID;

/** A flexible plan an eligible estate currently offers, as published — the terms a purchase copies. */
public record OfferedFlexiblePlan(UUID planId, String name, FlexiblePlanCalculator.Terms terms) {
}
