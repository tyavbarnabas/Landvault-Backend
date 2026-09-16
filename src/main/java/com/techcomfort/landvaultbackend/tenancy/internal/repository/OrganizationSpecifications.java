package com.techcomfort.landvaultbackend.tenancy.internal.repository;

import com.techcomfort.landvaultbackend.tenancy.internal.enums.TenantPlan;
import com.techcomfort.landvaultbackend.tenancy.internal.enums.VerificationState;
import com.techcomfort.landvaultbackend.tenancy.internal.domain.Organization;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;

/**
 * The directory's combinable filters, matching {@code TenantFilters} in the
 * frontend's {@code tenantsService.ts} — free text, {@code verificationState},
 * {@code plan}, state of operation, and created-after date.
 * <p>
 * The frontend searches free text over "registered/trading name and primary
 * contact email" — this backend has no primary-contact data at all (see
 * AGENTS.md's note on this slice's contract findings), so the closest real
 * substitute, {@code companyEmail}, is searched instead.
 * <p>
 * "State of operation" has no dedicated column either — there is no stored
 * list of every state a tenant operates in. This matches against the two
 * real state columns that do exist, {@code registeredState}/{@code operatingState}
 * — an approximation, not the frontend's actual concept, and also documented
 * as a finding.
 */
public final class OrganizationSpecifications {

    private OrganizationSpecifications() {
    }

    public static Specification<Organization> query(String query) {
        String like = "%" + query.toLowerCase() + "%";
        return (root, cq, cb) -> cb.or(
                cb.like(cb.lower(root.get("registeredName")), like),
                cb.like(cb.lower(root.get("tradingName")), like),
                cb.like(cb.lower(root.get("companyEmail")), like));
    }

    public static Specification<Organization> verificationStateIn(List<VerificationState> states) {
        return (root, cq, cb) -> root.get("verificationState").in(states);
    }

    public static Specification<Organization> plan(TenantPlan plan) {
        return (root, cq, cb) -> cb.equal(root.get("plan"), plan);
    }

    public static Specification<Organization> stateOfOperation(String state) {
        return (root, cq, cb) -> cb.or(
                cb.equal(root.get("registeredState"), state),
                cb.equal(root.get("operatingState"), state));
    }

    public static Specification<Organization> createdAfter(Instant instant) {
        return (root, cq, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), instant);
    }

    /** Combines only the filters actually supplied — every parameter is nullable. */
    public static Specification<Organization> matching(
            String query, List<VerificationState> verificationStates, TenantPlan plan, String state, Instant createdAfter) {
        Specification<Organization> spec = Specification.unrestricted();
        if (query != null && !query.isBlank()) {
            spec = spec.and(query(query));
        }
        if (verificationStates != null && !verificationStates.isEmpty()) {
            spec = spec.and(verificationStateIn(verificationStates));
        }
        if (plan != null) {
            spec = spec.and(plan(plan));
        }
        if (state != null && !state.isBlank()) {
            spec = spec.and(stateOfOperation(state));
        }
        if (createdAfter != null) {
            spec = spec.and(createdAfter(createdAfter));
        }
        return spec;
    }
}
