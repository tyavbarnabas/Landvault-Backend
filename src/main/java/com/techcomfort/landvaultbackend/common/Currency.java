package com.techcomfort.landvaultbackend.common;

/**
 * The platform's exact currency set — {@code NGN}, {@code USD}, {@code GBP},
 * {@code EUR} — matching the frontend's {@code Currency} union in
 * {@code ~/landvault/src/data/mockData.ts} exactly. This is a deliberately
 * closed project enum, not the JDK's {@code java.util.Currency} (which
 * accepts all ~180 ISO-4217 currencies and isn't an enum, so it can't be
 * used with {@code @Enumerated(EnumType.STRING)}): a plot priced in a
 * currency the frontend can't render is a contract break on day one.
 * <p>
 * <b>This field means two different things depending on where it's used —
 * read the usage site before assuming either meaning:</b>
 * <ul>
 *   <li><b>On {@code User}</b> it is a <i>display preference</i> only — what
 *   the buyer wants figures shown in. It is never used to convert a stored
 *   monetary amount; a user can be an {@code NGN}-preference viewer of a
 *   {@code USD}-denominated plot.</li>
 *   <li><b>On any monetary record</b> (a plot price, a transaction, a
 *   payment) it is the currency the amount is <i>genuinely denominated
 *   in</i>, and must never be dropped or silently converted. The frontend
 *   already enforces this — {@code groupPlotsByCurrency} refuses to sum
 *   {@code NGN} and {@code USD} figures together — and AGENTS.md requires
 *   the backend to preserve the same invariant.</li>
 * </ul>
 */
public enum Currency {

    NGN,
    USD,
    GBP,
    EUR
}
