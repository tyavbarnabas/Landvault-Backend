package com.techcomfort.landvaultbackend.inventory;

import java.util.UUID;

/**
 * Whether a plot has ever been part of a purchase — a reservation or a
 * transaction, in any state. Declared here, implemented by {@code checkout}:
 * inventory needs the answer to decide whether a plot may be withdrawn
 * (IE-11), and checkout is the module that will one day tell inventory a plot
 * is sold, so the arrow must point checkout → inventory. The same
 * inverted-interface pattern as {@code ActorNameResolver} and
 * {@code EstateLabelResolver}. See AGENTS.md.
 */
public interface PlotHistoryProbe {

    boolean hasPurchaseHistory(UUID plotId);
}
