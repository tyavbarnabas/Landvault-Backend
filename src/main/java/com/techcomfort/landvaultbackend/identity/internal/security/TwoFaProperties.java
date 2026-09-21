package com.techcomfort.landvaultbackend.identity.internal.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code landvault.two-fa.*} config. {@code encryptionKey} has no default —
 * a missing key fails startup rather than silently storing TOTP secrets in a
 * readable form, the same rule as {@code JWT_SECRET}. See AGENTS.md.
 */
@ConfigurationProperties(prefix = "landvault.two-fa")
public record TwoFaProperties(
        String encryptionKey,
        // How many 30-second windows either side of "now" to accept. 1 is
        // ~90 seconds total tolerance; widening this is extra brute-force
        // surface, not a kindness.
        int allowedDriftWindows,
        int maxFailedAttempts,
        Duration lockoutDuration,
        Duration challengeTtl,
        int recoveryCodeCount
) {
}
