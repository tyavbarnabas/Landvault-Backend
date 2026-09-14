package com.techcomfort.landvaultbackend.identity.internal.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.jwt.*} config. {@code secret} has no default — a missing
 * {@code JWT_SECRET} env var fails application startup rather than silently
 * running unsigned/predictable. See AGENTS.md for the token-lifetime choices.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
) {
}
