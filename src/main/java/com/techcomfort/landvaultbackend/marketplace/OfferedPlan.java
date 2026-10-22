package com.techcomfort.landvaultbackend.marketplace;

import com.techcomfort.landvaultbackend.common.InstalmentPlanCalculator;

import java.util.UUID;

/** A plan an eligible estate currently offers, as published — the terms a purchase is worked out from. */
public record OfferedPlan(UUID planId, String name, InstalmentPlanCalculator.Terms terms) {
}
