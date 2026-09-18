package com.techcomfort.landvaultbackend.tenancy;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.ResubmitDocumentRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import com.techcomfort.landvaultbackend.tenancy.dto.VerificationDecisionRequest;
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
 * {@code POST /api/admin/tenants} and the four verification-lifecycle write
 * endpoints, through real HTTP — see AGENTS.md Part 5. Same
 * deliberately-not-{@code @ServiceConnection} pattern as
 * {@code AdminTenantControllerIT}/{@code RowLevelSecurityIT}: the app
 * connects as the restricted role, fixtures are seeded/verified with a raw
 * superuser JDBC connection that bypasses RLS entirely.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class AdminTenantWriteIT {

    private static final String APP_ROLE = "landvault_app_tenant_write_it";
    private static final String APP_ROLE_PASSWORD = "tenant-write-it-app-password";
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

    // --- create ---

    @Test
    void createTenantCreatesOrgExecutiveDirectorAndAuditEntry() {
        String token = loginAsSuperAdmin();
        String rcNumber = "RC-" + UUID.randomUUID();
        String contactEmail = "ed+" + UUID.randomUUID() + "@example.com";

        ResponseEntity<TenantDetailDto> response = create(token, sampleCreateRequest(rcNumber, contactEmail, "Ada Okafor"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TenantDetailDto tenant = response.getBody();
        assertThat(tenant.status()).isEqualTo("active");
        assertThat(tenant.verificationState()).isEqualTo("created");
        UUID orgId = tenant.id();

        assertThat(countRows("organizations", "id = '" + orgId + "' AND rc_number = '" + rcNumber + "'")).isEqualTo(1);

        UUID edUserId = singleUuid(
                "SELECT id FROM users WHERE tenant_id = '" + orgId + "' AND email = '" + contactEmail.toLowerCase() + "'");
        assertThat(edUserId).isNotNull();
        assertThat(singleString("SELECT status FROM users WHERE id = '" + edUserId + "'")).isEqualTo("PENDING_VERIFICATION");
        assertThat(singleString("SELECT first_name FROM users WHERE id = '" + edUserId + "'")).isEqualTo("Ada");
        assertThat(singleString("SELECT last_name FROM users WHERE id = '" + edUserId + "'")).isEqualTo("Okafor");

        assertThat(countRows("user_roles ur JOIN roles r ON r.id = ur.role_id",
                "ur.user_id = '" + edUserId + "' AND r.code = 'executive_director' AND ur.scoped_branch_id IS NULL"))
                .isEqualTo(1);

        assertThat(countRows("audit_log_entries",
                "action = 'tenant.created' AND target_id = '" + orgId + "' AND tenant_id = '" + orgId + "'"))
                .isEqualTo(1);
    }

    @Test
    void duplicateRcNumberIsRejectedWithNoPartialState() {
        String token = loginAsSuperAdmin();
        String rcNumber = "RC-" + UUID.randomUUID();
        ResponseEntity<TenantDetailDto> first = create(token, sampleCreateRequest(rcNumber, "first+" + UUID.randomUUID() + "@example.com", "First ED"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = createRaw(token, sampleCreateRequest(rcNumber, "second+" + UUID.randomUUID() + "@example.com", "Second ED"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("RC_NUMBER_ALREADY_REGISTERED");
        assertThat(countRows("organizations", "rc_number = '" + rcNumber + "'")).isEqualTo(1);
    }

    @Test
    void buyerGets403OnCreateTenant() {
        String buyerToken = registerBuyer();

        ResponseEntity<String> response = createRaw(buyerToken, sampleCreateRequest("RC-" + UUID.randomUUID(), "buyer-attempt@example.com", "Nobody"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void duplicatePrimaryContactEmailIsRejectedWithNoPartialState() {
        String superAdminToken = loginAsSuperAdmin();
        String existingEmail = "taken+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest(
                "Existing", "Buyer", existingEmail, "+2348000000000", "correct horse battery staple", "NG", Currency.NGN);
        assertThat(restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        String rcNumber = "RC-" + UUID.randomUUID();
        ResponseEntity<String> response = createRaw(superAdminToken, sampleCreateRequest(rcNumber, existingEmail, "Someone Else"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("EMAIL_ALREADY_REGISTERED");
        // Rolled back together, not left as a half-created tenant.
        assertThat(countRows("organizations", "rc_number = '" + rcNumber + "'")).isEqualTo(0);
    }

    // --- submit-documents ---

    @Test
    void submitDocumentsRejectedWithZeroDocuments() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);

        ResponseEntity<String> response = postRaw(token, "/api/admin/tenants/" + orgId + "/submit-documents", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("NO_DOCUMENTS_UPLOADED");
    }

    // --- full happy path ---

    @Test
    void fullHappyPathSubmitReviewApproveVerifies() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);
        UUID documentId = insertDocument(orgId, "cac-certificate.pdf");

        ResponseEntity<TenantDetailDto> submitted = post(token, "/api/admin/tenants/" + orgId + "/submit-documents", null);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(submitted.getBody().verificationState()).isEqualTo("documents_submitted");

        ResponseEntity<TenantDetailDto> reviewed = post(token, "/api/admin/tenants/" + orgId + "/begin-review", null);
        assertThat(reviewed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reviewed.getBody().verificationState()).isEqualTo("under_review");
        assertThat(singleString("SELECT reviewer_user_id FROM organizations WHERE id = '" + orgId + "'")).isNotNull();

        ResponseEntity<TenantDetailDto> approved = post(token, "/api/admin/tenants/" + orgId + "/verification-decision",
                new VerificationDecisionRequest("approved", null, null));
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().verificationState()).isEqualTo("verified");

        assertThat(countRows("verification_decisions", "organization_id = '" + orgId + "' AND decision = 'APPROVED'")).isEqualTo(1);
        // Approval cascades to every document, matching the real frontend's
        // own mock behavior — a "verified" tenant shouldn't have its
        // evidence still sitting at "pending".
        assertThat(singleString("SELECT status FROM organization_documents WHERE id = '" + documentId + "'")).isEqualTo("VERIFIED");
    }

    @Test
    void rejectedMarksNamedDocumentsRejectedAndOthersVerified() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);
        UUID goodDocument = insertDocument(orgId, "tin.pdf");
        UUID badDocument = insertDocument(orgId, "cac-certificate-blurry.pdf");
        post(token, "/api/admin/tenants/" + orgId + "/submit-documents", null);
        post(token, "/api/admin/tenants/" + orgId + "/begin-review", null);

        ResponseEntity<TenantDetailDto> response = post(token, "/api/admin/tenants/" + orgId + "/verification-decision",
                new VerificationDecisionRequest("rejected", "CAC certificate is unreadable.", List.of(badDocument)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().verificationState()).isEqualTo("rejected");
        assertThat(singleString("SELECT status FROM organization_documents WHERE id = '" + badDocument + "'")).isEqualTo("REJECTED");
        assertThat(singleString("SELECT rejection_reason FROM organization_documents WHERE id = '" + badDocument + "'"))
                .isEqualTo("CAC certificate is unreadable.");
        assertThat(singleString("SELECT status FROM organization_documents WHERE id = '" + goodDocument + "'")).isEqualTo("VERIFIED");
    }

    // --- the REQUEST_MORE_INFO exception, tested explicitly over real HTTP ---

    @Test
    void requestMoreInfoLeavesVerificationStateUnderReview() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);
        UUID documentId = insertDocument(orgId, "tin.pdf");
        post(token, "/api/admin/tenants/" + orgId + "/submit-documents", null);
        post(token, "/api/admin/tenants/" + orgId + "/begin-review", null);

        ResponseEntity<TenantDetailDto> response = post(token, "/api/admin/tenants/" + orgId + "/verification-decision",
                new VerificationDecisionRequest("request_more_info", "Scan of the TIN certificate is illegible.", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().verificationState())
                .as("REQUEST_MORE_INFO must not transition verificationState — see AGENTS.md")
                .isEqualTo("under_review");
        assertThat(countRows("verification_decisions", "organization_id = '" + orgId + "' AND decision = 'REQUEST_MORE_INFO'")).isEqualTo(1);
        assertThat(singleString("SELECT verification_state FROM organizations WHERE id = '" + orgId + "'")).isEqualTo("UNDER_REVIEW");
        // Document statuses aren't touched either — same "leaves things
        // exactly as they were" rule as verificationState.
        assertThat(singleString("SELECT status FROM organization_documents WHERE id = '" + documentId + "'")).isEqualTo("PENDING");
    }

    @Test
    void rejectedWithoutReasonIsRejectedAsInvalid() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);
        insertDocument(orgId, "proof-of-address.pdf");
        post(token, "/api/admin/tenants/" + orgId + "/submit-documents", null);
        post(token, "/api/admin/tenants/" + orgId + "/begin-review", null);

        ResponseEntity<String> response = postRaw(token, "/api/admin/tenants/" + orgId + "/verification-decision",
                new VerificationDecisionRequest("rejected", null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("reason");
    }

    @Test
    void decisionAgainstCreatedStateTenantIsRejected() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token); // still CREATED — never submitted

        ResponseEntity<String> response = postRaw(token, "/api/admin/tenants/" + orgId + "/verification-decision",
                new VerificationDecisionRequest("approved", null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_VERIFICATION_TRANSITION");
    }

    // --- resubmit ---

    @Test
    void resubmitResetsDocumentStatusButNotVerificationState() {
        String token = loginAsSuperAdmin();
        UUID orgId = createOrgAndGetId(token);
        UUID documentId = insertDocument(orgId, "cac-certificate.pdf");
        post(token, "/api/admin/tenants/" + orgId + "/submit-documents", null);
        execute("UPDATE organization_documents SET status = 'REJECTED', rejection_reason = 'blurry' WHERE id = '" + documentId + "'");

        ResponseEntity<TenantDetailDto> response = post(token,
                "/api/admin/tenants/" + orgId + "/documents/" + documentId + "/resubmit",
                new ResubmitDocumentRequest("cac-certificate-v2.pdf", 2048));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(singleString("SELECT status FROM organization_documents WHERE id = '" + documentId + "'")).isEqualTo("PENDING");
        assertThat(singleString("SELECT file_name FROM organization_documents WHERE id = '" + documentId + "'")).isEqualTo("cac-certificate-v2.pdf");
        // resubmit does not itself advance verificationState — see AGENTS.md.
        assertThat(response.getBody().verificationState()).isEqualTo("documents_submitted");
        assertThat(singleString("SELECT verification_state FROM organizations WHERE id = '" + orgId + "'")).isEqualTo("DOCUMENTS_SUBMITTED");
    }

    // --- helpers ---

    private UUID createOrgAndGetId(String token) {
        ResponseEntity<TenantDetailDto> response = create(token,
                sampleCreateRequest("RC-" + UUID.randomUUID(), "ed+" + UUID.randomUUID() + "@example.com", "Some Director"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private CreateTenantRequest sampleCreateRequest(String rcNumber, String contactEmail, String contactFullName) {
        CompanyIdentityDto identity = new CompanyIdentityDto(
                "Test Company " + rcNumber, null, rcNumber, "Limited Liability (Ltd)", "2020-01-01",
                new AddressDto("1 Broad Street", "Lagos", "Lagos"),
                new AddressDto("1 Broad Street", "Lagos", "Lagos"),
                List.of("Lagos"));
        PrimaryContactDto primaryContact = new PrimaryContactDto(
                contactFullName, "Chief Executive Officer", contactEmail, "+2348000000001", "NIN", "12345678901");
        CompanyPresenceDto presence = new CompanyPresenceDto(
                "org+" + rcNumber + "@example.com", "+2348000000002", null,
                new SocialsDto(null, null, null, null));
        return new CreateTenantRequest(identity, primaryContact, presence, "starter");
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

    private ResponseEntity<TenantDetailDto> create(String token, CreateTenantRequest request) {
        return post(token, "/api/admin/tenants", request);
    }

    private ResponseEntity<String> createRaw(String token, CreateTenantRequest request) {
        return postRaw(token, "/api/admin/tenants", request);
    }

    private ResponseEntity<TenantDetailDto> post(String token, String path, Object body) {
        return restTemplate.exchange(path, HttpMethod.POST, entity(token, body), TenantDetailDto.class);
    }

    private ResponseEntity<String> postRaw(String token, String path, Object body) {
        return restTemplate.exchange(path, HttpMethod.POST, entity(token, body), String.class);
    }

    private HttpEntity<Object> entity(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    // --- raw JDBC fixture/assertion helpers — superuser, deliberately bypasses RLS, same pattern as AdminTenantControllerIT ---

    private static Connection superuserConnection() {
        try {
            return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID insertDocument(UUID organizationId, String fileName) {
        UUID id = UUID.randomUUID();
        try (Connection connection = superuserConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO organization_documents (
                        id, tenant_id, created_at, created_by, deleted,
                        organization_id, type, file_name, size, status, uploaded_at
                    ) VALUES (
                        '%s', '%s', now(), 'it', false,
                        '%s', 'CAC_CERTIFICATE', '%s', 1024, 'PENDING', now()
                    )
                    """.formatted(id, organizationId, organizationId, fileName));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private static void execute(String sql) {
        try (Connection connection = superuserConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
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
