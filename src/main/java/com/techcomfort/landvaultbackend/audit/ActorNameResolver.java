package com.techcomfort.landvaultbackend.audit;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Turns actor ids into display names for the audit log read path.
 * <p>
 * <strong>Declared here and implemented in {@code identity}, deliberately
 * inverted.</strong> The obvious approach — {@code audit} calling
 * {@code IdentityApi} — is impossible: {@code identity} already depends on
 * {@code audit} (both {@code AuthService} and {@code TwoFactorService} record
 * entries), so the reverse edge is a module cycle. Confirmed rather than
 * assumed: adding {@code IdentityApi} to {@code AuditApiImpl} fails
 * {@code ModularityTests} with "Cycle detected: Slice audit -> ...". Owning
 * the interface here keeps every arrow pointing the same way it already did.
 * <p>
 * Batched by construction: the parameter is a collection, so a paginated read
 * resolves one page's actors in a single call rather than one query per row.
 */
public interface ActorNameResolver {

    /**
     * Display names by user id. Ids with no matching user are simply absent
     * from the result — audit entries outlive the accounts that created them,
     * so a missing actor is an ordinary case, not an error.
     */
    Map<UUID, String> displayNamesFor(Collection<UUID> userIds);
}
