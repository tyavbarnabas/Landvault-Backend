package com.techcomfort.landvaultbackend.identity.internal.security;

import java.time.Instant;

/** A freshly issued access token and when it expires. */
public record AccessTokenIssue(
        String token,
        Instant expiresAt) {
}
