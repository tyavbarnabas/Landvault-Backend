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
}
