package com.techcomfort.landvaultbackend.identity;

import com.techcomfort.landvaultbackend.identity.dto.UserDto;

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
}
