package com.techcomfort.landvaultbackend.tenancy;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two read endpoints exercised through real HTTP, not a shortcut — the
 * whole point of this slice (see AGENTS.md's Part 0 note): JWT issue/parse,
 * the tenant context filter's platform-scope resolution, RLS's platform
 * bypass, the pagination envelope, and enum wire casing, all at once.
 * <p>
 * Deliberately NOT {@code @ServiceConnection} — same reason as
 * {@code RowLevelSecurityIT}: the app has to connect as the restricted role
 * RLS actually applies to, or the platform-bypass assertions below would
 * pass even if platform scope were never set at all (a superuser sees
 * everything regardless of any policy).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class AdminTenantControllerIT {

    private static final String APP_ROLE = "landvault_app_tenant_it";
    private static final String APP_ROLE_PASSWORD = "tenant-it-app-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String ADMIN_PASSWORD = "correct horse battery staple";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    private static UUID orgVerifiedEnterprise;
    private static UUID orgUnderReviewGrowth;
    private static UUID orgSuspendedStarter;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "integration-test-signing-secret-of-at-least-32-bytes");

        registry.add("spring.liquibase.url", POSTGRES::getJdbcUrl);
        registry.add("spring.liquibase.user", POSTGRES::getUsername);
        registry.add("spring.liquibase.password", POSTGRES::getPassword);
        registry.add("spring.liquibase.parameters.appDbUsername", () -> APP_ROLE);
        registry.add("spring.liquibase.parameters.appDbPassword", () -> APP_ROLE_PASSWORD);

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);

        // A real Super Admin, created the only way one can be — see
        // SuperAdminBootstrap and AGENTS.md.
        registry.add("landvault.bootstrap.super-admin.enabled", () -> "true");
        registry.add("landvault.bootstrap.super-admin.email", () -> ADMIN_EMAIL);
        registry.add("landvault.bootstrap.super-admin.password", () -> ADMIN_PASSWORD);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    // Deliberately @BeforeEach (guarded to run once), not @BeforeAll: a
    // static @BeforeAll runs before Spring's own context-loading callback
    // is guaranteed to have finished, i.e. before Liquibase has migrated
    // the schema — confirmed directly, the first attempt using @BeforeAll
    // failed with "relation organizations does not exist". @BeforeEach
    // only ever runs once the context (and migrations) are fully ready.
    private static boolean seeded = false;

    @BeforeEach
    void seedThreeTenantsOnce() {
        if (seeded) {
            return;
        }
        orgVerifiedEnterprise = insertOrganization("Estintin Group Limited", "Estintin Group",
                "ENTERPRISE", "VERIFIED", "ACTIVE");
        orgUnderReviewGrowth = insertOrganization("Citadel Homes Limited", null,
                "GROWTH", "UNDER_REVIEW", "ACTIVE");
        orgSuspendedStarter = insertOrganization("Northbridge Estates Limited", null,
                "STARTER", "VERIFIED", "SUSPENDED");
        insertDirector(orgVerifiedEnterprise, "12345678901", "22134455667");
        seeded = true;
    }

    // --- platform scope: the single most likely failure in this slice ---

    @Test
    void superAdminSeesTenantsFromMultipleOrganizations() {
        String token = loginAsSuperAdmin();

        ResponseEntity<PageResponse<TenantSummaryDto>> response = getTenants(token, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<TenantSummaryDto> items = response.getBody().items();
        List<UUID> ids = items.stream().map(TenantSummaryDto::id).toList();
        assertThat(ids).contains(orgVerifiedEnterprise, orgUnderReviewGrowth, orgSuspendedStarter);
    }

    @Test
    void buyerGets403NotAnEmptyList() {
        String email = "buyer+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest(
                "Test", "Buyer", email, "+2348000000000", "correct horse battery staple", "NG", Currency.NGN);
        AuthResponse registered = restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class).getBody();
        assertThat(registered).isNotNull();

        // An empty list here would mean the permission check is missing
        // and only RLS happened to save us — see AGENTS.md.
        ResponseEntity<String> response = getRaw(registered.token(), "");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- filtering ---

    @Test
    void filtersByVerificationState() {
        String token = loginAsSuperAdmin();

        ResponseEntity<PageResponse<TenantSummaryDto>> response = getTenants(token, "?verificationState=under_review");

        List<UUID> ids = response.getBody().items().stream().map(TenantSummaryDto::id).toList();
        assertThat(ids).containsExactly(orgUnderReviewGrowth);
    }

    @Test
    void filtersByPlan() {
        String token = loginAsSuperAdmin();

        ResponseEntity<PageResponse<TenantSummaryDto>> response = getTenants(token, "?plan=starter");

        List<UUID> ids = response.getBody().items().stream().map(TenantSummaryDto::id).toList();
        assertThat(ids).containsExactly(orgSuspendedStarter);
    }

    // --- detail ---

    @Test
    void detailReturns404ForUnknownId() {
        String token = loginAsSuperAdmin();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/tenants/" + UUID.randomUUID(), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- pagination envelope ---

    @Test
    void hasMoreIsCorrectAtAPageBoundary() {
        String token = loginAsSuperAdmin();

        // 3 tenants seeded, limit=2: first page has 2 items and hasMore=true;
        // second page has the remaining 1 and hasMore=false.
        ResponseEntity<PageResponse<TenantSummaryDto>> firstPage = getTenants(token, "?limit=2");
        assertThat(firstPage.getBody().items()).hasSize(2);
        assertThat(firstPage.getBody().total()).isEqualTo(3);
        assertThat(firstPage.getBody().hasMore()).isTrue();
        assertThat(firstPage.getBody().cursor()).isNotNull();

        ResponseEntity<PageResponse<TenantSummaryDto>> secondPage = getTenants(token, "?limit=2&cursor=" + firstPage.getBody().cursor());
        assertThat(secondPage.getBody().items()).hasSize(1);
        assertThat(secondPage.getBody().hasMore()).isFalse();
        assertThat(secondPage.getBody().cursor()).isNull();
    }

    // --- the NDPR leak test ---

    @Test
    void serializationExcludesFullBvnAndGovernmentId() {
        String token = loginAsSuperAdmin();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<String> raw = restTemplate.exchange(
                "/api/admin/tenants/" + orgVerifiedEnterprise, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(raw.getBody())
                .as("full, unmasked NDPR-regulated values must never appear in a tenant detail response")
                .doesNotContain("12345678901")
                .doesNotContain("22134455667");

        ResponseEntity<TenantDetailDto> parsed = restTemplate.exchange(
                "/api/admin/tenants/" + orgVerifiedEnterprise, HttpMethod.GET, new HttpEntity<>(headers), TenantDetailDto.class);
        assertThat(parsed.getBody().directors()).isNotEmpty();
        assertThat(parsed.getBody().directors().get(0).idNumber()).endsWith("8901").doesNotContain("1234");
        assertThat(parsed.getBody().directors().get(0).bvn()).endsWith("5667").doesNotContain("2213");
    }

    // --- helpers ---

    private String loginAsSuperAdmin() {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().token();
    }

    // PageResponse<T> is a record (implicitly final — can't be subclassed
    // for a concrete Jackson target), so a generic response needs
    // ParameterizedTypeReference rather than a plain .class token.
    private static final ParameterizedTypeReference<PageResponse<TenantSummaryDto>> PAGE_OF_SUMMARIES =
            new ParameterizedTypeReference<>() {
            };

    private ResponseEntity<PageResponse<TenantSummaryDto>> getTenants(String token, String queryString) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(
                "/api/admin/tenants" + queryString, HttpMethod.GET, new HttpEntity<>(headers), PAGE_OF_SUMMARIES);
    }

    private ResponseEntity<String> getRaw(String token, String queryString) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(
                "/api/admin/tenants" + queryString, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    // Superuser, raw JDBC — deliberately bypasses RLS/TenantContext,
    // same pattern as RowLevelSecurityIT's fixture setup.
    private static Connection superuserConnection() {
        try {
            return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID insertOrganization(String registeredName, String tradingName, String plan, String verificationState, String status) {
        UUID id = UUID.randomUUID();
        String tradingNameLiteral = tradingName == null ? "NULL" : "'" + tradingName.replace("'", "''") + "'";
        try (Connection connection = superuserConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO organizations (
                        id, created_at, created_by, deleted,
                        registered_name, trading_name, rc_number, company_type, date_of_incorporation,
                        registered_street, registered_city, registered_state,
                        operating_street, operating_city, operating_state,
                        company_email, company_phone,
                        plan, marketplace_publishing, mlm_module, fx_rails,
                        status, verification_state
                    ) VALUES (
                        '%s', now(), 'it', false,
                        '%s', %s, 'RC-%s', 'LIMITED_LIABILITY', '2020-01-01',
                        'Test St', 'Lagos', 'Lagos',
                        'Test St', 'Lagos', 'Lagos',
                        'org-%s@example.com', '+2348000000000',
                        '%s', false, false, false, '%s', '%s'
                    )
                    """.formatted(id, registeredName.replace("'", "''"), tradingNameLiteral, id, id, plan, status, verificationState));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private static void insertDirector(UUID organizationId, String idNumber, String bvn) {
        try (Connection connection = superuserConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO directors (
                        id, tenant_id, created_at, created_by, deleted,
                        organization_id, full_name, role, nationality,
                        id_type, id_number, bvn, ownership_pct, is_beneficial_owner
                    ) VALUES (
                        '%s', '%s', now(), 'it', false,
                        '%s', 'Ifeoma Balogun', 'Executive Director', 'Nigerian',
                        'NIN', '%s', '%s', 60.00, true
                    )
                    """.formatted(UUID.randomUUID(), organizationId, organizationId, idNumber, bvn));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
