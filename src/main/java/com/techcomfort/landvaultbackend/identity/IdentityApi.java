package com.techcomfort.landvaultbackend.identity;

import com.techcomfort.landvaultbackend.identity.dto.UserDto;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The identity module's only public surface.
 * <p>
 * Other modules must go through this interface rather than reaching for
 * {@code identity.internal} — the {@code User} entity and its repository
 * are intentionally invisible outside this module. A user row will operate
 * under row-level-security and repository-scoping rules this module owns;
 * if another module could obtain the raw entity and a repository, it could
 * query around those isolation guarantees.
 * <p>
 * If you find yourself wanting to expose a repository or entity from a
 * module instead of adding a method here, the boundary is in the wrong
 * place — see AGENTS.md. Add methods below only as other modules genuinely
 * need them.
 */
public interface IdentityApi {

    /**
     * <strong>Careful:</strong> {@code identity.dto} is not a Modulith
     * named interface, so {@link UserDto}'s accessors cannot be called from
     * another module — the verification test rejects it, even though this
     * method is public. Until that is settled (either by exposing the DTO
     * package deliberately or by keeping this module's cross-module surface
     * to narrow methods like the one below), prefer adding a method that
     * returns exactly what the caller needs.
     */
    Optional<UserDto> findById(UUID id);

    /**
     * A user's country of residence, as the two-letter code captured at
     * registration. Empty when there is no such user.
     * <p>
     * Narrow on purpose: {@code kyc} needs this one field to decide which
     * documents a buyer must produce, and a caller that receives only the
     * country cannot accidentally come to depend on the rest of a user row.
     */
    Optional<String> countryOf(UUID userId);

    /** The account's email — what a payment provider sends the receipt to. A single field, like {@link #countryOf}. */
    Optional<String> emailOf(UUID userId);

    /**
     * The email of every active, company-wide Executive Director of a
     * company — who is alerted when its payout account changes. Empty when
     * there is none.
     */
    List<String> executiveDirectorEmails(UUID tenantId);

    /**
     * Whether this account has two-factor authentication switched on AND
     * proven (both flags — see AGENTS.md, "Setup and confirmation are
     * separate states"). Sending money requires it.
     */
    boolean hasConfirmedTwoFactor(UUID userId);

    /** The email of every active Super Admin — who is told when a payout fails or is reversed. */
    List<String> superAdminEmails();

    /** A user's name as registered ("First Last") — what a refund account's bank name is compared with. */
    Optional<String> fullNameOf(UUID userId);
}
