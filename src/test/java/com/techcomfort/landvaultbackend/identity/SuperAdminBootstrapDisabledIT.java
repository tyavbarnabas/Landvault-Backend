package com.techcomfort.landvaultbackend.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The overwhelmingly common case: bootstrap disabled (the default), an
 * ordinary startup does nothing at all. A separate class from
 * {@link SuperAdminBootstrapIT} — {@code landvault.bootstrap.super-admin.enabled}
 * is fixed for the life of one Spring context, same reason
 * {@code SwaggerDevProfileIT} is separate from {@code AuthenticationIT}.
 */
@SpringBootTest
@Testcontainers
class SuperAdminBootstrapDisabledIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "integration-test-signing-secret-of-at-least-32-bytes");
        // landvault.bootstrap.super-admin.enabled defaults to false — not
        // set here at all, deliberately, to prove the real default (not a
        // test-only override) is what keeps this a no-op.
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void disabledBootstrapCreatesNoUser() {
        Integer userCount = jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class);
        Integer superAdminAssignments = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id
                WHERE r.code = 'super_admin'
                """, Integer.class);

        assertThat(userCount).isZero();
        assertThat(superAdminAssignments).isZero();
    }
}
