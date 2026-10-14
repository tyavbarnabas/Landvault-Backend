package com.techcomfort.landvaultbackend.tenancy;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.OnboardingRequestDto;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.internal.service.OnboardingProperties;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The public "request onboarding" form and the Super Admin queue. Wired to the
 * restricted app role, so changeset 083's policies — anyone may submit, only
 * platform staff may read — are what's actually enforcing it. See AGENTS.md,
 * "Onboarding requests".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class OnboardingRequestUnderRlsIT {

    private static final String APP_ROLE = "landvault_app_onboarding_it";
    private static final String APP_ROLE_PASSWORD = "onboarding-it-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple 9";

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
        registry.add("landvault.bootstrap.super-admin.password", () -> PASSWORD);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OnboardingProperties onboardingProperties;

    @Test
    void anyoneCanAskToBeOnboardedAndNothingIsCreated() {
        String email = uniqueEmail();
        ResponseEntity<String> response = submit(submission("Estintin Group", email, "[\"Lagos State\", \"FCT\"]"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID id = UUID.fromString(queryString("SELECT id FROM onboarding_requests WHERE contact_email = '" + email + "'"));
        OnboardingRequestDto stored = get(id);
        assertThat(stored.status()).isEqualTo("new");
        assertThat(stored.statesOfOperation()).as("standardised")
                .containsExactly("Lagos", "Federal Capital Territory (Abuja)");
        assertThat(stored.rcNumber()).as("optional").isNull();
        assertThat(stored.preferredContactMethod()).isEqualTo("phone");
        assertThat(stored.organizationId()).isNull();

        assertThat(queryString("SELECT count(*) FROM organizations WHERE registered_name = 'Estintin Group'"))
                .as("a request creates no company").isEqualTo("0");
        assertThat(queryString("SELECT count(*) FROM users WHERE lower(email) = '" + email + "'"))
                .as("and no account").isEqualTo("0");
        assertThat(queryString("SELECT actor_user_id IS NULL FROM audit_log_entries "
                + "WHERE action = 'onboarding_request.received' AND target_id = '" + id + "'"))
                .as("a system entry: the submitter has no account").isEqualTo("t");
    }

    /** One open request per email, and the reply never says so — the form can't flood an inbox or probe who's known. */
    @Test
    void aRepeatFromAnEmailWithAnOpenRequestGetsTheSameReplyAndIsNotStored() {
        String email = uniqueEmail();
        ResponseEntity<String> first = submit(submission("First Co", email, "[]"));
        ResponseEntity<String> repeat = submit(submission("Second Co", email.toUpperCase(), "[]"));

        assertThat(repeat.getStatusCode()).isEqualTo(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(repeat.getBody()).isEqualTo(first.getBody());
        assertThat(countFor(email)).isEqualTo("1");
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'onboarding_request.received' "
                + "AND detail LIKE '%Second Co%'")).as("nothing recorded for the dropped repeat").isEqualTo("0");

        // Once the first is closed, the same email may ask again.
        UUID id = idFor(email);
        decline(id, "Not a developer");
        assertThat(submit(submission("Second Co", email, "[]")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(countFor(email)).isEqualTo("2");
    }

    @Test
    void badSubmissionsAreRefusedWithTheFieldNamed() {
        String missingPhone = """
                {"companyName":"Co","contactName":"A","contactEmail":"%s","preferredContactMethod":"email"}
                """.formatted(uniqueEmail());
        ResponseEntity<String> noPhone = submit(missingPhone);
        assertThat(noPhone.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noPhone.getBody()).contains("contactPhone");

        ResponseEntity<String> badState = submit(submission("Co", uniqueEmail(), "[\"Atlantis\"]"));
        assertThat(badState.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badState.getBody()).contains("statesOfOperation").contains("Atlantis");

        ResponseEntity<String> badMethod = submit(submission("Co", uniqueEmail(), "[]").replace("\"phone\"", "\"pigeon\""));
        assertThat(badMethod.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badMethod.getBody()).contains("INVALID_VALUE");
    }

    @Test
    void onlyPlatformStaffCanSeeOrHandleRequests() {
        String email = uniqueEmail();
        submit(submission("Private Co", email, "[]"));
        UUID id = idFor(email);

        assertThat(restTemplate.getForEntity("/api/admin/onboarding-requests", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        String buyer = buyerToken();
        assertThat(restTemplate.exchange("/api/admin/onboarding-requests/" + id, HttpMethod.GET,
                entity(buyer, null), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange("/api/admin/onboarding-requests/" + id + "/decline", HttpMethod.POST,
                entity(buyer, "{\"reason\":\"x\"}"), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** RLS, checked as the app role itself: with no scope it may submit, never read, and never submit a decided request. */
    @Test
    void theDatabaseLetsAnyoneSubmitButOnlyPlatformStaffRead() {
        submit(submission("Hidden Co", uniqueEmail(), "[]"));

        assertThat(queryAsAppRole("SELECT count(*) FROM onboarding_requests")).as("no scope, no rows").isEqualTo("0");
        assertThatThrownBy(() -> executeAsAppRole("""
                INSERT INTO onboarding_requests (id, created_at, deleted, company_name, contact_name, contact_email,
                    contact_phone, preferred_contact_method, status, organization_id)
                VALUES (gen_random_uuid(), now(), false, 'Sneaky', 'A', 'sneaky@example.com', '0800', 'EMAIL',
                    'CONVERTED', (SELECT id FROM organizations LIMIT 1))
                """)).as("a decided request can't be submitted").hasMessageContaining("row-level security");
        assertThatThrownBy(() -> executeAsAppRole("DELETE FROM onboarding_requests"))
                .as("never deleted").hasMessageContaining("permission denied");
    }

    @Test
    void anAdminRecordsContactThenDeclinesAndADeclineIsFinal() {
        String email = uniqueEmail();
        submit(submission("Maybe Co", email, "[]"));
        UUID id = idFor(email);

        assertThat(list("new")).extracting(OnboardingRequestDto::id).contains(id);

        OnboardingRequestDto contacted = restTemplate.exchange("/api/admin/onboarding-requests/" + id + "/contacted",
                HttpMethod.POST, entity(adminToken(), "{\"note\":\"Called Monday, meeting Thursday\"}"),
                OnboardingRequestDto.class).getBody();
        assertThat(contacted.status()).isEqualTo("contacted");
        assertThat(contacted.contactNote()).isEqualTo("Called Monday, meeting Thursday");
        assertThat(contacted.contactedBy()).isNotNull();
        assertThat(list("new")).extracting(OnboardingRequestDto::id).doesNotContain(id);
        assertThat(list("contacted")).extracting(OnboardingRequestDto::id).contains(id);

        assertThat(restTemplate.exchange("/api/admin/onboarding-requests/" + id + "/decline", HttpMethod.POST,
                entity(adminToken(), "{\"reason\":\"\"}"), String.class).getStatusCode())
                .as("a reason is required").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(decline(id, "Sells no land").declineReason()).isEqualTo("Sells no land");

        ResponseEntity<String> again = restTemplate.exchange("/api/admin/onboarding-requests/" + id + "/contacted",
                HttpMethod.POST, entity(adminToken(), "{}"), String.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("ONBOARDING_REQUEST_CLOSED");
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE target_id = '" + id + "'"))
                .as("received, contacted, declined").isEqualTo("3");
    }

    /** Conversion is the ordinary tenant creation, all or nothing with the request. */
    @Test
    void convertingCreatesTheCompanyAndInvitesItsDirectorOrChangesNothing() {
        String email = uniqueEmail();
        submit(submission("Estintin Homes", email, "[\"Lagos\"]"));
        UUID id = idFor(email);
        String rc = "RC" + UUID.randomUUID().toString().substring(0, 8);

        ResponseEntity<OnboardingRequestDto> converted = restTemplate.exchange(
                "/api/admin/onboarding-requests/" + id + "/convert", HttpMethod.POST,
                entity(adminToken(), tenantFor("Estintin Homes", rc, email)), OnboardingRequestDto.class);

        assertThat(converted.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID organizationId = converted.getBody().organizationId();
        assertThat(converted.getBody().status()).isEqualTo("converted");
        assertThat(queryString("SELECT registered_name FROM organizations WHERE id = '" + organizationId + "'"))
                .isEqualTo("Estintin Homes");
        assertThat(queryString("SELECT count(*) FROM staff_invitations WHERE tenant_id = '" + organizationId
                + "' AND lower(email) = '" + email + "'")).as("the director is invited, as usual").isEqualTo("1");

        // A second request whose conversion clashes on the RC number stays open, and nothing is created.
        String other = uniqueEmail();
        submit(submission("Copycat Ltd", other, "[]"));
        UUID otherId = idFor(other);
        ResponseEntity<String> clash = restTemplate.exchange("/api/admin/onboarding-requests/" + otherId + "/convert",
                HttpMethod.POST, entity(adminToken(), tenantFor("Copycat Ltd", rc, other)), String.class);
        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(clash.getBody()).contains("RC_NUMBER_ALREADY_REGISTERED");
        assertThat(get(otherId).status()).as("still open").isEqualTo("new");
        assertThat(queryString("SELECT count(*) FROM organizations WHERE registered_name = 'Copycat Ltd'")).isEqualTo("0");

        assertThat(restTemplate.exchange("/api/admin/onboarding-requests/" + id + "/convert", HttpMethod.POST,
                entity(adminToken(), tenantFor("Estintin Homes", "RC-NEW-" + UUID.randomUUID(), uniqueEmail())),
                String.class).getStatusCode()).as("converting twice").isEqualTo(HttpStatus.CONFLICT);
    }

    /** Each accepted submission sends two emails, so the form is throttled hard — and a forged header doesn't help. */
    @Test
    void theFormIsRateLimitedPerAddress() {
        int previous = onboardingProperties.getSubmissionsPerWindow();
        onboardingProperties.setSubmissionsPerWindow(1);
        try {
            submit(submission("Burst One", uniqueEmail(), "[]"));
            String blockedEmail = uniqueEmail();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Forwarded-For", "203.0.113.9");
            ResponseEntity<String> blocked = restTemplate.exchange("/api/onboarding-requests", HttpMethod.POST,
                    new HttpEntity<>(submission("Burst Two", blockedEmail, "[]"), headers), String.class);

            assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(blocked.getHeaders().getFirst("Retry-After")).isNotBlank();
            assertThat(countFor(blockedEmail)).isEqualTo("0");
        } finally {
            onboardingProperties.setSubmissionsPerWindow(previous);
        }
    }

    // --- helpers ---

    private ResponseEntity<String> submit(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/onboarding-requests", HttpMethod.POST, new HttpEntity<>(json, headers),
                String.class);
    }

    private static String submission(String company, String email, String statesJson) {
        return """
                {"companyName":"%s","statesOfOperation":%s,"estimatedEstateCount":3,
                 "contactName":"Adaeze Okafor","contactRole":"Managing Director","contactEmail":"%s",
                 "contactPhone":"+234 803 123 4567","preferredContactMethod":"phone",
                 "preferredContactTime":"Weekdays after 2pm","message":"We have three estates in Lekki."}
                """.formatted(company, statesJson, email);
    }

    private static CreateTenantRequest tenantFor(String name, String rc, String directorEmail) {
        return new CreateTenantRequest(
                new CompanyIdentityDto(name, null, rc, "Limited Liability (Ltd)", "2020-01-01",
                        new AddressDto("1 Admiralty Way", "Lekki", "Lagos"),
                        new AddressDto("1 Admiralty Way", "Lekki", "Lagos"), List.of("Lagos")),
                new PrimaryContactDto("Adaeze Okafor", "Managing Director", directorEmail, "+2348031234567",
                        "NIN", "12345678901"),
                new CompanyPresenceDto("info+" + UUID.randomUUID() + "@example.com", "+2348000000002", null,
                        new SocialsDto(null, null, null, null)),
                "starter");
    }

    private OnboardingRequestDto get(UUID id) {
        ResponseEntity<OnboardingRequestDto> response = restTemplate.exchange("/api/admin/onboarding-requests/" + id,
                HttpMethod.GET, entity(adminToken(), null), OnboardingRequestDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<OnboardingRequestDto> list(String status) {
        ResponseEntity<OnboardingRequestPage> response = restTemplate.exchange(
                "/api/admin/onboarding-requests?limit=500&status=" + status, HttpMethod.GET, entity(adminToken(), null),
                OnboardingRequestPage.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().items();
    }

    private record OnboardingRequestPage(List<OnboardingRequestDto> items, long total, String cursor, boolean hasMore) {
    }

    private OnboardingRequestDto decline(UUID id, String reason) {
        ResponseEntity<OnboardingRequestDto> response = restTemplate.exchange(
                "/api/admin/onboarding-requests/" + id + "/decline", HttpMethod.POST,
                entity(adminToken(), "{\"reason\":\"" + reason + "\"}"), OnboardingRequestDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static UUID idFor(String email) {
        return UUID.fromString(queryString("SELECT id FROM onboarding_requests WHERE contact_email = '" + email
                + "' ORDER BY created_at DESC LIMIT 1"));
    }

    private static String countFor(String email) {
        return queryString("SELECT count(*) FROM onboarding_requests WHERE lower(contact_email) = lower('" + email + "')");
    }

    private static String uniqueEmail() {
        return "contact+" + UUID.randomUUID() + "@example.com";
    }

    private String buyerToken() {
        String email = "buyer+" + UUID.randomUUID() + "@example.com";
        assertThat(restTemplate.postForEntity("/api/auth/register", new RegisterRequest(
                "Some", "Buyer", email, "+2348000000000", PASSWORD, "NG", Currency.NGN), AuthResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return login(email);
    }

    private String adminToken() {
        return login(ADMIN_EMAIL);
    }

    private String login(String email) {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class);
        assertThat(response.getStatusCode()).as("login for " + email).isEqualTo(HttpStatus.OK);
        return response.getBody().token();
    }

    private static HttpEntity<Object> entity(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static String queryString(String sql) {
        return query(POSTGRES.getUsername(), POSTGRES.getPassword(), sql);
    }

    private static String queryAsAppRole(String sql) {
        return query(APP_ROLE, APP_ROLE_PASSWORD, sql);
    }

    private static String query(String user, String password, String sql) {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, password);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void executeAsAppRole(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
