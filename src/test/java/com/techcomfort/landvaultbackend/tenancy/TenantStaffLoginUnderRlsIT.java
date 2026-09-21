package com.techcomfort.landvaultbackend.tenancy;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RefreshRequest;
import com.techcomfort.landvaultbackend.identity.dto.RefreshResponse;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.identity.dto.TenantScopeResponse;
import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantStatusRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant-staff login against a database where row-level security is
 * <strong>actually enforced</strong> — the app connects as the restricted
 * {@code landvault_app} role, not as the container superuser.
 * <p>
 * This class exists because of a bug that every other login test structurally
 * could not see. {@code AuthService.login()} asks
 * {@code TenancyApi.isTenantActive(...)}, and {@code TenantContextFilter} asks
 * {@code branchBelongsToTenant(...)}; both run <em>before</em> any tenant
 * scope exists, so under RLS their queries returned zero rows and every
 * tenant-staff login was refused as {@code TENANT_NOT_ACTIVE}. Every existing
 * login IT uses {@code @ServiceConnection} (superuser, RLS bypassed), and the
 * one IT that does use the restricted role only logs in as platform staff,
 * whose {@code tenantId} is null so the check never runs.
 * <p>
 * <strong>Do not convert this class to {@code @ServiceConnection}.</strong>
 * Doing so makes every test below pass whether the fix is present or not,
 * which is exactly how the bug survived in the first place.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class TenantStaffLoginUnderRlsIT {

    private static final String APP_ROLE = "landvault_app_rls_login_it";
    private static final String APP_ROLE_PASSWORD = "rls-login-it-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "integration-test-signing-secret-of-at-least-32-bytes");

        // Liquibase migrates as the superuser; the APP connects as the
        // restricted role. @ServiceConnection cannot express this split, and
        // the split is the entire point of this class.
        registry.add("spring.liquibase.url", POSTGRES::getJdbcUrl);
        registry.add("spring.liquibase.user", POSTGRES::getUsername);
        registry.add("spring.liquibase.password", POSTGRES::getPassword);
        registry.add("spring.liquibase.parameters.appDbUsername", () -> APP_ROLE);
        registry.add("spring.liquibase.parameters.appDbPassword", () -> APP_ROLE_PASSWORD);

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);

        registry.add("landvault.bootstrap.super-admin.enabled", () -> "true");
        registry.add("landvault.bootstrap.super-admin.email", () -> ADMIN_EMAIL);
        registry.add("landvault.bootstrap.super-admin.password", () -> PASSWORD);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    /** The regression: this returned 403 TENANT_NOT_ACTIVE for every tenant-staff account. */
    @Test
    void tenantStaffCanLogIn() {
        Fixture fixture = tenantStaff();

        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(fixture.email(), PASSWORD), AuthResponse.class);

        assertThat(response.getStatusCode())
                .as("RLS must not hide the organization from the very lookup that gates login")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().token()).isNotBlank();
    }

    /**
     * The other half: the gate must still actually gate. A fix that simply
     * returned {@code true} would pass the test above and defeat the feature.
     */
    @Test
    void staffOfASuspendedTenantStillCannotLogIn() {
        Fixture fixture = tenantStaff();
        suspend(fixture.tenantId());

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(fixture.email(), PASSWORD), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("TENANT_NOT_ACTIVE");
    }

    @Test
    void refreshAlsoWorksForTenantStaff() {
        Fixture fixture = tenantStaff();
        AuthResponse session = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(fixture.email(), PASSWORD), AuthResponse.class).getBody();

        ResponseEntity<RefreshResponse> refreshed = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshRequest(session.refreshToken()), RefreshResponse.class);

        assertThat(refreshed.getStatusCode())
                .as("refresh() calls the same lookup, before any scope exists")
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * The second half of the same bug: {@code TenantContextFilter} resolves
     * the branch switcher while the scope is still being computed, so its
     * {@code branches} read had no context either and silently never
     * honoured a branch.
     */
    @Test
    void theBranchSwitcherIsHonouredForAnOrganizationWideUser() {
        Fixture fixture = tenantStaff();
        String token = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(fixture.email(), PASSWORD), AuthResponse.class)
                .getBody().token();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Branch-Id", fixture.branchId().toString());

        ResponseEntity<TenantScopeResponse> response = restTemplate.exchange(
                "/api/me/tenant-scope", HttpMethod.GET, new HttpEntity<>(headers), TenantScopeResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().threadLocalBranchId())
                .as("an organization-wide user narrowing to a branch of their own tenant")
                .isEqualTo(fixture.branchId());
        assertThat(response.getBody().dbBranchId())
                .as("and it must reach Postgres as a session variable, not just the ThreadLocal")
                .isEqualTo(fixture.branchId().toString());
    }

    @Test
    void aBranchFromAnotherTenantIsStillIgnored() {
        Fixture fixture = tenantStaff();
        Fixture other = tenantStaff();
        String token = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(fixture.email(), PASSWORD), AuthResponse.class)
                .getBody().token();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Branch-Id", other.branchId().toString());

        ResponseEntity<TenantScopeResponse> response = restTemplate.exchange(
                "/api/me/tenant-scope", HttpMethod.GET, new HttpEntity<>(headers), TenantScopeResponse.class);

        assertThat(response.getBody().threadLocalBranchId())
                .as("a foreign branch is silently ignored, never honoured")
                .isNull();
    }

    // --- fixture ---

    private record Fixture(UUID tenantId, UUID branchId, String email) {
    }

    /**
     * A tenant with a branch and a staff account whose password we know. The
     * Executive Director the tenant-creation flow makes has an unrecoverable
     * random password by design, so the role is granted to a registered
     * account instead.
     */
    private Fixture tenantStaff() {
        String adminToken = loginAsSuperAdmin();
        UUID tenantId = createTenant(adminToken);

        UUID branchId = UUID.randomUUID();
        execute("INSERT INTO branches (id, created_at, deleted, organization_id, name) VALUES ('"
                + branchId + "', now(), false, '" + tenantId + "', 'Head Office')");

        String email = "staff+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest(
                "Tenant", "Staff", email, "+2348000000000", PASSWORD, "NG", Currency.NGN);
        assertThat(restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        execute("UPDATE users SET tenant_id = '" + tenantId + "' WHERE lower(email) = lower('" + email + "')");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id FROM users u, roles r "
                + "WHERE lower(u.email) = lower('" + email + "') AND r.code = 'executive_director'");

        return new Fixture(tenantId, branchId, email);
    }

    private String loginAsSuperAdmin() {
        return restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(ADMIN_EMAIL, PASSWORD), AuthResponse.class)
                .getBody().token();
    }

    private void suspend(UUID tenantId) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/tenants/" + tenantId + "/status", HttpMethod.POST,
                entity(loginAsSuperAdmin(), new TenantStatusRequest("suspended", "Testing the gate.")),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private UUID createTenant(String adminToken) {
        String rc = "RC-" + UUID.randomUUID();
        CreateTenantRequest request = new CreateTenantRequest(
                new CompanyIdentityDto("RLS Co " + rc, null, rc, "Limited Liability (Ltd)", "2020-01-01",
                        new AddressDto("1 Broad Street", "Lagos", "Lagos"),
                        new AddressDto("1 Broad Street", "Lagos", "Lagos"), List.of("Lagos")),
                new PrimaryContactDto("Some Director", "Chief Executive Officer",
                        "ed+" + UUID.randomUUID() + "@example.com", "+2348000000001", "NIN", "12345678901"),
                new CompanyPresenceDto("org+" + rc + "@example.com", "+2348000000002", null,
                        new SocialsDto(null, null, null, null)),
                "starter");

        ResponseEntity<TenantDetailDto> response = restTemplate.exchange(
                "/api/admin/tenants", HttpMethod.POST, entity(adminToken, request), TenantDetailDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private HttpEntity<Object> entity(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    // Superuser connection, deliberately: fixture setup writes rows the app's
    // own restricted role could not see to write.
    private static void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
