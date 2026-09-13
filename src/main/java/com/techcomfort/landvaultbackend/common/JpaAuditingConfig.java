package com.techcomfort.landvaultbackend.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

/**
 * Wires up JPA auditing ({@code @CreatedDate}/{@code @LastModifiedDate} work
 * from {@code @EnableJpaAuditing} alone, but {@code @CreatedBy}/
 * {@code @LastModifiedBy} need an {@link AuditorAware} bean or they stay
 * silently {@code null}). Kept in one place in {@code common} rather than on
 * the main application class so the auditing setup and its actor-resolution
 * policy live together.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    /**
     * Resolves the "who" for {@code created_by}/{@code updated_by}.
     * <p>
     * TODO: once the {@code identity} module has a security context, read
     * the authenticated principal from {@code SecurityContextHolder} here
     * instead. {@code "system"} must stay a fallback for genuine system
     * actors only (Liquibase seed data, scheduled jobs) — never a
     * stand-in for a human actor, because {@code verification_decisions}
     * and {@code audit_log} both record who made a decision, and a wrong
     * actor on a human approval is a false audit record.
     */
    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of("system");
    }
}
