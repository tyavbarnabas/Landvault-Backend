package com.techcomfort.landvaultbackend.inventory.internal.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The status transitions a developer may make (IE-7/IE-8), each as
 * <strong>one statement</strong> whose {@code WHERE} names the statuses it may
 * move from. That is the concurrency guarantee, the same compare-and-swap the
 * reservation uses: a plot a buyer is reserving at this instant holds its row
 * lock, so this waits, re-checks the status against the committed row, and
 * skips it if it is now {@code RESERVED}. Read-then-write would overwrite the
 * buyer's hold.
 * <p>
 * Never moves a plot to {@code RESERVED} or {@code SOLD}. Returning a plot to
 * the market resets a land plot's size from its tier: a tier resize only
 * reaches available plots, so a withheld one may be carrying the old size
 * (the same reason changeset 060 resyncs on release).
 * <p>
 * Runs under the caller's row-level security, which is what confines it to
 * their own tenant and branch; a plot id from elsewhere simply doesn't match.
 */
@Component
public class PlotStatusWriter {

    public enum Target {
        WITHHOLD("p.status IN ('AVAILABLE_DEV', 'AVAILABLE_INV')",
                "withheld_from_status = p.status, status = 'WITHHELD'"),
        RESTORE("p.status = 'WITHHELD' AND p.withheld_from_status IS NOT NULL",
                "status = p.withheld_from_status, withheld_from_status = NULL, " + Target.RESYNC),
        TO_AVAILABLE_DEV("p.status IN ('AVAILABLE_INV', 'WITHHELD')",
                "status = 'AVAILABLE_DEV', withheld_from_status = NULL, " + Target.RESYNC),
        TO_AVAILABLE_INV("p.status IN ('AVAILABLE_DEV', 'WITHHELD')",
                "status = 'AVAILABLE_INV', withheld_from_status = NULL, " + Target.RESYNC);

        private static final String RESYNC =
                "nominal_size_sqm = CASE WHEN t.tier_type = 'LAND_SIZE' THEN t.size_sqm ELSE p.nominal_size_sqm END";

        private final String allowedFrom;
        private final String assignments;

        Target(String allowedFrom, String assignments) {
            this.allowedFrom = allowedFrom;
            this.assignments = assignments;
        }
    }

    @PersistenceContext
    private EntityManager entityManager;

    /** The ids that changed — or, on a dry run, would change right now. */
    @SuppressWarnings("unchecked")
    public List<UUID> apply(UUID estateId, Collection<UUID> plotIds, Target target, String actor, boolean dryRun) {
        String where = " WHERE t.id = p.price_tier_id AND p.estate_id = :estateId AND p.id IN (:ids)"
                + " AND p.deleted = false AND " + target.allowedFrom;
        entityManager.flush();
        List<Object> ids;
        if (dryRun) {
            ids = entityManager.createNativeQuery("SELECT p.id FROM plots p, price_tiers t" + where)
                    .setParameter("estateId", estateId)
                    .setParameter("ids", plotIds)
                    .getResultList();
        } else {
            ids = entityManager.createNativeQuery("UPDATE plots p SET " + target.assignments
                            + ", updated_at = now(), updated_by = :actor FROM price_tiers t" + where + " RETURNING p.id")
                    .setParameter("estateId", estateId)
                    .setParameter("ids", plotIds)
                    .setParameter("actor", actor)
                    .getResultList();
            // The persistence context still holds the pre-update rows.
            entityManager.clear();
        }
        return ids.stream().map(id -> id instanceof UUID u ? u : UUID.fromString(id.toString())).toList();
    }
}
