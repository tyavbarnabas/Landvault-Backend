package com.techcomfort.landvaultbackend.checkout;

/** How a finance decision on a purchase came out. Nothing is written unless it is {@link #DONE}. */
public enum FinanceDecision {
    DONE,
    /** Not this company's purchase, or outside this officer's branch — deliberately the same answer. */
    NOT_FOUND,
    /** Already decided, or never reached finance. */
    NOT_AWAITING_FINANCE,
    /** The plot is no longer held for this purchase, so it cannot be sold. */
    PLOT_NOT_RESERVED,
    /** A late payment: the purchase isn't abandoned (any more), so there is nothing to allocate. */
    NOT_ABANDONED,
    /** A late payment: someone else reserved or bought the plot meanwhile — only a refund is left. */
    PLOT_NOT_AVAILABLE
}
