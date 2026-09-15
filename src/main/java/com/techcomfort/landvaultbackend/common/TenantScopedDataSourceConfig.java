package com.techcomfort.landvaultbackend.common;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Wraps whatever {@code DataSource} bean the context produces —
 * auto-configured {@code HikariDataSource} in normal use, a Testcontainers
 * {@code @ServiceConnection}-backed one in integration tests — in
 * {@link TenantScopedDataSource}, rather than redeclaring the primary
 * {@code DataSource} bean by hand from {@code DataSourceProperties}. A
 * {@link BeanPostProcessor} is what lets this work identically regardless of
 * how the underlying pool got built, with no Hikari-specific configuration
 * to duplicate or fall out of sync here.
 * <p>
 * Runs for every bean, but only ever acts on the one (or two, in tests)
 * that are actually a {@code DataSource} — Liquibase, JPA, and
 * {@code JdbcTemplate} all end up with the same wrapped instance, since bean
 * post-processing completes before anything that depends on this bean is
 * constructed.
 */
@Component
public class TenantScopedDataSourceConfig implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && !(bean instanceof TenantScopedDataSource)) {
            return new TenantScopedDataSource(dataSource);
        }
        return bean;
    }
}
