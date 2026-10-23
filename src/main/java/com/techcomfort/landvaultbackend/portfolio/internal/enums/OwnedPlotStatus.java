package com.techcomfort.landvaultbackend.portfolio.internal.enums;

import java.util.Locale;

/**
 * The frontend's {@code PlotAccountStatus}, all nine. Only {@link #COMPLETED}
 * is written today; {@link #RESERVED} and {@link #PENDING_VERIFICATION} are
 * shown from the checkout and never stored; the others belong to flows that
 * don't exist yet (documents, installments, upgrades, resale) and must not be
 * faked. {@link #TRANSFERRED} and {@link #SUPERSEDED} are different facts —
 * another buyer now owns it, versus this buyer's equity moved to another plot.
 */
public enum OwnedPlotStatus {
    RESERVED,
    PENDING_VERIFICATION,
    ALLOCATED,
    INSTALLMENT_ACTIVE,
    COMPLETED,
    IN_ARREARS,
    UPGRADE_PENDING,
    TRANSFERRED,
    SUPERSEDED,
    /** Phase 5a: the buyer withdrew from their plan; the plot went back on sale. */
    WITHDRAWN,
    /** Phase 5b: the developer revoked the plan after the notice ran out; the plot went back on sale. */
    REVOKED;

    public String getValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Still the buyer's: retired records stay in the ledger but never count in totals (PF-2). */
    public boolean activelyOwned() {
        return this != TRANSFERRED && this != SUPERSEDED && this != WITHDRAWN && this != REVOKED;
    }
}
