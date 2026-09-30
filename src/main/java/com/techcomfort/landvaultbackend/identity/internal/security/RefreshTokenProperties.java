package com.techcomfort.landvaultbackend.identity.internal.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.refresh-token.*}: the cookie that carries the refresh token, the
 * rotation grace window, and how long dead rows are kept. Every value is
 * configuration rather than a constant — {@code cookieSecure} in particular
 * is true in the base config and false only under {@code dev}. See AGENTS.md.
 */
@ConfigurationProperties(prefix = "app.refresh-token")
public record RefreshTokenProperties(
        String cookieName,
        boolean cookieSecure,
        String cookieSameSite,
        String cookiePath,
        Duration reuseGrace,
        Duration cleanupRetention
) {
}
