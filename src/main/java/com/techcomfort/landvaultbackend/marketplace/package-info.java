/**
 * The public marketplace: the first surface in this system an anonymous
 * request can read.
 * <p>
 * Reads nothing but the {@code marketplace_*} views (changeset 049), never a
 * tenant table, so a column this module never selected cannot leak (MP-3).
 * The five publication conditions live in those views, evaluated at read
 * time: publication is the developer's intent, eligibility is current state.
 * <p>
 * Public surface: {@link com.techcomfort.landvaultbackend.marketplace.MarketplaceApi},
 * used by {@code inventory}'s publish endpoint so the publish check and the
 * public read are answered by the same SQL and cannot disagree, plus the DTOs
 * in {@code marketplace.dto}. Depends on {@code common} only.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Marketplace"
)
package com.techcomfort.landvaultbackend.marketplace;
