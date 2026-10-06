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
/**
 * FP-2, option C: correcting an estate boundary. Small changes and
 * unpublished estates apply at once; a published estate whose land changes by
 * more than 5% waits for a Super Admin. Restricted app role throughout, with
 * the state check on. See AGENTS.md, "Correcting an estate boundary".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class EstateBoundaryCorrectionUnderRlsIT {

    private static final String APP_ROLE = "landvault_app_boundary_fix_it";
    private static final String APP_ROLE_PASSWORD = "boundary-fix-it-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple";
    /** Each test gets its own patch of the FCT, so estates from different tests never overlap. */
    private static final java.util.concurrent.atomic.AtomicInteger PATCH = new java.util.concurrent.atomic.AtomicInteger();

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
    private double x;
    private double y;
    /** About 1.1 km square at this test's origin. */
    private String base;

    @BeforeEach
    void setUp() {
        int n = PATCH.getAndIncrement();
        x = 7.250 + 0.030 * (n % 8);
        y = 8.850 + 0.030 * (n / 8);
        base = rect(x, y, 0.010, 0.010);
        tenantId = tenant();
        branchId = UUID.randomUUID();
        execute("INSERT INTO branches (id, created_at, deleted, organization_id, name) VALUES ('"
                + branchId + "', now(), false, '" + tenantId + "', 'Head Office')");
        token = staffToken(tenantId, "executive_director");
    }

    @Test
    void aSmallCorrectionToAPublishedEstateAppliesAtOnce() {
        UUID estate = publishedEstate("Small Fix");
        ResponseEntity<String> fixed = correct(token, estate, rect(x + 0.0000, y + 0.0000, 0.010, 0.0102));

        assertThat(fixed.getStatusCode()).as(fixed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(fixed.getBody()).contains("\"status\":\"applied\"").contains("\"publicationBlocked\":false");
        assertThat(queryString("SELECT round(ST_YMax(footprint)::numeric, 4) FROM estates WHERE id = '" + estate + "'"))
                .isEqualTo(String.format(Locale.ROOT, "%.4f", y + 0.0102));
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'estate.boundary_corrected' "
                + "AND target_id = '" + estate + "'")).isEqualTo("1");
    }

    /** Option C: buyers only ever see a reviewed boundary when a live listing's land changes a lot. */
    @Test
    void aLargeCorrectionToAPublishedEstateWaitsAndTheOldBoundaryStaysLive() {
        UUID estate = publishedEstate("Big Fix");
        String before = footprintText(estate);

        ResponseEntity<String> held = correct(token, estate, rect(x + 0.0000, y + 0.0000, 0.010, 0.015));
        assertThat(held.getStatusCode()).as(held.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(held.getBody()).contains("\"status\":\"pending\"").contains("\"changedPct\":50");
        assertThat(footprintText(estate)).as("the live boundary is untouched").isEqualTo(before);
        assertThat(correct(token, estate, rect(x + 0.0000, y + 0.0000, 0.010, 0.0101)).getBody())
                .as("one correction waiting at a time").contains("BOUNDARY_CHANGE_PENDING");

        String queue = restTemplate.exchange("/api/admin/boundary-changes", HttpMethod.GET, entity(adminToken(), null), String.class).getBody();
        assertThat(queue).contains(idOf(held).toString()).contains("\"companyName\":\"State Co");

        ResponseEntity<String> approved = admin("/" + idOf(held) + "/approve", "{\"note\":\"Survey plan checked.\"}");
        assertThat(approved.getStatusCode()).as(approved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody()).contains("\"status\":\"approved\"").contains("\"publicationBlocked\":false");
        assertThat(footprintText(estate)).isNotEqualTo(before);
        assertThat(queryString("SELECT round(ST_YMax(footprint)::numeric, 3) FROM estates WHERE id = '" + estate + "'"))
                .isEqualTo(String.format(Locale.ROOT, "%.3f", y + 0.015));
        assertThat(admin("/" + idOf(held) + "/approve", null).getBody()).contains("BOUNDARY_CHANGE_NOT_PENDING");
    }

    @Test
    void anUnpublishedEstateCorrectsFreelyEvenByALot() {
        UUID estate = estateId(createEstate("Draft Estate", "FCT", base));
        ResponseEntity<String> fixed = correct(token, estate, rect(x + 0.0000, y + 0.0000, 0.010, 0.015));
        assertThat(fixed.getStatusCode()).as(fixed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(fixed.getBody()).contains("\"status\":\"applied\"");
    }

    @Test
    void aRejectionKeepsTheOldBoundaryAndTellsTheDeveloperWhy() {
        UUID estate = publishedEstate("Rejected Fix");
        String before = footprintText(estate);
        UUID change = idOf(correct(token, estate, rect(x + 0.0000, y + 0.0000, 0.010, 0.015)));

        assertThat(admin("/" + change + "/reject", "{}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(admin("/" + change + "/reject", "{\"note\":\"Survey plan doesn't match.\"}").getBody())
                .contains("\"status\":\"rejected\"");
        assertThat(footprintText(estate)).isEqualTo(before);
        assertThat(restTemplate.exchange("/api/portal/estates/" + estate + "/boundary-changes", HttpMethod.GET,
                entity(token, null), String.class).getBody()).contains("Survey plan doesn't match.");
    }

    @Test
    void aWithdrawnCorrectionFreesTheEstateForAnother() {
        UUID estate = publishedEstate("Withdrawn Fix");
        UUID change = idOf(correct(token, estate, rect(x + 0.0000, y + 0.0000, 0.010, 0.015)));

        assertThat(restTemplate.exchange("/api/portal/estates/" + estate + "/boundary-changes/" + change + "/withdraw",
                HttpMethod.POST, entity(token, null), String.class).getBody()).contains("\"status\":\"withdrawn\"");
        assertThat(correct(token, estate, rect(x + 0.0000, y + 0.0000, 0.010, 0.0101)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(admin("/" + change + "/approve", null).getBody()).contains("BOUNDARY_CHANGE_NOT_PENDING");
    }

    @Test
    void everyMappedPlotMustStillFitAndApprovalChecksAgain() {
        UUID estate = publishedEstate("Plots Fit");
        addPlot(estate, "7", rect(x + 0.0085, y + 0.0085, 0.001, 0.001));
        ResponseEntity<String> shrunk = correct(token, estate, rect(x + 0.0000, y + 0.0000, 0.008, 0.008));
        assertThat(shrunk.getBody()).contains("PLOT_OUTSIDE_ESTATE").contains("Plot 7");

        // Shifted east: plot 7 still fits, so it waits for review...
        UUID change = idOf(correct(token, estate, rect(x + 0.0040, y + 0.0000, 0.010, 0.010)));
        // ...then a plot is mapped in the strip the new boundary drops.
        addPlot(estate, "8", rect(x + 0.0005, y + 0.0005, 0.001, 0.001));
        assertThat(admin("/" + change + "/approve", null).getBody()).contains("PLOT_OUTSIDE_ESTATE").contains("Plot 8");
        assertThat(queryString("SELECT status FROM estate_boundary_changes WHERE id = '" + change + "'")).isEqualTo("PENDING");
    }

    @Test
    void aCorrectionIsStateCheckedAndMustChangeSomething() {
        UUID estate = estateId(createEstate("Checked Fix", "FCT", base));
        assertThat(correct(token, estate, rect(y + 0.0000, x + 0.0000, 0.010, 0.010)).getBody())
                .as("transposed lands in Benue").contains("BOUNDARY_OUTSIDE_STATE");
        assertThat(correct(token, estate, base).getBody()).contains("BOUNDARY_UNCHANGED");
        UUID bare = estateId(createEstate("No Boundary", "FCT", null));
        assertThat(correct(token, bare, base).getBody()).contains("BOUNDARY_NOT_SET");
    }

    @Test
    void anotherCompanyCannotCorrectYourEstate() {
        UUID estate = estateId(createEstate("Ours", "FCT", base));
        String theirs = staffToken(tenant(), "executive_director");
        assertThat(correct(theirs, estate, rect(x + 0.0000, y + 0.0000, 0.010, 0.0101)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * A correction onto another company's land is caught; correcting back
     * clears the geometry but not the block — a human closes a HIGH conflict
     * (CD-9), so a boundary can't be shaved to lift one quietly.
     */
    @Test
    void aCrossCompanyOverlapFromACorrectionBlocksAndOnlyAHumanClearsIt() {
        UUID ours = estateId(createEstate("Ours East", "FCT", base));
        UUID otherTenant = tenant();
        UUID otherBranch = UUID.randomUUID();
        execute("INSERT INTO branches (id, created_at, deleted, organization_id, name) VALUES ('"
                + otherBranch + "', now(), false, '" + otherTenant + "', 'Theirs')");
        String other = staffToken(otherTenant, "executive_director");
        ResponseEntity<String> neighbour = restTemplate.exchange("/api/portal/estates", HttpMethod.POST, entity(other,
                "{\"name\":\"Neighbour\",\"state\":\"FCT\",\"branchId\":\"" + otherBranch + "\",\"footprint\":"
                        + rect(x + 0.0100, y + 0.0000, 0.010, 0.010) + "}"), String.class);
        assertThat(neighbour.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> grabbed = correct(token, ours, rect(x + 0.0000, y + 0.0000, 0.013, 0.010));
        assertThat(grabbed.getBody()).contains("\"status\":\"applied\"").contains("\"publicationBlocked\":true")
                .as("never names the other company").doesNotContain("Neighbour").doesNotContain(otherTenant.toString());
        assertThat(between(grabbed.getBody(), "\"raised\":[", "],\"resolved\""))
                .as("the new overlap is itemised").contains("\"severity\":\"high\"").contains("Ours East");

        ResponseEntity<String> back = correct(token, ours, base);
        assertThat(back.getBody()).contains("\"publicationBlocked\":true");
        assertThat(between(back.getBody(), "\"awaitingReview\":[", "],\"stillOpen\""))
                .as("cleared, but a person closes it").contains("\"underReview\":true");
        assertThat(between(back.getBody(), "\"resolved\":[", "],\"awaitingReview\"")).isEmpty();
        assertThat(queryString("SELECT count(*) FROM listing_conflicts WHERE geometry_cleared_at IS NOT NULL "
                + "AND status NOT IN ('DISMISSED', 'AUTO_RESOLVED')")).isEqualTo("1");
    }

    /** A same-company overlap clears itself when the boundary is corrected — and the response says which. */
    @Test
    void correctingYourOwnOverlapReportsItResolved() {
        estateId(createEstate("Own West", "FCT", base));
        UUID east = estateId(createEstate("Own East", "FCT", rect(x + 0.0080, y, 0.010, 0.010)));
        assertThat(queryString("SELECT count(*) FROM listing_conflicts WHERE status = 'OPEN' AND severity = 'MEDIUM' "
                + "AND (left_entity_id = '" + east + "' OR right_entity_id = '" + east + "')")).isEqualTo("1");

        ResponseEntity<String> fixed = correct(token, east, rect(x + 0.0100, y, 0.010, 0.010));
        assertThat(between(fixed.getBody(), "\"resolved\":[", "],\"awaitingReview\""))
                // Both sides are this company's, so either estate may be the one named.
                .contains("\"status\":\"auto_resolved\"").contains("\"severity\":\"medium\"").containsPattern("Own (East|West)");
        assertThat(between(fixed.getBody(), "\"raised\":[", "],\"resolved\"")).isEmpty();
    }

    private static String between(String body, String start, String end) {
        int from = body.indexOf(start);
        assertThat(from).as("'" + start + "' in " + body).isNotNegative();
        from += start.length();
        return body.substring(from, body.indexOf(end, from));
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

    private static String rect(double lng, double lat, double width, double height) {
        return String.format(Locale.ROOT, "{\"type\":\"Polygon\",\"coordinates\":[[[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f]]]}",
                lng, lat, lng + width, lat, lng + width, lat + height, lng, lat + height, lng, lat);
    }

    private ResponseEntity<String> correct(String asToken, UUID estate, String footprint) {
        return restTemplate.exchange("/api/portal/estates/" + estate + "/boundary", HttpMethod.PUT,
                entity(asToken, "{\"footprint\":" + footprint + ",\"reason\":\"Re-survey.\"}"), String.class);
    }

    private ResponseEntity<String> admin(String path, String body) {
        return restTemplate.exchange("/api/admin/boundary-changes" + path, HttpMethod.POST,
                entity(adminToken(), body), String.class);
    }

    private UUID publishedEstate(String name) {
        UUID id = estateId(createEstate(name, "FCT", base));
        execute("UPDATE estates SET published = true WHERE id = '" + id + "'");
        return id;
    }

    private String footprintText(UUID estate) {
        return queryString("SELECT ST_AsText(footprint) FROM estates WHERE id = '" + estate + "'");
    }

    private static UUID idOf(ResponseEntity<String> response) {
        String body = response.getBody();
        int at = body.indexOf("\"id\":\"") + 6;
        return UUID.fromString(body.substring(at, at + 36));
    }

    private void addPlot(UUID estate, String number, String footprint) {
        ResponseEntity<String> tier = restTemplate.exchange("/api/portal/estates/" + estate + "/price-tiers", HttpMethod.POST,
                entity(token, "{\"tierType\":\"LAND_SIZE\",\"sizeSqm\":" + (500 + Integer.parseInt(number)) + ",\"price\":1000000,\"currency\":\"NGN\",\"label\":\"T"
                        + number + "\"}"), String.class);
        assertThat(tier.getStatusCode()).as(tier.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> plot = restTemplate.exchange("/api/portal/estates/" + estate + "/plots", HttpMethod.POST,
                entity(token, "{\"plots\":[{\"plotNumber\":\"" + number + "\",\"priceTierId\":\"" + idOf(tier)
                        + "\",\"status\":\"available-dev\",\"footprint\":" + footprint + "}]}"), String.class);
        assertThat(plot.getStatusCode()).as(plot.getBody()).isEqualTo(HttpStatus.CREATED);
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
