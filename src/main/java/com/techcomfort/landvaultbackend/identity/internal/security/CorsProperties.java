package com.techcomfort.landvaultbackend.identity.internal.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** {@code app.cors.allowed-origins} — from configuration, never hardcoded. */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(
        List<String> allowedOrigins) {
}
