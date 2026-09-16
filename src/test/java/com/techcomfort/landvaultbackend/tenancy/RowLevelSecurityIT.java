package com.techcomfort.landvaultbackend.tenancy;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.MeResponse;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deliverable of this slice: proof the database itself enforces tenant
 * isolation, not just that the policies exist. Deliberately does NOT use
 * {@code @ServiceConnection} — this suite needs the app's own connection
 * (spring.datasource) on the restricted role RLS actually applies to, and
 * Liquibase's connection (spring.liquibase) on the container's superuser,
 * which are two different roles. See AGENTS.md.
 * <p>
 * Fixture setup uses a raw JDBC connection opened directly against the
 * container's superuser credentials — bypassing RLS entirely, the same way
 * seeding genuinely cross-tenant data always has to. The assertions
 * themselves go through the app's own autowired {@link JdbcTemplate} (the
 * restricted role) inside a real transaction driven by
 * {@link TransactionTemplate}, after directly setting {@link TenantContext}
 * — the same mechanism {@code TenantContextIT} uses, exercising the exact
 * {@code TenantScopedDataSource} → {@code SET LOCAL} → RLS policy pipeline
 * a real request goes through, without needing new endpoints this task
 * doesn't ask for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class RowLevelSecurityIT {

    private static final String APP_ROLE = "landvault_app_test";
    private static final String APP_ROLE_PASSWORD = "rls-it-app-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "integration-test-signing-secret-of-at-least-32-bytes");

        // Liquibase migrates as the container's own superuser — it needs
        // CREATE ROLE/GRANT privileges the restricted app role below
        // deliberately doesn't have.
        registry.add("spring.liquibase.url", POSTGRES::getJdbcUrl);
        registry.add("spring.liquibase.user", POSTGRES::getUsername);
        registry.add("spring.liquibase.password", POSTGRES::getPassword);
        registry.add("spring.liquibase.parameters.appDbUsername", () -> APP_ROLE);
        registry.add("spring.liquibase.parameters.appDbPassword", () -> APP_ROLE_PASSWORD);

        // The app's own runtime connection is the restricted role changeset
        // 019 creates — this is what makes this suite a real test of RLS
        // actually applying, not just existing in the schema.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    // --- isolation, branch scope, platform scope, write protection, no-context ---

    @Test
    void isolationBetweenTenants() {
        UUID orgA = insertOrganization("RLS Tenant A");
        UUID orgB = insertOrganization("RLS Tenant B");
        insertDocument(orgA, null);
        insertDocument(orgB, null);
        insertBranch(orgA, "A Branch");
        insertBranch(orgB, "B Branch");

        TenantScope scopeA = new TenantScope(UUID.randomUUID(), orgA, null, false);
        TenantScope scopeB = new TenantScope(UUID.randomUUID(), orgB, null, false);

        List<UUID> docsAsA = inScope(scopeA, this::documentOrgIds);
        List<UUID> docsAsB = inScope(scopeB, this::documentOrgIds);
        assertThat(docsAsA).containsExactly(orgA);
        assertThat(docsAsB).containsExactly(orgB);

        List<UUID> branchesAsA = inScope(scopeA, this::branchOrganizationIds);
        List<UUID> branchesAsB = inScope(scopeB, this::branchOrganizationIds);
        assertThat(branchesAsA).containsExactly(orgA);
        assertThat(branchesAsB).containsExactly(orgB);
    }

    @Test
    void branchScopedUserSeesOwnBranchPlusOrgLevelRows() {
        UUID org = insertOrganization("RLS Branch Test Co");
        UUID branchA = insertBranch(org, "Branch A");
        UUID branchB = insertBranch(org, "Branch B");
        UUID orgLevelDoc = insertDocument(org, null);
        UUID branchADoc = insertDocument(org, branchA);
        insertDocument(org, branchB); // branch B's — must not be visible

        // Whether this scope came from a hard-wall role or a branch switch,
        // the DB layer treats it identically — the switch's own header
        // logic is unit-tested separately (TenantScopeResolverTest).
        TenantScope branchScoped = new TenantScope(UUID.randomUUID(), org, branchA, false);

        List<UUID> visible = inScope(branchScoped, this::documentIds);
        assertThat(visible).containsExactlyInAnyOrder(orgLevelDoc, branchADoc);
    }

    @Test
    void platformStaffSeesAcrossEveryTenant() {
        UUID orgA = insertOrganization("RLS Platform Test A");
        UUID orgB = insertOrganization("RLS Platform Test B");
        UUID docA = insertDocument(orgA, null);
        UUID docB = insertDocument(orgB, null);

        TenantScope platformScope = new TenantScope(UUID.randomUUID(), null, null, true);

        List<UUID> visible = inScope(platformScope, this::documentIds);
        assertThat(visible).contains(docA, docB);
    }

    @Test
    void writeProtectionRejectsAnotherTenantsRow() {
        UUID orgA = insertOrganization("RLS Write Test A");
        UUID orgB = insertOrganization("RLS Write Test B");
        TenantScope scopeA = new TenantScope(UUID.randomUUID(), orgA, null, false);

        // As tenant A, attempt to insert a director row stamped with
        // tenant B's id — WITH CHECK must reject this, not just USING on
        // reads. Read isolation without write isolation is half a control.
        assertThatThrownBy(() -> inScope(scopeA, () -> {
            jdbcTemplate.update("""
                    INSERT INTO directors (
                        id, tenant_id, created_at, created_by, deleted,
                        organization_id, full_name, role, nationality,
                        id_type, id_number, ownership_pct, is_beneficial_owner
                    ) VALUES (?, ?, now(), 'test', false, ?, 'Attacker', 'Director', 'NG', 'NIN', '12345678901', 10.00, false)
                    """, UUID.randomUUID(), orgB, orgB);
            return null;
        })).isInstanceOf(DataAccessException.class);
    }

    @Test
    void noContextSeesNothing() {
        UUID org = insertOrganization("RLS No Context Test");
        insertDocument(org, null);

        // Deliberately no TenantContext.set(...) at all — this is the
        // Liquibase-connection / unauthenticated-request / background-job
        // shape. Fail closed: zero rows, not an error.
        TenantContext.clear();
        List<UUID> visible = documentIds();
        assertThat(visible).isEmpty();
    }

    // --- login/me must still work once the app runs as the restricted role ---

    @Test
    void registrationAndLoginStillWorkAfterRls() {
        String email = "rls+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest(
                "RLS", "Buyer", email, "+2348000000000", "correct horse battery staple", "NG", Currency.NGN);

        ResponseEntity<AuthResponse> response = restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class);

        // roles/permissions/role_permissions are deliberately NOT
        // RLS-policied (platform-wide reference data — see AGENTS.md), and
        // users isn't policied in this slice either (see AGENTS.md) —
        // if either were wrong, registration would fail outright here.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().user().permissions()).isNotEmpty();
    }

    @Test
    void meStillWorksForABuyer() {
        String email = "rls-me+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest(
                "RLS", "Me", email, "+2348000000000", "correct horse battery staple", "NG", Currency.NGN);
        AuthResponse registered = restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class).getBody();
        assertThat(registered).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(registered.token());
        ResponseEntity<MeResponse> response = restTemplate.exchange(
                "/api/me", HttpMethod.GET, new HttpEntity<>(headers), MeResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- helpers ---

    private <T> T inScope(TenantScope scope, Supplier<T> work) {
        TenantContext.set(scope);
        try {
            return new TransactionTemplate(transactionManager).execute(status -> work.get());
        } finally {
            TenantContext.clear();
        }
    }

    private List<UUID> documentIds() {
        return jdbcTemplate.queryForList("SELECT id FROM organization_documents", UUID.class);
    }

    private List<UUID> documentOrgIds() {
        return jdbcTemplate.queryForList("SELECT organization_id FROM organization_documents", UUID.class);
    }

    private List<UUID> branchOrganizationIds() {
        return jdbcTemplate.queryForList("SELECT organization_id FROM branches", UUID.class);
    }

    // Superuser, raw JDBC — deliberately bypasses RLS and TenantContext
    // entirely. Seeding cross-tenant fixture data is exactly the kind of
    // operation no single tenant scope could ever legitimately perform.
    private static Connection superuserConnection() {
        try {
            return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID insertOrganization(String name) {
        UUID id = UUID.randomUUID();
        try (Connection connection = superuserConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO organizations (
                        id, created_at, created_by, deleted,
                        registered_name, rc_number, company_type, date_of_incorporation,
                        registered_street, registered_city, registered_state,
                        operating_street, operating_city, operating_state,
                        company_email, company_phone,
                        plan, marketplace_publishing, mlm_module, fx_rails,
                        status, verification_state
                    ) VALUES (
                        '%s', now(), 'rls-it', false,
                        '%s', 'RC-%s', 'LIMITED_LIABILITY', '%s',
                        'Test St', 'Lagos', 'Lagos',
                        'Test St', 'Lagos', 'Lagos',
                        'org-%s@example.com', '+2348000000000',
                        'STARTER', false, false, false, 'ACTIVE', 'VERIFIED'
                    )
                    """.formatted(id, name.replace("'", "''"), id, LocalDate.now(), id));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private static UUID insertBranch(UUID organizationId, String name) {
        UUID id = UUID.randomUUID();
        try (Connection connection = superuserConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO branches (id, created_at, created_by, deleted, organization_id, name)
                    VALUES ('%s', now(), 'rls-it', false, '%s', '%s')
                    """.formatted(id, organizationId, name.replace("'", "''")));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private static UUID insertDocument(UUID organizationId, UUID branchId) {
        UUID id = UUID.randomUUID();
        String branchLiteral = branchId == null ? "NULL" : "'" + branchId + "'";
        try (Connection connection = superuserConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO organization_documents (
                        id, tenant_id, branch_id, created_at, created_by, deleted,
                        organization_id, type, file_name, size, status, uploaded_at
                    ) VALUES (
                        '%s', '%s', %s, now(), 'rls-it', false,
                        '%s', 'CAC_CERTIFICATE', 'cac.pdf', 1024, 'PENDING', now()
                    )
                    """.formatted(id, organizationId, branchLiteral, organizationId));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }
}
