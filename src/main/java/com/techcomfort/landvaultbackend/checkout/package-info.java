/**
 * Reservations and pending purchases — where browsing becomes buying.
 * <p>
 * The one correctness property this module exists for: <strong>two buyers can
 * never both hold the same plot</strong>. Double allocation at the point of
 * sale is the exact fraud LandVault is pitched on preventing, so the acquire
 * is a database-level compare-and-swap inside a {@code SECURITY DEFINER}
 * function (changeset 054), never a read-then-write in Java.
 * <p>
 * That function is also the third deliberate row-level-security escape in
 * this codebase, and the first that writes: a buyer has no tenant scope, so
 * {@code plots} is neither readable nor writable to them — and an RLS-blocked
 * update reports zero affected rows, which is indistinguishable from losing
 * the race. See {@code PlotLockGateway}.
 * <p>
 * Depends on {@code marketplace} (the plot's estate and its publication
 * eligibility, both through owner-privileged views a buyer can actually
 * read), {@code kyc} (a buyer must be verified before holding anything),
 * {@code audit} and {@code common}. It does not depend on {@code inventory}:
 * everything it needs about a plot comes back from the acquire itself, taken
 * from the same locked snapshot that granted the hold.
 * <p>
 * A plot is allocated only when finance verifies the sale
 * ({@code CheckoutApi.verifyAndAllocate}), which publishes {@code PlotAllocated}
 * for the portfolio to record ownership in the same transaction.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Checkout"
)
package com.techcomfort.landvaultbackend.checkout;
