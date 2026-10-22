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
    PLOT_NOT_AVAILABLE,
    /** The estate's allocation letter isn't approved (or lapsed), so the sale can't be allocated yet. */
    LETTER_NOT_APPROVED,
    /** Late money on an instalment purchase: allocating a late deposit isn't supported yet — refund it. */
    LATE_DEPOSIT_NOT_SUPPORTED,
    /** 3b: the purchase isn't an instalment plan in progress. */
    NOT_ON_PLAN,
    /** 3b: the held plot isn't ON_PLAN any more, so it can't be sold. */
    PLOT_NOT_ON_PLAN
}
