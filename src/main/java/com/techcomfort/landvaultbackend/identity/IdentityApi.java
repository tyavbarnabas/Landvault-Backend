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

    Optional<UserDto> findById(UUID id);
}
