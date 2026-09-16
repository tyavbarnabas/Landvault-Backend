package com.techcomfort.landvaultbackend.identity.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code landvault.bootstrap.super-admin.*} config. Defaults to disabled —
 * an ordinary startup does nothing at all. See {@link SuperAdminBootstrap}
 * and AGENTS.md for why this exists instead of a Liquibase seed.
 */
@ConfigurationProperties(prefix = "landvault.bootstrap.super-admin")
public record SuperAdminBootstrapProperties(
        boolean enabled,
        String email,
        String firstName,
        String lastName,
        String password
) {
}
