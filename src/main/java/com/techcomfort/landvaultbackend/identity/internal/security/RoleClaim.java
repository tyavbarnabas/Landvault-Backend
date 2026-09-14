package com.techcomfort.landvaultbackend.identity.internal.security;

import java.util.UUID;

/** One role assignment as it travels in the access token's {@code roles} claim. */
public record RoleClaim(
        String role,
        UUID branch
  ) {
}
