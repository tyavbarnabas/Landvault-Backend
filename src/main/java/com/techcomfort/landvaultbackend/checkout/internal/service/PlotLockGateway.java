package com.techcomfort.landvaultbackend.checkout.internal.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The atomic acquire and release of a plot, through the {@code SECURITY
 * DEFINER} functions of changeset 054.
 * <p>
 * <strong>Do not "simplify" either call into a repository update.</strong> It
 * compiles, it passes every test on a superuser connection, and it fails
 * silently everywhere row-level security is real. A buyer's session carries
 * no tenant scope and no platform scope, so changeset 044's policies make
 * {@code plots} return zero rows to a read <em>and</em> zero affected rows to
 * an update — with no error either time.
 * <p>
 * That matters more here than in the two earlier RLS escapes, because zero
 * affected rows is exactly how a compare-and-swap reports <em>losing a
 * race</em>. The two failures are indistinguishable from inside the
 * application: every buyer would simply be told the plot was taken, forever.
 * <p>
 * The atomicity itself is Postgres's, not this class's: the function locks
 * the row with {@code SELECT … FOR UPDATE} and re-checks its status, so of
 * two concurrent callers exactly one proceeds. A read-then-write in Java
 * could not give that guarantee, and neither could a Redis TTL, which cannot
 * flip {@code plots.status} at all.
 */
@Component
@RequiredArgsConstructor
public class PlotLockGateway {

    private final EntityManager entityManager;

    /**
     * Takes the hold, or returns empty when the plot is not available —
     * already held or sold, deleted, non-existent, or on an estate that is
     * no longer publicly eligible.
     */
    @SuppressWarnings("unchecked")
    public Optional<AcquiredPlot> acquire(UUID plotId, String actor) {
        List<Object[]> rows = entityManager
                .createNativeQuery("""
                        SELECT previous_status, estate_id, seller_tenant_id, branch_id,
                               price_tier_id, tier_price, tier_currency, is_corner, corner_premium_pct
                        FROM landvault_reserve_plot(:plotId, :actor)
                        """)
                .setParameter("plotId", plotId)
                .setParameter("actor", actor)
                .getResultList();

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.getFirst();
        return Optional.of(new AcquiredPlot(
                (String) row[0],
                (UUID) row[1],
                (UUID) row[2],
                (UUID) row[3],
                (UUID) row[4],
                (BigDecimal) row[5],
                (String) row[6],
                (Boolean) row[7],
                (BigDecimal) row[8]));
    }

    /**
     * Returns the plot to the pool. False when it was not held — the
     * sweeper got there first, say — which makes cancellation and expiry
     * idempotent instead of racing.
     * <p>
     * {@code restoreStatus} is validated inside the function against the two
     * available states, so this path can never be used to mark a plot sold.
     */
    public boolean release(UUID plotId, String restoreStatus, String actor) {
        Object result = entityManager
                .createNativeQuery(
                        "SELECT landvault_release_plot(:plotId, :restoreStatus, :actor)")
                .setParameter("plotId", plotId)
                .setParameter("restoreStatus", restoreStatus)
                .setParameter("actor", actor)
                .getSingleResult();
        return Boolean.TRUE.equals(result);
    }

    /**
     * What the acquire returns: enough to price the hold, taken from the
     * same locked snapshot that granted it, so the price cannot be read
     * from a tier that changed in between.
     */
    public record AcquiredPlot(
            String previousStatus,
            UUID estateId,
            UUID sellerTenantId,
            UUID branchId,
            UUID priceTierId,
            BigDecimal tierPrice,
            String tierCurrency,
            Boolean isCorner,
            BigDecimal cornerPremiumPct
    ) {
    }
}
