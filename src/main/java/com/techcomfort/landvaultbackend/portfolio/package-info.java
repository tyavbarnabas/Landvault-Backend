/**
 * A buyer's portfolio: the plots they own, across every company they bought
 * from. See AGENTS.md, "Portfolio".
 * <p>
 * An owned plot is written when finance verifies a sale, by a synchronous
 * listener on checkout's {@code PlotAllocated}, inside the allocating
 * transaction. Before that, a paid purchase is the checkout's and is shown
 * here as pending verification. Buyers have no tenant scope, so the plot and
 * estate labels come through {@code landvault_buyer_plot_details}, a narrow
 * {@code SECURITY DEFINER} function (changeset 088).
 * <p>
 * Depends on {@code checkout} (the event, pending purchases, and the payment
 * summaries the payments module provides through checkout's interface),
 * {@code audit} and {@code common}. Nothing depends on it.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Portfolio"
)
package com.techcomfort.landvaultbackend.portfolio;
