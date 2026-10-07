package com.techcomfort.landvaultbackend.inventory;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SB-1 with the boundary-in-state check switched ON (the general suite turns
 * it off — see src/test/resources/application.properties). Wired to the
 * restricted app role, so the reference table's read-only grants are what
 * the check actually runs under. See AGENTS.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class StateBoundaryUnderRlsIT {

    private static final String APP_ROLE = "landvault_app_state_boundary_it";
    private static final String APP_ROLE_PASSWORD = "state-boundary-it-password";
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
        registry.add("landvault.estates.state-check.enabled", () -> "true");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private UUID tenantId;
    private UUID branchId;
    private String token;

    @BeforeEach
    void setUp() {
        tenantId = tenant();
        branchId = UUID.randomUUID();
        execute("INSERT INTO branches (id, created_at, deleted, organization_id, name) VALUES ('"
                + branchId + "', now(), false, '" + tenantId + "', 'Head Office')");
        token = staffToken(tenantId, "executive_director");
    }

    // --- standard state names ---

    @Test
    void stateNamesAreStandardisedAndUnknownOnesRefused() {
        assertThat(createEstate("Alias One", "FCT", null).getBody()).contains("\"state\":\"Federal Capital Territory (Abuja)\"");
        assertThat(createEstate("Alias Two", "lagos state", null).getBody()).contains("\"state\":\"Lagos\"");
        assertThat(createEstate("Alias Three", "Nassarawa", null).getBody()).contains("\"state\":\"Nasarawa\"");
        assertThat(queryString("SELECT state_code FROM estates WHERE name = 'Alias One'")).isEqualTo("NG-FC");

        ResponseEntity<String> unknown = createEstate("Nowhere", "Atlantis", null);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody()).contains("UNKNOWN_STATE").contains("Lagos");
    }

    // --- the check ---

    @Test
    void aBoundaryInsideItsStateIsAccepted() {
        assertThat(createEstate("Gwarinpa Real", "FCT", square(7.400, 9.050, 0.01)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(createEstate("Kano Real", "Kano State", square(8.500, 12.000, 0.01)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    /** The case SB-1 exists for: transposed, still inside Nigeria, so the national check passes it. */
    @Test
    void aTransposedAbujaBoundaryIsRefusedNamingWhereItLanded() {
        ResponseEntity<String> refused = createEstate("Swapped Abuja", "FCT", square(9.050, 7.400, 0.01));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("BOUNDARY_OUTSIDE_STATE").contains("in Benue")
                .contains("not Federal Capital Territory (Abuja)");
        assertThat(queryString("SELECT count(*) FROM estates WHERE name = 'Swapped Abuja'")).isEqualTo("0");
    }

    /**
     * The margin: an estate straddling the FCT border by a few hundred metres
     * is accepted. Built around a real vertex of the FCT boundary, so it is
     * genuinely part-inside, part-outside.
     */
    @Test
    void anEstateStraddlingItsStateBorderWithinTheMarginIsAccepted() {
        double x = Double.parseDouble(queryString(
                "SELECT ST_X(ST_PointN(ST_ExteriorRing(boundary), 1)) FROM nigerian_states WHERE code = 'NG-FC'"));
        double y = Double.parseDouble(queryString(
                "SELECT ST_Y(ST_PointN(ST_ExteriorRing(boundary), 1)) FROM nigerian_states WHERE code = 'NG-FC'"));
        String straddle = square(x - 0.002, y - 0.002, 0.004);
        assertThat(queryString("SELECT ST_Within(ST_SetSRID(ST_GeomFromGeoJSON('" + straddle + "'), 4326), boundary) "
                + "FROM nigerian_states WHERE code = 'NG-FC'")).as("genuinely over the border").isEqualTo("f");

        assertThat(createEstate("Border Gardens", "FCT", straddle).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void addingABoundaryLaterIsCheckedToo() {
        UUID estate = estateId(createEstate("Later Gardens", "FCT", null));

        ResponseEntity<String> refused = restTemplate.exchange("/api/portal/estates/" + estate + "/boundary",
                HttpMethod.POST, entity(token, "{\"footprint\":" + square(9.050, 7.400, 0.01) + "}"), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("BOUNDARY_OUTSIDE_STATE");
    }

    /** Changing the declared state re-checks the boundary the estate already has. */
    @Test
    void changingTheStateRechecksTheExistingBoundary() {
        UUID estate = estateId(createEstate("Moving Gardens", "FCT", square(7.400, 9.050, 0.01)));

        ResponseEntity<String> refused = putEstate(estate, "{\"state\":\"Lagos\"}");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("BOUNDARY_OUTSIDE_STATE").contains("Federal Capital Territory (Abuja)");
        assertThat(queryString("SELECT state_code FROM estates WHERE id = '" + estate + "'")).isEqualTo("NG-FC");

        assertThat(putEstate(estate, "{\"state\":\"Abuja\"}").getStatusCode()).as("same state, another spelling")
                .isEqualTo(HttpStatus.OK);
    }

    // --- the Super Admin override ---

    /** For a genuinely disputed border: verified by a person, the check is skipped for that estate. */
    @Test
    void aSuperAdminOverrideLetsADisputedBoundaryThrough() {
        UUID estate = estateId(createEstate("Disputed Gardens", "FCT", null));

        assertThat(restTemplate.exchange("/api/admin/estates/" + estate + "/state-override", HttpMethod.POST,
                entity(token, "{\"reason\":\"x\"}"), String.class).getStatusCode())
                .as("tenant staff can't override").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange("/api/admin/estates/" + estate + "/state-override", HttpMethod.POST,
                entity(adminToken(), "{\"reason\":\" \"}"), String.class).getStatusCode())
                .as("a reason is required").isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> set = restTemplate.exchange("/api/admin/estates/" + estate + "/state-override",
                HttpMethod.POST, entity(adminToken(), "{\"reason\":\"Registry confirms FCT title; GRID3 border differs\"}"),
                String.class);
        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(restTemplate.exchange("/api/portal/estates/" + estate + "/boundary", HttpMethod.POST,
                entity(token, "{\"footprint\":" + square(9.050, 7.400, 0.01) + "}"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT detail FROM audit_log_entries WHERE action = 'estate.state_override_set' "
                + "AND target_id = '" + estate + "'")).contains("Registry confirms FCT title");
    }

    @Test
    void changingTheStateClearsTheOverride() {
        UUID estate = estateId(createEstate("Override Gardens", "FCT", null));
        restTemplate.exchange("/api/admin/estates/" + estate + "/state-override", HttpMethod.POST,
                entity(adminToken(), "{\"reason\":\"verified\"}"), String.class);

        putEstate(estate, "{\"state\":\"Niger\"}");

        assertThat(queryString("SELECT state_override_at IS NULL FROM estates WHERE id = '" + estate + "'"))
                .as("it verified the old state, not the new one").isEqualTo("t");
    }

    // --- the reference data ---

    @Test
    void theAppCanReadButNeverWriteTheStateBoundaries() {
        assertThat(queryString("SELECT count(*) FROM nigerian_states")).isEqualTo("37");
        assertThat(queryString("SELECT has_table_privilege('" + APP_ROLE + "', 'nigerian_states', 'SELECT')")).isEqualTo("t");
        for (String privilege : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
            assertThat(queryString("SELECT has_table_privilege('" + APP_ROLE + "', 'nigerian_states', '"
                    + privilege + "')")).as(privilege).isEqualTo("f");
        }
    }

    // --- fixtures ---

    private ResponseEntity<String> createEstate(String name, String state, String footprintJson) {
        String body = "{\"name\":\"" + name + "\",\"state\":\"" + state + "\",\"branchId\":\"" + branchId + "\""
                + (footprintJson == null ? "" : ",\"footprint\":" + footprintJson) + "}";
        return restTemplate.exchange("/api/portal/estates", HttpMethod.POST, entity(token, body), String.class);
    }

    private ResponseEntity<String> putEstate(UUID estate, String json) {
        return restTemplate.exchange("/api/portal/estates/" + estate, HttpMethod.PUT, entity(token, json), String.class);
    }

    private static UUID estateId(ResponseEntity<String> created) {
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        String body = created.getBody();
        int at = body.indexOf("\"id\":\"") + 6;
        return UUID.fromString(body.substring(at, at + 36));
    }

    private static String square(double lng, double lat, double side) {
        return String.format(Locale.ROOT, "{\"type\":\"Polygon\",\"coordinates\":[[[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f]]]}",
                lng, lat, lng + side, lat, lng + side, lat + side, lng, lat + side, lng, lat);
    }

    private UUID tenant() {
        String rc = "RC-" + UUID.randomUUID();
        CreateTenantRequest request = new CreateTenantRequest(
                new CompanyIdentityDto("State Co " + rc, null, rc, "Limited Liability (Ltd)", "2020-01-01",
                        new AddressDto("1 Broad Street", "Abuja", "FCT"),
                        new AddressDto("1 Broad Street", "Abuja", "FCT"), List.of("FCT")),
                new PrimaryContactDto("Some Director", "Chief Executive Officer",
                        "ed+" + UUID.randomUUID() + "@example.com", "+2348000000001", "NIN", "12345678901"),
                new CompanyPresenceDto("org+" + rc + "@example.com", "+2348000000002", null,
                        new SocialsDto(null, null, null, null)),
                "starter");
        ResponseEntity<TenantDetailDto> response = restTemplate.exchange(
                "/api/admin/tenants", HttpMethod.POST, entity(adminToken(), request), TenantDetailDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private String staffToken(UUID owningTenantId, String roleCode) {
        String email = "staff+" + UUID.randomUUID() + "@example.com";
        assertThat(restTemplate.postForEntity("/api/auth/register", new RegisterRequest(
                "Portal", "Staff", email, "+2348000000000", PASSWORD, "NG", Currency.NGN), AuthResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        execute("UPDATE users SET tenant_id = '" + owningTenantId + "' WHERE lower(email) = lower('" + email + "')");
        execute("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('" + email + "'))");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id, scoped_branch_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id, NULL FROM users u, roles r "
                + "WHERE lower(u.email) = lower('" + email + "') AND r.code = '" + roleCode + "'");
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
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void execute(String sql) {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
