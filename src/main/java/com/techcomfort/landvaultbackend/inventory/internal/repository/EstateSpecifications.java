package com.techcomfort.landvaultbackend.inventory.internal.repository;

import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.enums.EstateIntent;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Filters for the estate directory.
 * <p>
 * <strong>Nothing here filters by tenant, and nothing here should.</strong>
 * Isolation is the database's job — changeset 044's row-level-security
 * policies scope every read to the caller's tenant (and branch) before
 * these predicates ever run. Adding a {@code tenantId} predicate here would
 * be a second, weaker copy of that guarantee, and the moment the two
 * disagreed the weaker one would be the bug nobody looks for. See AGENTS.md.
 */
public final class EstateSpecifications {

    private EstateSpecifications() {
    }

    public static Specification<Estate> matching(
            String state, Boolean published, String intent, UUID branchId, String query) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (state != null && !state.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("state")), state.trim().toLowerCase(Locale.ROOT)));
            }
            if (published != null) {
                predicates.add(cb.equal(root.get("published"), published));
            }
            if (intent != null && !intent.isBlank()) {
                predicates.add(cb.equal(root.get("intent"), EstateIntent.fromValue(intent)));
            }
            // An Executive Director narrowing the directory to one branch.
            // RLS has already confirmed the caller may see this tenant; a
            // branch id that isn't theirs simply matches nothing, which is
            // the same non-answer a prober would get from a branch that
            // doesn't exist.
            if (branchId != null) {
                predicates.add(cb.equal(root.get("branchId"), branchId));
            }
            if (query != null && !query.isBlank()) {
                String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("city")), like),
                        cb.like(cb.lower(root.get("area")), like)));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
