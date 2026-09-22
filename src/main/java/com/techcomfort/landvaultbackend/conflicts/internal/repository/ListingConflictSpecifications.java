package com.techcomfort.landvaultbackend.conflicts.internal.repository;

import com.techcomfort.landvaultbackend.conflicts.internal.domain.ListingConflict;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictSeverity;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictStatus;
import com.techcomfort.landvaultbackend.conflicts.internal.enums.ConflictType;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Filters and ordering for the admin review queue (CD-6). No tenant
 * predicate: this surface is platform-scope by definition, and a conflict
 * spans two tenants so there is no single one to scope it by.
 */
public final class ListingConflictSpecifications {

    private ListingConflictSpecifications() {
    }

    public static Specification<ListingConflict> matching(
            List<ConflictSeverity> severities, List<ConflictStatus> statuses, ConflictType conflictType) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (severities != null && !severities.isEmpty()) {
                predicates.add(root.get("severity").in(severities));
            }
            if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            if (conflictType != null) {
                predicates.add(cb.equal(root.get("conflictType"), conflictType));
            }

            // Worst first: severity, then largest overlap (CD-3/CD-6).
            //
            // Ranked with an explicit CASE rather than ordering by the
            // severity column. Because @Enumerated(STRING) stores the
            // constant name, a plain ascending sort happens to put HIGH
            // before MEDIUM today — but only because 'H' sorts before 'M'.
            // Adding a LOW severity later would silently slot it between
            // them and nothing would fail. The CASE says what is meant.
            //
            // Skipped for the count query Spring Data issues alongside the
            // page: ordering a COUNT is at best wasted work and on some
            // databases an error.
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                // Live before closed, first. Without it an unfiltered queue
                // ranked a dismissed HIGH above an open MEDIUM — history
                // outranking work that still needs doing.
                Expression<Integer> liveRank = cb.<Integer>selectCase()
                        .when(root.get("status").in(ConflictStatus.OPEN, ConflictStatus.INVESTIGATING,
                                ConflictStatus.CONFIRMED_DUPLICATE), 0)
                        .otherwise(1);
                Expression<Integer> severityRank = cb.<Integer>selectCase()
                        .when(cb.equal(root.get("severity"), ConflictSeverity.HIGH), 0)
                        .otherwise(1);
                query.orderBy(cb.asc(liveRank), cb.asc(severityRank), cb.desc(root.get("overlapAreaSqm")));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
