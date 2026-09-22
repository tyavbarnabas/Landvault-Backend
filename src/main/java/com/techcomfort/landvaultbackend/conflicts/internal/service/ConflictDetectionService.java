package com.techcomfort.landvaultbackend.conflicts.internal.service;

import com.techcomfort.landvaultbackend.conflicts.ConflictDetectionApi;
import com.techcomfort.landvaultbackend.conflicts.ConflictPublicationCheck;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Runs detection and answers the publication question.
 * <p>
 * <strong>Every statement here goes through a {@code SECURITY DEFINER}
 * function, and none through a repository.</strong> That is not a style
 * choice — see changeset 047's header for the full reasoning. In short,
 * detection needs three things an ordinary query cannot do from where it
 * runs: read estates across tenants, write to a platform-scope-only table
 * from inside a tenant-scoped request, and do both without elevating the
 * rest of that request's transaction.
 * <p>
 * The alternative the slice spec also allowed — {@code SET LOCAL
 * landvault.platform_scope = 'on'} for the detection path — was rejected
 * because it cannot be scoped to one statement: it would leave the
 * remainder of a tenant's estate-creation transaction able to read and
 * write every tenant's rows. A definer function's privileges stop at the
 * function body.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConflictDetectionService implements ConflictDetectionApi {

    private final ConflictProperties properties;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public int detectForEstateBoundary(UUID estateId) {
        if (estateId == null) {
            return 0;
        }
        // The subject must be visible to the function, which runs on this
        // same connection and transaction. Hibernate does not reliably
        // auto-flush before a native query whose query spaces it cannot
        // infer, so a just-saved row could otherwise be invisible to the
        // very detection run that exists to check it.
        //
        // Callers should still flush through their own repository first
        // (saveAndFlush/saveAllAndFlush). This flush is a safety net, not
        // the intended path: EntityManager.flush() from inside a @Service
        // is NOT covered by Spring's persistence-exception translation, so
        // a constraint violation surfacing here arrives as a raw
        // PersistenceException and escapes as a 500 instead of the 409 its
        // handler would have produced. Found by PortalEstateCreationIT's
        // duplicate-plot-number test, which went red the moment detection
        // was wired into plot creation.
        entityManager.flush();

        int recorded = (int) (Integer) entityManager
                .createNativeQuery("SELECT landvault_detect_estate_conflicts(:estateId, :minOverlap)")
                .setParameter("estateId", estateId)
                .setParameter("minOverlap", properties.getMinOverlapSqm())
                .getSingleResult();

        if (recorded > 0) {
            log.info("Estate boundary conflict detection: estate={} liveConflicts={}", estateId, recorded);
        }
        return recorded;
    }

    @Override
    @Transactional
    public int detectForEstatePlots(UUID estateId) {
        if (estateId == null) {
            return 0;
        }
        entityManager.flush();

        int recorded = (int) (Integer) entityManager
                .createNativeQuery("SELECT landvault_detect_plot_conflicts(:estateId, :minOverlap)")
                .setParameter("estateId", estateId)
                .setParameter("minOverlap", properties.getMinOverlapSqm())
                .getSingleResult();

        if (recorded > 0) {
            log.info("Plot conflict detection: estate={} liveConflicts={}", estateId, recorded);
        }
        return recorded;
    }

    /**
     * CD-10. HIGH blocks, MEDIUM warns — see
     * {@link ConflictPublicationCheck} for why that asymmetry is
     * deliberate. A {@code CONFIRMED_DUPLICATE} blocks at any severity,
     * because a human has looked and said it is a real duplicate; at that
     * point the survey-error assumption behind MEDIUM no longer applies.
     */
    @Override
    @Transactional(readOnly = true)
    public ConflictPublicationCheck publicationCheckFor(UUID estateId) {
        if (estateId == null) {
            return ConflictPublicationCheck.clear();
        }
        Object[] row = (Object[]) entityManager
                .createNativeQuery("SELECT blocking_count, warning_count FROM landvault_estate_conflict_summary(:estateId)")
                .setParameter("estateId", estateId)
                .getSingleResult();

        int blocking = ((Number) row[0]).intValue();
        int warning = ((Number) row[1]).intValue();

        if (blocking > 0) {
            return ConflictPublicationCheck.blocked(
                    "This estate's boundary overlaps land claimed by another listing. "
                            + "Publication is paused while the overlap is reviewed. A correction to the "
                            + "boundary is itself reviewed before publication can resume — geometry no "
                            + "longer overlapping does not lift this on its own.",
                    blocking, warning);
        }
        return warning > 0 ? ConflictPublicationCheck.warning(warning) : ConflictPublicationCheck.clear();
    }

    /** Exposed for the threshold's own tests; not part of the public API. */
    BigDecimal minOverlapSqm() {
        return properties.getMinOverlapSqm();
    }
}
