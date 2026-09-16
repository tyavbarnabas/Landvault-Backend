package com.techcomfort.landvaultbackend.identity;

import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.internal.service.SuperAdminBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bootstrap enabled with valid configuration — the account gets created
 * once, is idempotent across repeated runs, and the resulting credentials
 * actually work end to end through the real login endpoint. One shared
 * context: all three concerns depend on the same successful first-run
 * state, so splitting them into separate Testcontainers instances would
 * only add setup cost, not isolation that matters here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class SuperAdminBootstrapIT {

    private static final String EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "integration-test-signing-secret-of-at-least-32-bytes");
        registry.add("landvault.bootstrap.super-admin.enabled", () -> "true");
        registry.add("landvault.bootstrap.super-admin.email", () -> EMAIL);
        registry.add("landvault.bootstrap.super-admin.first-name", () -> "Test");
        registry.add("landvault.bootstrap.super-admin.last-name", () -> "Admin");
        registry.add("landvault.bootstrap.super-admin.password", () -> PASSWORD);
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SuperAdminBootstrap bootstrap;

    @Test
    void createsExactlyOneSuperAdminWithTheRightShape() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT tenant_id, branch_id, status, must_change_password FROM users WHERE email = ?",
                EMAIL);

        assertThat(row.get("tenant_id")).isNull();
        assertThat(row.get("branch_id")).isNull();
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("must_change_password")).isEqualTo(true);

        Integer superAdminCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id
                WHERE r.code = 'super_admin'
                """, Integer.class);
        assertThat(superAdminCount).isEqualTo(1);
    }

    // Simulates a restart with the environment variable still set, without
    // the cost of spinning up a second full Spring context — run() is the
    // exact same method Boot would invoke on an actual restart, and its
    // idempotency check (existsByRoleId) is a pure DB read with no
    // in-memory state that a real restart would exercise differently.
    @Test
    void runningItAgainDoesNotCreateASecondAdmin() {
        ApplicationArguments noArgs = new DefaultApplicationArguments();

        bootstrap.run(noArgs);
        bootstrap.run(noArgs);

        Integer superAdminCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id
                WHERE r.code = 'super_admin'
                """, Integer.class);
        assertThat(superAdminCount).isEqualTo(1);
    }

    @Test
    void bootstrappedCredentialsLogInWithAdminPermissions() {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(EMAIL, PASSWORD), AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.user().role()).isEqualTo("super_admin");
        assertThat(body.user().mustChangePassword()).isTrue();
        assertThat(body.user().permissions()).contains("admin.tenants.view");

        String payload = decodeJwtPayload(body.token());
        assertThat(payload).contains("\"platform_staff\":true").contains("\"admin.tenants.view\"");
    }

    private static String decodeJwtPayload(String jwt) {
        String payload = jwt.split("\\.")[1];
        return new String(java.util.Base64.getUrlDecoder().decode(payload), java.nio.charset.StandardCharsets.UTF_8);
    }
}
