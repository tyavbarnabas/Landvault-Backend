package com.techcomfort.landvaultbackend.identity;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.identity.dto.TenantScopeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenant-context filter and its SET LOCAL mechanism, exercised through
 * real HTTP requests against a real Postgres — see AGENTS.md.
 * <p>
 * The connection pool is pinned to exactly one connection
 * ({@code spring.datasource.hikari.maximum-pool-size=1}) specifically for
 * {@link #aSecondTenantsRequestNeverSeesTheFirstTenantsSessionVariables()}:
 * without this, HikariCP would very likely (but not guaranteedly) hand back
 * the same physical connection to two sequential requests anyway, which
 * would make the leak test pass or fail non-deterministically depending on
 * pool internals rather than on whether SET LOCAL actually works. Pinning
 * the pool to one connection makes reuse certain, which is what turns this
 * into a real regression test for "SET LOCAL, never plain SET" instead of a
 * flaky one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class TenantContextIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "integration-test-signing-secret-of-at-least-32-bytes");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String STAFF_PASSWORD = "correct horse battery staple";

    @Test
    void buyerResolvesToNoTenantAndNoBranchEverywhere() {
        String email = "buyer+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest(
                "Ada", "Lovelace", email, "+2348000000000", STAFF_PASSWORD, "NG", Currency.NGN);
        AuthResponse registered = restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class).getBody();
        assertThat(registered).isNotNull();

        TenantScopeResponse scope = callTenantScope(registered.token(), null);

        assertThat(scope.threadLocalTenantId()).isNull();
        assertThat(scope.threadLocalBranchId()).isNull();
        assertThat(scope.threadLocalPlatformStaff()).isFalse();
        // A buyer's request DOES establish a scope (it just has no tenant),
        // so the DB-level GUCs are explicitly '' — not unset/NULL. See
        // TenantScopedDataSource's null-handling note.
        assertThat(scope.dbTenantId()).isEmpty();
        assertThat(scope.dbBranchId()).isEmpty();
        assertThat(scope.dbPlatformScope()).isEqualTo("off");
    }

    @Test
    void branchScopedStaffIsAHardWallAndIgnoresTheSwitchHeader() {
        UUID orgId = insertOrganization("Heritage Estates");
        UUID branchA = insertBranch(orgId, "Branch A");
        UUID branchB = insertBranch(orgId, "Branch B");
        String email = insertTenantStaffUser(orgId, "branch_manager", branchA);

        AuthResponse login = login(email);
        TenantScopeResponse withoutHeader = callTenantScope(login.token(), null);

        assertThat(withoutHeader.threadLocalTenantId()).isEqualTo(orgId);
        assertThat(withoutHeader.threadLocalBranchId()).isEqualTo(branchA);
        assertThat(withoutHeader.dbTenantId()).isEqualTo(orgId.toString());
        assertThat(withoutHeader.dbBranchId()).isEqualTo(branchA.toString());

        // A hard wall: asking to switch to a real, different branch of the
        // SAME tenant is still ignored — resolveBaseScope already fixed
        // this user's scope, and the switcher only ever narrows an
        // organization-wide scope, never re-targets a branch-scoped one.
        TenantScopeResponse withHeader = callTenantScope(login.token(), branchB.toString());
        assertThat(withHeader.threadLocalBranchId()).isEqualTo(branchA);
        assertThat(withHeader.dbBranchId()).isEqualTo(branchA.toString());
    }

    @Test
    void organizationWideStaffCanNarrowToAGenuineBranchOfTheirOwnTenant() {
        UUID orgId = insertOrganization("Double King Group");
        UUID branch = insertBranch(orgId, "Only Branch");
        String email = insertTenantStaffUser(orgId, "executive_director", null);

        AuthResponse login = login(email);
        TenantScopeResponse orgWide = callTenantScope(login.token(), null);
        assertThat(orgWide.threadLocalBranchId()).isNull();

        TenantScopeResponse narrowed = callTenantScope(login.token(), branch.toString());
        assertThat(narrowed.threadLocalBranchId()).isEqualTo(branch);
        assertThat(narrowed.dbBranchId()).isEqualTo(branch.toString());

        // A branch belonging to some OTHER tenant is rejected even for an
        // organization-wide user — the switcher only narrows within their
        // own tenant.
        UUID otherOrgsBranch = insertBranch(insertOrganization("Crestview"), "Not Yours");
        TenantScopeResponse rejected = callTenantScope(login.token(), otherOrgsBranch.toString());
        assertThat(rejected.threadLocalBranchId()).isNull();
    }

    // The leak test that matters: tenant A's request, then tenant B's, on
    // the same (pinned single-connection) pool — B's session must carry no
    // trace of A. This is the regression test for the worst bug this
    // design can produce; see TenantScopedDataSource and TenantContext.
    @Test
    void aSecondTenantsRequestNeverSeesTheFirstTenantsSessionVariables() {
        UUID orgA = insertOrganization("Tenant A Estates");
        String emailA = insertTenantStaffUser(orgA, "executive_director", null);
        UUID orgB = insertOrganization("Tenant B Estates");
        String emailB = insertTenantStaffUser(orgB, "executive_director", null);

        AuthResponse loginA = login(emailA);
        TenantScopeResponse scopeA = callTenantScope(loginA.token(), null);
        assertThat(scopeA.dbTenantId()).isEqualTo(orgA.toString());

        AuthResponse loginB = login(emailB);
        TenantScopeResponse scopeB = callTenantScope(loginB.token(), null);

        assertThat(scopeB.dbTenantId())
                .isEqualTo(orgB.toString())
                .isNotEqualTo(orgA.toString());
        assertThat(scopeB.threadLocalTenantId()).isEqualTo(orgB);
    }

    private TenantScopeResponse callTenantScope(String accessToken, String branchHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        if (branchHeader != null) {
            headers.add("X-Branch-Id", branchHeader);
        }
        ResponseEntity<TenantScopeResponse> response = restTemplate.exchange(
                "/api/me/tenant-scope", HttpMethod.GET, new HttpEntity<>(headers), TenantScopeResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private AuthResponse login(String email) {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, STAFF_PASSWORD), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    // --- fixture setup: there is no public API for any of this by design
    // (tenant staff can never self-register) — inserted directly, then
    // exercised through the real /api/auth/login endpoint so the token
    // itself is entirely real, not hand-built. ---

    private UUID insertOrganization(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO organizations (
                    id, created_at, created_by, deleted,
                    registered_name, rc_number, company_type, date_of_incorporation,
                    registered_street, registered_city, registered_state,
                    operating_street, operating_city, operating_state,
                    company_email, company_phone,
                    plan, marketplace_publishing, mlm_module, fx_rails,
                    status, verification_state
                ) VALUES (?, now(), 'test', false,
                    ?, ?, 'LIMITED_LIABILITY', CURRENT_DATE,
                    'Test Street', 'Lagos', 'Lagos',
                    'Test Street', 'Lagos', 'Lagos',
                    ?, '+2348000000000',
                    'STARTER', false, false, false,
                    'ACTIVE', 'VERIFIED')
                """, id, name, "RC-" + id, "org+" + id + "@example.com");
        return id;
    }

    private UUID insertBranch(UUID organizationId, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO branches (id, created_at, created_by, deleted, organization_id, name)
                VALUES (?, now(), 'test', false, ?, ?)
                """, id, organizationId, name);
        return id;
    }

    private String insertTenantStaffUser(UUID organizationId, String roleCode, UUID scopedBranchId) {
        String email = roleCode + "+" + UUID.randomUUID() + "@example.com";
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (
                    id, tenant_id, created_at, created_by, deleted,
                    first_name, last_name, email, password_hash, country, currency, status, two_fa_enabled
                ) VALUES (?, ?, now(), 'test', false, 'Staff', 'User', ?, ?, 'NG', 'NGN', 'ACTIVE', false)
                """, userId, organizationId, email, passwordEncoder.encode(STAFF_PASSWORD));

        UUID roleId = jdbcTemplate.queryForObject("SELECT id FROM roles WHERE code = ?", UUID.class, roleCode);
        jdbcTemplate.update("""
                INSERT INTO user_roles (id, created_at, created_by, deleted, user_id, role_id, scoped_branch_id)
                VALUES (?, now(), 'test', false, ?, ?, ?)
                """, UUID.randomUUID(), userId, roleId, scopedBranchId);

        return email;
    }
}
