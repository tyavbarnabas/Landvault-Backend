package com.techcomfort.landvaultbackend.identity.internal.security;

import java.util.List;
import java.util.UUID;

/**
 * A validated access token's claims, in the shape {@link JwtService}'s
 * caller actually needs — not the raw {@code io.jsonwebtoken.Claims} map.
 * {@code roles} is what {@link TenantContextFilter} resolves branch scope
 * from — see AGENTS.md.
 */
public record AccessTokenClaims(
        UUID userId,
        String email,
        UUID tenantId,
        boolean platformStaff,
        List<String> permissions,
        List<RoleClaim> roles
) {
}
