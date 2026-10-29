/**
 * Sell my plot: a buyer asks HomeTrue to sell a plot they own or are paying
 * for. See AGENTS.md, "Sell my plot", and the stories in
 * {@code ~/Documents/hometrue-sell-my-plot-user-stories.md}.
 * <p>
 * Phase 1 is read only: whether a plot may be listed, whether a sale would be
 * an early one (before the profit thresholds), and the split at a price.
 * Depends on {@code checkout} (the purchase, its schedule or flexible terms,
 * and the payments module's standings through checkout's interface),
 * {@code portfolio}, {@code kyc}, {@code marketplace} (the developer's current
 * price and terms, through the public projection) and {@code common}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Resale"
)
package com.techcomfort.landvaultbackend.resale;
