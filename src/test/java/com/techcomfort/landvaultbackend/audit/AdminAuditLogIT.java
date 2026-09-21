package com.techcomfort.landvaultbackend.audit;

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
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.techcomfort.landvaultbackend.audit.dto.AuditLogEntryDto;
import com.techcomfort.landvaultbackend.common.PageResponse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit log read endpoint, against entries produced by real tenancy
 * writes rather than hand-inserted rows — if the write path and the read path
 * ever disagree about a field, that only shows up this way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class AdminAuditLogIT {

    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String ADMIN_PASSWORD = "correct horse battery staple";

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "integration-test-signing-secret-of-at-least-32-bytes");
        registry.add("landvault.bootstrap.super-admin.enabled", () -> "true");
        registry.add("landvault.bootstrap.super-admin.email", () -> ADMIN_EMAIL);
        registry.add("landvault.bootstrap.super-admin.password", () -> ADMIN_PASSWORD);
        registry.add("landvault.bootstrap.super-admin.first-name", () -> "Ada");
        registry.add("landvault.bootstrap.super-admin.last-name", () -> "Nwosu");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private static final ParameterizedTypeReference<PageResponse<AuditLogEntryDto>> PAGE =
            new ParameterizedTypeReference<>() {
            };

    // --- the entries a real write produces ---

    @Test
    void tenantCreationAppearsWithAResolvedActorName() {
        String token = loginAsSuperAdmin();
        UUID tenantId = createTenant(token);

        PageResponse<AuditLogEntryDto> page = auditLog(token, "?tenantId=" + tenantId);

        AuditLogEntryDto created = page.items().stream()
                .filter(e -> e.action().equals("tenant.created"))
                .findFirst().orElseThrow();
        assertThat(created.targetType()).isEqualTo("organization");
        assertThat(created.targetId()).isEqualTo(tenantId);
        assertThat(created.actorName())
                .as("raw UUIDs are unreadable in an activity stream")
                .isEqualTo("Ada Nwosu");
        assertThat(created.privileged()).isFalse();
    }

    // The filter most likely to be the reason someone opens this screen.
    @Test
    void filteringByPrivilegedIsolatesSupportAccessEntries() {
        String token = loginAsSuperAdmin();
        UUID tenantId = createTenant(token);
        grantSupportAccess(token, tenantId);

        PageResponse<AuditLogEntryDto> privileged = auditLog(token, "?privileged=true");

        assertThat(privileged.items()).isNotEmpty();
        assertThat(privileged.items()).allSatisfy(entry -> {
            assertThat(entry.privileged()).isTrue();
            assertThat(entry.action()).isEqualTo("tenant.support_access_granted");
        });

        PageResponse<AuditLogEntryDto> ordinary = auditLog(token, "?privileged=false");
        assertThat(ordinary.items()).isNotEmpty();
        assertThat(ordinary.items()).allSatisfy(entry -> assertThat(entry.privileged()).isFalse());
    }

    @Test
    void filteringByTenantReturnsOnlyThatTenantsEntries() {
        String token = loginAsSuperAdmin();
        UUID first = createTenant(token);
        UUID second = createTenant(token);

        PageResponse<AuditLogEntryDto> page = auditLog(token, "?tenantId=" + first);

        assertThat(page.items()).isNotEmpty();
        assertThat(page.items()).allSatisfy(entry -> assertThat(entry.tenantId()).isEqualTo(first));
        assertThat(page.items()).noneSatisfy(entry -> assertThat(entry.tenantId()).isEqualTo(second));
    }

    @Test
    void filteringByTargetReturnsOneOrganizationsHistory() {
        String token = loginAsSuperAdmin();
        UUID tenantId = createTenant(token);
        grantSupportAccess(token, tenantId);

        PageResponse<AuditLogEntryDto> page = auditLog(
                token, "?targetType=organization&targetId=" + tenantId);

        assertThat(page.items()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(page.items()).allSatisfy(entry -> {
            assertThat(entry.targetType()).isEqualTo("organization");
            assertThat(entry.targetId()).isEqualTo(tenantId);
        });
        assertThat(page.items()).extracting(AuditLogEntryDto::action)
                .contains("tenant.created", "tenant.support_access_granted");
    }

    @Test
    void filteringByActionNarrowsToThatActionAlone() {
        String token = loginAsSuperAdmin();
        createTenant(token);

        PageResponse<AuditLogEntryDto> page = auditLog(token, "?action=tenant.created");

        assertThat(page.items()).isNotEmpty();
        assertThat(page.items()).allSatisfy(entry -> assertThat(entry.action()).isEqualTo("tenant.created"));
    }

    @Test
    void entriesAreMostRecentFirst() {
        String token = loginAsSuperAdmin();
        createTenant(token);
        createTenant(token);

        List<AuditLogEntryDto> items = auditLog(token, "").items();

        assertThat(items).hasSizeGreaterThan(1);
        assertThat(items).isSortedAccordingTo((a, b) -> b.occurredAt().compareTo(a.occurredAt()));
    }

    // --- date range, at both boundaries ---

    @Test
    void dateRangeFilteringIsInclusiveAtBothEnds() {
        String token = loginAsSuperAdmin();
        UUID tenantId = createTenant(token);

        AuditLogEntryDto entry = auditLog(token, "?tenantId=" + tenantId).items().getFirst();
        Instant at = entry.occurredAt();

        assertThat(auditLog(token, "?tenantId=" + tenantId + "&occurredAfter=" + at).items())
                .as("occurredAfter is inclusive")
                .extracting(AuditLogEntryDto::id).contains(entry.id());
        assertThat(auditLog(token, "?tenantId=" + tenantId + "&occurredBefore=" + at).items())
                .as("occurredBefore is inclusive")
                .extracting(AuditLogEntryDto::id).contains(entry.id());

        assertThat(auditLog(token, "?tenantId=" + tenantId + "&occurredAfter=" + at.plusSeconds(60)).items())
                .isEmpty();
        assertThat(auditLog(token, "?tenantId=" + tenantId + "&occurredBefore=" + at.minusSeconds(60)).items())
                .isEmpty();
    }

    // --- envelope and paging ---

    @Test
    void theEnvelopeIsThePageResponseShapeAndHasMoreIsCorrectAtABoundary() {
        String token = loginAsSuperAdmin();
        createTenant(token);
        createTenant(token);

        ResponseEntity<String> raw = restTemplate.exchange(
                "/api/admin/audit-log?limit=1", HttpMethod.GET, new HttpEntity<>(auth(token)), String.class);
        assertThat(raw.getBody())
                .contains("\"items\"").contains("\"total\"").contains("\"hasMore\"")
                .as("never Spring's own Page shape")
                .doesNotContain("\"totalElements\"").doesNotContain("\"pageable\"");

        PageResponse<AuditLogEntryDto> firstPage = auditLog(token, "?limit=1");
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.total()).isGreaterThan(1);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.cursor()).isNotNull();

        PageResponse<AuditLogEntryDto> nextPage = auditLog(token, "?limit=1&cursor=" + firstPage.cursor());
        assertThat(nextPage.items()).hasSize(1);
        assertThat(nextPage.items().getFirst().id()).isNotEqualTo(firstPage.items().getFirst().id());

        // The final page must report hasMore=false rather than a cursor that
        // leads nowhere.
        long total = firstPage.total();
        PageResponse<AuditLogEntryDto> lastPage = auditLog(token, "?limit=" + total);
        assertThat(lastPage.hasMore()).isFalse();
        assertThat(lastPage.cursor()).isNull();
    }

    @Test
    void anEntryWhoseActorNoLongerExistsStillRenders() {
        String token = loginAsSuperAdmin();
        UUID tenantId = createTenant(token);

        // Repoint the entry at an actor that was never a user — the same
        // shape as an account deleted after the fact.
        UUID ghost = UUID.randomUUID();
        execute("UPDATE audit_log_entries SET actor_user_id = '" + ghost
                + "' WHERE target_id = '" + tenantId + "' AND action = 'tenant.created'");

        AuditLogEntryDto entry = auditLog(token, "?tenantId=" + tenantId).items().stream()
                .filter(e -> e.action().equals("tenant.created"))
                .findFirst().orElseThrow();

        assertThat(entry.actorUserId()).isEqualTo(ghost);
        assertThat(entry.actorName())
                .as("audit entries outlive the accounts that created them")
                .isEqualTo("Unknown user");
    }

    // --- authorization ---

    @Test
    void aBuyerGets403RatherThanAnEmptyList() {
        String buyerToken = registerBuyer();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/audit-log", HttpMethod.GET, new HttpEntity<>(auth(buyerToken)), String.class);

        assertThat(response.getStatusCode())
                .as("an empty list would mean only RLS was saving us and the permission check is missing")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void holdingTenantsViewWithoutAuditViewIsAlsoForbidden() {
        String token = loginAsSuperAdmin();
        // Strip only admin.audit.view from the Super Admin's role; every
        // other admin permission stays, so this isolates the audit check
        // rather than testing "no permissions at all".
        execute("DELETE FROM role_permissions WHERE permission_id IN "
                + "(SELECT id FROM permissions WHERE code = 'admin.audit.view')");
        try {
            String strippedToken = loginAsSuperAdmin();
            assertThat(restTemplate.exchange("/api/admin/audit-log", HttpMethod.GET,
                    new HttpEntity<>(auth(strippedToken)), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(restTemplate.exchange("/api/admin/tenants", HttpMethod.GET,
                    new HttpEntity<>(auth(strippedToken)), String.class).getStatusCode())
                    .as("admin.tenants.view is untouched, so this must still work")
                    .isEqualTo(HttpStatus.OK);
        } finally {
            execute("INSERT INTO role_permissions (id, created_at, created_by, deleted, role_id, permission_id) "
                    + "SELECT gen_random_uuid(), now(), 'system', false, r.id, p.id FROM roles r, permissions p "
                    + "WHERE r.code IN ('super_admin','compliance_officer') AND p.code = 'admin.audit.view'");
        }
        assertThat(token).isNotBlank();
    }

    @Test
    void thereIsNoWritePathOnTheAuditLog() {
        String token = loginAsSuperAdmin();

        // An append-only log must not be revisable through the API. These
        // should not resolve to a handler at all.
        assertThat(restTemplate.exchange("/api/admin/audit-log", HttpMethod.DELETE,
                new HttpEntity<>(auth(token)), String.class).getStatusCode())
                .isNotEqualTo(HttpStatus.OK);
        assertThat(restTemplate.exchange("/api/admin/audit-log", HttpMethod.POST,
                entity(token, "{}"), String.class).getStatusCode())
                .isNotEqualTo(HttpStatus.OK);
    }

    // --- helpers ---

    private PageResponse<AuditLogEntryDto> auditLog(String token, String queryString) {
        ResponseEntity<PageResponse<AuditLogEntryDto>> response = restTemplate.exchange(
                "/api/admin/audit-log" + queryString, HttpMethod.GET, new HttpEntity<>(auth(token)), PAGE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
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
                "Test", "Buyer", email, "+2348000000000", ADMIN_PASSWORD, "NG", Currency.NGN);
        return restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class).getBody().token();
    }

    private UUID createTenant(String token) {
        String rcNumber = "RC-" + UUID.randomUUID();
        CreateTenantRequest request = new CreateTenantRequest(
                new CompanyIdentityDto("Audit Co " + rcNumber, null, rcNumber, "Limited Liability (Ltd)",
                        "2020-01-01",
                        new AddressDto("1 Broad Street", "Lagos", "Lagos"),
                        new AddressDto("1 Broad Street", "Lagos", "Lagos"),
                        List.of("Lagos")),
                new PrimaryContactDto("Some Director", "Chief Executive Officer",
                        "ed+" + UUID.randomUUID() + "@example.com", "+2348000000001", "NIN", "12345678901"),
                new CompanyPresenceDto("org+" + rcNumber + "@example.com", "+2348000000002", null,
                        new SocialsDto(null, null, null, null)),
                "starter");

        ResponseEntity<TenantDetailDto> response = restTemplate.exchange(
                "/api/admin/tenants", HttpMethod.POST, entity(token, request), TenantDetailDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private void grantSupportAccess(String token, UUID tenantId) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/tenants/" + tenantId + "/support-access", HttpMethod.POST,
                entity(token, new CreateSupportAccessGrantRequest("Investigating a stuck webhook.", 30)),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private HttpEntity<Object> entity(String token, Object body) {
        HttpHeaders headers = auth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

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
