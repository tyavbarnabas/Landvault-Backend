package com.techcomfort.landvaultbackend.tenancy;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateSupportAccessGrantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SupportAccessGrantDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantPlanUpdateRequest;
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
 * {@code POST .../status}, {@code PUT .../plan}, and the support-access
 * create/list endpoints — tenancy slice B2. Same real-HTTP,
 * not-{@code @ServiceConnection} pattern as {@code AdminTenantWriteIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class AdminTenantStatusPlanSupportAccessIT {

    private static final String APP_ROLE = "landvault_app_status_plan_it";
    private static final String APP_ROLE_PASSWORD = "status-plan-it-app-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String ADMIN_PASSWORD = "correct horse battery staple";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

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

        registry.add("landvault.bootstrap.super-admin.enabled", () -> "true");
        registry.add("landvault.bootstrap.super-admin.email", () -> ADMIN_EMAIL);
        registry.add("landvault.bootstrap.super-admin.password", () -> ADMIN_PASSWORD);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    // --- status transitions ---

    @Test
    void activeToSuspendedWithReasonChangesStatusAndWritesAudit() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);

        ResponseEntity<TenantDetailDto> response = post(token, "/api/admin/tenants/" + orgId + "/status",
                new TenantStatusRequest("suspended", "Non-payment for 60 days."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("suspended");
        assertThat(singleString("SELECT status FROM organizations WHERE id = '" + orgId + "'")).isEqualTo("SUSPENDED");
        assertThat(countRows("audit_log_entries", "action = 'tenant.status_changed' AND target_id = '" + orgId + "'")).isEqualTo(1);
    }

    @Test
    void suspendedToActiveIsAllowed() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);
        post(token, "/api/admin/tenants/" + orgId + "/status", new TenantStatusRequest("suspended", "Non-payment."));

        ResponseEntity<TenantDetailDto> response = post(token, "/api/admin/tenants/" + orgId + "/status",
                new TenantStatusRequest("active", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("active");
    }

    @Test
    void transitionAwayFromOffboardedIsRejected() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);
        post(token, "/api/admin/tenants/" + orgId + "/status", new TenantStatusRequest("offboarded", "Contract ended."));

        ResponseEntity<String> response = postRaw(token, "/api/admin/tenants/" + orgId + "/status",
                new TenantStatusRequest("active", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_STATUS_TRANSITION");
        assertThat(singleString("SELECT status FROM organizations WHERE id = '" + orgId + "'")).isEqualTo("OFFBOARDED");
    }

    @Test
    void suspendedWithNoReasonIsRejected() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);

        ResponseEntity<String> response = postRaw(token, "/api/admin/tenants/" + orgId + "/status",
                new TenantStatusRequest("suspended", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("reason");
        assertThat(singleString("SELECT status FROM organizations WHERE id = '" + orgId + "'")).isEqualTo("ACTIVE");
    }

    @Test
    void settingTheSameStatusTwiceIsRejectedAsNoOp() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);

        ResponseEntity<String> response = postRaw(token, "/api/admin/tenants/" + orgId + "/status",
                new TenantStatusRequest("active", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_STATUS_TRANSITION");
    }

    @Test
    void statusChangeLeavesVerificationStateUntouched() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);
        String verificationStateBefore = singleString("SELECT verification_state FROM organizations WHERE id = '" + orgId + "'");

        post(token, "/api/admin/tenants/" + orgId + "/status", new TenantStatusRequest("suspended", "Non-payment."));

        assertThat(singleString("SELECT verification_state FROM organizations WHERE id = '" + orgId + "'"))
                .as("changing TenantStatus must never touch verificationState — see AGENTS.md Part 0")
                .isEqualTo(verificationStateBefore);
    }

    // --- plan/entitlements ---

    @Test
    void planUpdatePersistsAndWritesAuditWithOldAndNewValues() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);
        String statusBefore = singleString("SELECT status FROM organizations WHERE id = '" + orgId + "'");
        String verificationStateBefore = singleString("SELECT verification_state FROM organizations WHERE id = '" + orgId + "'");

        ResponseEntity<TenantDetailDto> response = put(token, "/api/admin/tenants/" + orgId + "/plan",
                new TenantPlanUpdateRequest("enterprise", true, true, true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().plan()).isEqualTo("enterprise");
        assertThat(response.getBody().entitlements().marketplacePublishing()).isTrue();
        assertThat(response.getBody().entitlements().mlmModule()).isTrue();
        assertThat(response.getBody().entitlements().fxRails()).isTrue();
        assertThat(singleString("SELECT plan FROM organizations WHERE id = '" + orgId + "'")).isEqualTo("ENTERPRISE");

        String detail = singleString("SELECT detail FROM audit_log_entries WHERE action = 'tenant.plan_changed' AND target_id = '" + orgId + "'");
        assertThat(detail).contains("starter").contains("enterprise");

        // Independence, the other direction: a plan change must never touch status/verificationState.
        assertThat(singleString("SELECT status FROM organizations WHERE id = '" + orgId + "'")).isEqualTo(statusBefore);
        assertThat(singleString("SELECT verification_state FROM organizations WHERE id = '" + orgId + "'")).isEqualTo(verificationStateBefore);
    }

    // --- support access ---

    @Test
    void supportAccessGrantHasCorrectExpiryAndGranteeFromToken() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);
        UUID adminUserId = singleUuid("SELECT id FROM users WHERE email = '" + ADMIN_EMAIL.toLowerCase() + "'");

        ResponseEntity<SupportAccessGrantDto> response = postSupportAccess(token, orgId,
                new CreateSupportAccessGrantRequest("Investigating a stuck payment webhook.", 45));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        SupportAccessGrantDto grant = response.getBody();
        assertThat(grant.tenantId()).isEqualTo(orgId);
        assertThat(grant.reason()).isEqualTo("Investigating a stuck payment webhook.");
        assertThat(grant.expiresAt()).isEqualTo(grant.requestedAt().plusSeconds(45 * 60L));

        assertThat(singleUuid("SELECT granted_to_user_id FROM support_access_grants WHERE id = '" + grant.id() + "'"))
                .as("grantedToUserId must be the authenticated caller, never client-supplied")
                .isEqualTo(adminUserId);

        assertThat(countRows("audit_log_entries",
                "action = 'tenant.support_access_granted' AND target_id = '" + orgId + "' AND privileged = true"))
                .as("a support-access audit entry must be privileged = true")
                .isEqualTo(1);
    }

    @Test
    void supportAccessGrantDefaultsToThirtyMinutes() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);

        ResponseEntity<SupportAccessGrantDto> response = postSupportAccess(token, orgId,
                new CreateSupportAccessGrantRequest("Quick look at a listing conflict.", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        SupportAccessGrantDto grant = response.getBody();
        assertThat(grant.expiresAt()).isEqualTo(grant.requestedAt().plusSeconds(30 * 60L));
    }

    @Test
    void listingSupportAccessReturnsMostRecentFirst() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);
        postSupportAccess(token, orgId, new CreateSupportAccessGrantRequest("First look.", 30));
        postSupportAccess(token, orgId, new CreateSupportAccessGrantRequest("Second look.", 30));

        ResponseEntity<SupportAccessGrantDto[]> response = restTemplate.exchange(
                "/api/admin/tenants/" + orgId + "/support-access", HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), SupportAccessGrantDto[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> reasonsInOrder = List.of(response.getBody()).stream().map(SupportAccessGrantDto::reason).toList();
        assertThat(reasonsInOrder).containsExactly("Second look.", "First look.");
    }

    // --- buyer 403 ---

    @Test
    void buyerGets403OnStatusPlanAndSupportAccessEndpoints() {
        String superAdminToken = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(superAdminToken);
        String buyerToken = registerBuyer();

        assertThat(postRaw(buyerToken, "/api/admin/tenants/" + orgId + "/status", new TenantStatusRequest("suspended", "x")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(putRaw(buyerToken, "/api/admin/tenants/" + orgId + "/plan", new TenantPlanUpdateRequest("growth", false, false, false)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postRaw(buyerToken, "/api/admin/tenants/" + orgId + "/support-access", new CreateSupportAccessGrantRequest("x", null)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange("/api/admin/tenants/" + orgId + "/support-access", HttpMethod.GET,
                        new HttpEntity<>(authHeaders(buyerToken)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- helpers ---

    private UUID createOrgAndGetId(String token) {
        String rcNumber = "RC-" + UUID.randomUUID();
        CompanyIdentityDto identity = new CompanyIdentityDto(
                "Test Company " + rcNumber, null, rcNumber, "Limited Liability (Ltd)", "2020-01-01",
                new AddressDto("1 Broad Street", "Lagos", "Lagos"),
                new AddressDto("1 Broad Street", "Lagos", "Lagos"),
                List.of("Lagos"));
        PrimaryContactDto primaryContact = new PrimaryContactDto(
                "Some Director", "Chief Executive Officer", "ed+" + UUID.randomUUID() + "@example.com",
                "+2348000000001", "NIN", "12345678901");
        CompanyPresenceDto presence = new CompanyPresenceDto(
                "org+" + rcNumber + "@example.com", "+2348000000002", null,
                new SocialsDto(null, null, null, null));
        CreateTenantRequest request = new CreateTenantRequest(identity, primaryContact, presence, "starter");

        ResponseEntity<TenantDetailDto> response = post(token, "/api/admin/tenants", request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private String loginAsSuperAdmin() {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().token();
    }

    private String registerBuyer() {
        String email = "buyer+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest(
                "Test", "Buyer", email, "+2348000000000", "correct horse battery staple", "NG", Currency.NGN);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().token();
    }

    private ResponseEntity<TenantDetailDto> post(String token, String path, Object body) {
        return restTemplate.exchange(path, HttpMethod.POST, entity(token, body), TenantDetailDto.class);
    }

    private ResponseEntity<TenantDetailDto> put(String token, String path, Object body) {
        return restTemplate.exchange(path, HttpMethod.PUT, entity(token, body), TenantDetailDto.class);
    }

    private ResponseEntity<String> postRaw(String token, String path, Object body) {
        return restTemplate.exchange(path, HttpMethod.POST, entity(token, body), String.class);
    }

    private ResponseEntity<String> putRaw(String token, String path, Object body) {
        return restTemplate.exchange(path, HttpMethod.PUT, entity(token, body), String.class);
    }

    private ResponseEntity<SupportAccessGrantDto> postSupportAccess(String token, UUID orgId, CreateSupportAccessGrantRequest request) {
        return restTemplate.exchange("/api/admin/tenants/" + orgId + "/support-access", HttpMethod.POST,
                entity(token, request), SupportAccessGrantDto.class);
    }

    private HttpEntity<Object> entity(String token, Object body) {
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    // --- raw JDBC assertion helpers — superuser, deliberately bypasses RLS, same pattern as AdminTenantWriteIT ---

    private static Connection superuserConnection() {
        try {
            return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long countRows(String fromClause, String whereClause) {
        try (Connection connection = superuserConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + fromClause + " WHERE " + whereClause)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String singleString(String sql) {
        try (Connection connection = superuserConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID singleUuid(String sql) {
        String value = singleString(sql);
        return value == null ? null : UUID.fromString(value);
    }
}
