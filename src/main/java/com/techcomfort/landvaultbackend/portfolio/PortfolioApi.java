package com.techcomfort.landvaultbackend.portfolio;

import java.util.Optional;
import java.util.UUID;

/** What other modules may ask the portfolio module. */
public interface PortfolioApi {

    /**
     * The purchase behind one of the buyer's portfolio entries, by owned-plot
     * id or transaction id (the same lookup as {@code GET /api/portfolio/plots/{id}}).
     * Empty when it isn't theirs.
     */
    Optional<UUID> transactionIdOf(UUID buyerUserId, UUID ownedPlotOrTransactionId);

    /**
     * Sell my plot: the estate and plot names of one of the buyer's plots, by
     * inventory plot id, read through {@code landvault_buyer_plot_details} so
     * it works with no tenant scope. Empty when it isn't theirs.
     */
    Optional<PlotLabel> plotLabelOf(UUID buyerUserId, UUID plotId);
}
