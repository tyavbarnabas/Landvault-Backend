package com.techcomfort.landvaultbackend.inventory;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.inventory.dto.BlockDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierDto;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Estate creation end to end — the first time a polygon reaches PostGIS
 * through a real request path, gets stored, indexed and measured.
 * <p>
 * Boundaries are in Lagos, chosen deliberately: it is one of the places where
 * a transposed coordinate leaves the country box and the bounds guard can
 * actually catch it. See
 * {@link #swappedCoordinatesAreOnlyCaughtWhenTheyLeaveTheCountryBox()} for the
 * cases it cannot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class PortalEstateCreationIT {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";

    /** ~2.2km x 2.2km in Lagos — about 4.9 million square metres. */
    private static final String LAGOS_ESTATE_RING = """
            [[3.35,6.45],[3.37,6.45],[3.37,6.47],[3.35,6.47],[3.35,6.45]]""";
    /** ~110m x 110m inside the estate above — about 12,200 square metres. */
    private static final String PLOT_INSIDE_RING = """
            [[3.355,6.455],[3.356,6.455],[3.356,6.456],[3.355,6.456],[3.355,6.455]]""";
    /** Valid Nigerian coordinates, but nowhere near the estate. */
    private static final String PLOT_OUTSIDE_RING = """
            [[7.40,9.05],[7.41,9.05],[7.41,9.06],[7.40,9.06],[7.40,9.05]]""";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "integration-test-signing-secret-of-at-least-32-bytes");
        registry.add("landvault.bootstrap.super-admin.enabled", () -> "true");
        registry.add("landvault.bootstrap.super-admin.email", () -> ADMIN_EMAIL);
        registry.add("landvault.bootstrap.super-admin.password", () -> PASSWORD);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private UUID tenantId;
    private UUID branchId;
    private String portalToken;

    /**
     * A tenant with a branch, and a user holding {@code executive_director}
     * within it. The Executive Director account the tenant-creation flow
     * makes has an unrecoverable random password by design, so the portal
     * user is built by granting the role to an account whose password we
     * know.
     */
    @BeforeEach
    void setUpPortalUser() {
        String adminToken = login(ADMIN_EMAIL);
        tenantId = createTenant(adminToken);

        branchId = UUID.randomUUID();
        execute("INSERT INTO branches (id, created_at, deleted, organization_id, name) VALUES ('"
                + branchId + "', now(), false, '" + tenantId + "', 'Head Office')");

        String email = "ed+" + UUID.randomUUID() + "@example.com";
        registerBuyer(email);
        UUID userId = userIdOf(email);
        execute("UPDATE users SET tenant_id = '" + tenantId + "' WHERE id = '" + userId + "'");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id) "
                + "SELECT gen_random_uuid(), now(), false, '" + userId + "', r.id "
                + "FROM roles r WHERE r.code = 'executive_director'");

        portalToken = login(email);
    }

    // --- estates ---

    @Test
    void createsADraftEstateWithNoBoundary() {
        ResponseEntity<EstateDto> response = createEstate(estateBody("Draft Gardens", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        EstateDto estate = response.getBody();
        assertThat(estate.hasFootprint()).as("absent means absent — no boundary is synthesised").isFalse();
        assertThat(estate.footprintAreaSqm()).isNull();
        assertThat(estate.published()).as("publication is a separate deliberate action").isFalse();
        assertThat(estate.tenantId()).isEqualTo(tenantId);
        assertThat(estate.slug()).isEqualTo("draft-gardens");
    }

    /** The moment the PostGIS setup is actually proven. */
    @Test
    void storesARealBoundaryAsSrid4326AndMeasuresItInSquareMetres() {
        EstateDto estate = createEstate(estateBody("Palm Grove", LAGOS_ESTATE_RING)).getBody();

        assertThat(estate.hasFootprint()).isTrue();
        assertThat(singleLong("SELECT ST_SRID(footprint) FROM estates WHERE id = '" + estate.id() + "'"))
                .as("stored as WGS84")
                .isEqualTo(4326L);
        assertThat(singleString("SELECT GeometryType(footprint) FROM estates WHERE id = '" + estate.id() + "'"))
                .isEqualTo("POLYGON");

        // ~2.2km x 2.2km. In square DEGREES this would be 0.0004 — the
        // difference between a geography cast and raw 4326 is this stark.
        assertThat(estate.footprintAreaSqm())
                .as("ST_Area(footprint::geography) must be square metres, not square degrees")
                .isGreaterThan(new BigDecimal("4000000"))
                .isLessThan(new BigDecimal("6000000"));
    }

    @Test
    void rejectsAnUnclosedRing() {
        String open = "[[3.35,6.45],[3.37,6.45],[3.37,6.47],[3.35,6.47]]";
        ResponseEntity<String> response = createEstateRaw(estateBody("Open Ring", open));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("INVALID_GEOMETRY").contains("not closed");
    }

    @Test
    void rejectsAMultiPolygon() {
        String body = """
                {"name":"Multi","branchId":"%s","footprint":{"type":"MultiPolygon","coordinates":[%s]}}"""
                .formatted(branchId, LAGOS_ESTATE_RING);

        ResponseEntity<String> response = createEstateRaw(body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("INVALID_GEOMETRY").contains("MultiPolygon");
    }

    @Test
    void rejectsTooFewPositions() {
        ResponseEntity<String> response = createEstateRaw(
                estateBody("Triangle", "[[3.35,6.45],[3.37,6.45],[3.35,6.45]]"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("INVALID_GEOMETRY");
    }

    // --- the coordinate-order trap ---

    @Test
    void rejectsSwappedCoordinatesThatLeaveTheCountry() {
        // Lagos transposed: longitude 6.45, latitude 3.35 — the Gulf of
        // Guinea, south of the country.
        String swapped = "[[6.45,3.35],[6.45,3.37],[6.47,3.37],[6.47,3.35],[6.45,3.35]]";

        ResponseEntity<String> response = createEstateRaw(estateBody("Swapped", swapped));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .contains("INVALID_GEOMETRY")
                .contains("transposed");
    }

    /**
     * Pins a real limitation rather than leaving it to be discovered.
     * <p>
     * Nigeria spans longitude 2–15 and latitude 4–14, so the two ranges
     * overlap across 4–14. Any interior point whose coordinates both fall in
     * that band is swap-ambiguous: transposed Abuja (7.4, 9.05) becomes
     * (9.05, 7.4), which is in Taraba — genuinely inside the country, so no
     * bounds check of any shape can reject it.
     * <p>
     * The guard therefore catches out-of-country boundaries and coastal or
     * far-western swaps (Lagos, Port Harcourt's longitude), but is not the
     * complete protection it may appear. The real protections are the
     * documented {@code [lng, lat]} wire convention and the frontend owning
     * the Leaflet conversion. A per-state bounding-box check would close most
     * of the remaining gap — FCT's box is small enough that transposed Abuja
     * falls outside it — and is the concrete follow-up. See AGENTS.md.
     */
    @Test
    void swappedCoordinatesAreOnlyCaughtWhenTheyLeaveTheCountryBox() {
        String transposedAbuja = "[[9.05,7.40],[9.05,7.41],[9.06,7.41],[9.06,7.40],[9.05,7.40]]";

        ResponseEntity<String> response = createEstateRaw(estateBody("Transposed Abuja", transposedAbuja));

        assertThat(response.getStatusCode())
                .as("documented gap: both values sit inside Nigeria's overlapping lng/lat ranges")
                .isEqualTo(HttpStatus.CREATED);
    }

    // --- tenant and branch come from context ---

    @Test
    void aTenantIdInTheRequestBodyIsIgnored() {
        UUID someoneElse = UUID.randomUUID();
        String body = """
                {"name":"Injected","branchId":"%s","tenantId":"%s"}""".formatted(branchId, someoneElse);

        ResponseEntity<EstateDto> response = restTemplate.exchange(
                "/api/portal/estates", HttpMethod.POST, entity(portalToken, body), EstateDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().tenantId())
                .as("tenant comes from the authenticated scope, never the body")
                .isEqualTo(tenantId)
                .isNotEqualTo(someoneElse);
    }

    @Test
    void aBranchFromAnotherTenantIsRejected() {
        UUID otherTenant = createTenant(login(ADMIN_EMAIL));
        UUID foreignBranch = UUID.randomUUID();
        execute("INSERT INTO branches (id, created_at, deleted, organization_id, name) VALUES ('"
                + foreignBranch + "', now(), false, '" + otherTenant + "', 'Not Yours')");

        ResponseEntity<String> response = createEstateRaw(
                """
                {"name":"Cross Tenant","branchId":"%s"}""".formatted(foreignBranch));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("does not belong to your organization");
    }

    // --- price tiers ---

    @Test
    void rejectsALandSizeTierWithNoSizeAsACleanBadRequest() {
        EstateDto estate = createEstate(estateBody("Tierless", null)).getBody();

        ResponseEntity<String> response = post(
                "/api/portal/estates/" + estate.id() + "/price-tiers",
                """
                {"tierType":"land_size","price":4200000,"currency":"NGN"}""");

        assertThat(response.getStatusCode())
                .as("a clean 400, not a constraint violation leaking out as a 500")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("INVALID_REQUEST").contains("LAND_SIZE");
    }

    @Test
    void acceptsAUnitTypeTierWithNoSize() {
        EstateDto estate = createEstate(estateBody("Terraces", null)).getBody();

        PriceTierDto tier = createTier(estate.id(),
                """
                {"tierType":"unit_type","price":85000000,"currency":"NGN","label":"3-bedroom terrace"}""");

        assertThat(tier.sizeSqm()).isNull();
        assertThat(tier.label()).isEqualTo("3-bedroom terrace");
    }

    // --- plots ---

    @Test
    void nominalSizeComesFromTheTierAndSurveyedAreaIsComputed() {
        EstateDto estate = createEstate(estateBody("Measured", LAGOS_ESTATE_RING)).getBody();
        PriceTierDto tier = createTier(estate.id(),
                """
                {"tierType":"land_size","sizeSqm":250,"price":4200000,"currency":"NGN"}""");
        BlockDto block = createBlock(estate.id());

        String body = """
                {"plots":[
                  {"plotNumber":"1","blockId":"%s","priceTierId":"%s","status":"available-dev",
                   "nominalSizeSqmOverride":9999,"footprint":{"type":"Polygon","coordinates":[%s]}},
                  {"plotNumber":"2","blockId":"%s","priceTierId":"%s","status":"available-dev"}
                ]}""".formatted(block.id(), tier.id(), PLOT_INSIDE_RING, block.id(), tier.id());

        ResponseEntity<PlotDto[]> response = restTemplate.exchange(
                "/api/portal/estates/" + estate.id() + "/plots", HttpMethod.POST,
                entity(portalToken, body), PlotDto[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<PlotDto> plots = List.of(response.getBody());

        PlotDto withBoundary = plots.getFirst();
        assertThat(withBoundary.nominalSizeSqm())
                .as("the tier's size, never the request's — 9999 must be ignored")
                .isEqualByComparingTo("250");
        assertThat(withBoundary.actualAreaSqm())
                .as("computed from the footprint in square metres")
                .isGreaterThan(new BigDecimal("10000"))
                .isLessThan(new BigDecimal("15000"));

        PlotDto withoutBoundary = plots.get(1);
        assertThat(withoutBoundary.actualAreaSqm())
                .as("no footprint means no surveyed area — never the nominal figure")
                .isNull();
        assertThat(withoutBoundary.nominalSizeSqm()).isEqualByComparingTo("250");
    }

    @Test
    void rejectsAPlotWhoseBoundaryFallsOutsideTheEstate() {
        EstateDto estate = createEstate(estateBody("Bounded", LAGOS_ESTATE_RING)).getBody();
        PriceTierDto tier = createTier(estate.id(),
                """
                {"tierType":"land_size","sizeSqm":250,"price":4200000,"currency":"NGN"}""");

        ResponseEntity<String> response = post("/api/portal/estates/" + estate.id() + "/plots",
                """
                {"plots":[{"plotNumber":"X","priceTierId":"%s","status":"available-dev",
                 "footprint":{"type":"Polygon","coordinates":[%s]}}]}"""
                        .formatted(tier.id(), PLOT_OUTSIDE_RING));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("PLOT_OUTSIDE_ESTATE");
    }

    /**
     * A duplicate plot number is an ordinary user error. Before the handler
     * existed it escaped as a 500 — found during a live walkthrough, not by
     * this suite.
     */
    @Test
    void aDuplicatePlotNumberIsAConflictNotAServerError() {
        EstateDto estate = createEstate(estateBody("Duplicated", null)).getBody();
        PriceTierDto tier = createTier(estate.id(),
                """
                {"tierType":"land_size","sizeSqm":250,"price":4200000,"currency":"NGN"}""");
        BlockDto block = createBlock(estate.id());

        String body = """
                {"plots":[{"plotNumber":"1","blockId":"%s","priceTierId":"%s","status":"available-dev"}]}"""
                .formatted(block.id(), tier.id());
        assertThat(post("/api/portal/estates/" + estate.id() + "/plots", body).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> repeat = post("/api/portal/estates/" + estate.id() + "/plots", body);

        assertThat(repeat.getStatusCode())
                .as("a constraint violation must not escape as a 500")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(repeat.getBody())
                .contains("DUPLICATE_RECORD")
                .as("and must name the real cause, not a fixed guess")
                .contains("plot number");
    }

    @Test
    void aBatchThatRepeatsAPlotNumberWithinItselfIsRejectedBeforeAnyWrite() {
        EstateDto estate = createEstate(estateBody("Self Duplicated", null)).getBody();
        PriceTierDto tier = createTier(estate.id(),
                """
                {"tierType":"land_size","sizeSqm":250,"price":4200000,"currency":"NGN"}""");

        ResponseEntity<String> response = post("/api/portal/estates/" + estate.id() + "/plots",
                """
                {"plots":[
                  {"plotNumber":"7","priceTierId":"%s","status":"available-dev"},
                  {"plotNumber":"7","priceTierId":"%s","status":"available-dev"}
                ]}""".formatted(tier.id(), tier.id()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("repeats plot number");
        assertThat(countRows("plots", "estate_id = '" + estate.id() + "'"))
                .as("nothing is written when the batch is self-inconsistent")
                .isZero();
    }

    // --- title and verification ---

    @Test
    void recordsTitleAndRefusesASecond() {
        EstateDto estate = createEstate(estateBody("Titled", null)).getBody();
        String body = """
                {"titleType":"C of O","titleNumber":"FCT/ABC/123","issuedDate":"2021-03-04"}""";

        assertThat(post("/api/portal/estates/" + estate.id() + "/title", body).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(post("/api/portal/estates/" + estate.id() + "/title", body).getStatusCode())
                .as("title is 1:1 with an estate")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aVerifiedCheckMustSayWhereTheVerificationCameFrom() {
        EstateDto estate = createEstate(estateBody("Checked", null)).getBody();

        ResponseEntity<String> withoutSource = post(
                "/api/portal/estates/" + estate.id() + "/verification-checks",
                """
                {"checkType":"encroachment_status","status":"verified"}""");
        assertThat(withoutSource.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> withSource = post(
                "/api/portal/estates/" + estate.id() + "/verification-checks",
                """
                {"checkType":"encroachment_status","status":"verified","verificationSource":"manual_review"}""");
        assertThat(withSource.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(withSource.getBody()).contains("manual_review");
    }

    @Test
    void aCheckDefaultsToNotCheckedRatherThanVerified() {
        EstateDto estate = createEstate(estateBody("Unchecked", null)).getBody();

        ResponseEntity<String> response = post(
                "/api/portal/estates/" + estate.id() + "/verification-checks",
                """
                {"checkType":"agis_registration"}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody())
                .as("the absence of a check is not a clean bill of health")
                .contains("not_checked");
    }

    // --- authorization and audit ---

    @Test
    void aBuyerIsForbiddenOnEveryEndpoint() {
        EstateDto estate = createEstate(estateBody("Private", null)).getBody();
        String buyerToken = registerBuyer("buyer+" + UUID.randomUUID() + "@example.com");

        assertThat(rawPost(buyerToken, "/api/portal/estates", estateBody("Nope", null)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rawPost(buyerToken, "/api/portal/estates/" + estate.id() + "/blocks",
                """
                {"name":"B"}""").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rawPost(buyerToken, "/api/portal/estates/" + estate.id() + "/price-tiers",
                """
                {"tierType":"land_size","sizeSqm":250,"price":1,"currency":"NGN"}""")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rawPost(buyerToken, "/api/portal/estates/" + estate.id() + "/title",
                """
                {"titleType":"Gazette"}""").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void everyCreationIsAudited() {
        EstateDto estate = createEstate(estateBody("Audited", null)).getBody();
        PriceTierDto tier = createTier(estate.id(),
                """
                {"tierType":"land_size","sizeSqm":250,"price":4200000,"currency":"NGN"}""");
        createBlock(estate.id());
        post("/api/portal/estates/" + estate.id() + "/plots",
                """
                {"plots":[{"plotNumber":"7","priceTierId":"%s","status":"available-dev"}]}"""
                        .formatted(tier.id()));

        assertThat(countRows("audit_log_entries",
                "target_id = '" + estate.id() + "' AND action IN "
                        + "('estate.created','estate.price_tier_added','estate.block_added','estate.plots_added')"))
                .isEqualTo(4);
        assertThat(singleString("SELECT tenant_id::text FROM audit_log_entries WHERE target_id = '"
                + estate.id() + "' AND action = 'estate.created'"))
                .isEqualTo(tenantId.toString());
    }

    // --- helpers ---

    private String estateBody(String name, String ring) {
        if (ring == null) {
            return """
                    {"name":"%s","branchId":"%s","city":"Lagos","state":"Lagos"}""".formatted(name, branchId);
        }
        return """
                {"name":"%s","branchId":"%s","city":"Lagos","state":"Lagos",
                 "footprint":{"type":"Polygon","coordinates":[%s]}}""".formatted(name, branchId, ring);
    }

    private ResponseEntity<EstateDto> createEstate(String body) {
        return restTemplate.exchange("/api/portal/estates", HttpMethod.POST,
                entity(portalToken, body), EstateDto.class);
    }

    private ResponseEntity<String> createEstateRaw(String body) {
        return restTemplate.exchange("/api/portal/estates", HttpMethod.POST,
                entity(portalToken, body), String.class);
    }

    private PriceTierDto createTier(UUID estateId, String body) {
        ResponseEntity<PriceTierDto> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/price-tiers", HttpMethod.POST,
                entity(portalToken, body), PriceTierDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private BlockDto createBlock(UUID estateId) {
        ResponseEntity<BlockDto> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/blocks", HttpMethod.POST,
                entity(portalToken, """
                        {"name":"A","label":"Block A"}"""), BlockDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<String> post(String path, String body) {
        return restTemplate.exchange(path, HttpMethod.POST, entity(portalToken, body), String.class);
    }

    private ResponseEntity<String> rawPost(String token, String path, String body) {
        return restTemplate.exchange(path, HttpMethod.POST, entity(token, body), String.class);
    }

    private HttpEntity<String> entity(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String login(String email) {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().token();
    }

    private String registerBuyer(String email) {
        RegisterRequest request = new RegisterRequest(
                "Portal", "User", email, "+2348000000000", PASSWORD, "NG", Currency.NGN);
        ResponseEntity<AuthResponse> response =
                restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().token();
    }

    private UUID createTenant(String adminToken) {
        String rc = "RC-" + UUID.randomUUID();
        CreateTenantRequest request = new CreateTenantRequest(
                new CompanyIdentityDto("Inventory Co " + rc, null, rc, "Limited Liability (Ltd)", "2020-01-01",
                        new AddressDto("1 Broad Street", "Lagos", "Lagos"),
                        new AddressDto("1 Broad Street", "Lagos", "Lagos"), List.of("Lagos")),
                new PrimaryContactDto("Some Director", "Chief Executive Officer",
                        "ed+" + UUID.randomUUID() + "@example.com", "+2348000000001", "NIN", "12345678901"),
                new CompanyPresenceDto("org+" + rc + "@example.com", "+2348000000002", null,
                        new SocialsDto(null, null, null, null)),
                "starter");

        ResponseEntity<TenantDetailDto> response = restTemplate.exchange(
                "/api/admin/tenants", HttpMethod.POST, entity(adminToken, toJson(request)), TenantDetailDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private String toJson(CreateTenantRequest request) {
        CompanyIdentityDto i = request.identity();
        PrimaryContactDto p = request.primaryContact();
        CompanyPresenceDto c = request.presence();
        return """
                {"identity":{"registeredName":"%s","rcNumber":"%s","companyType":"%s","dateOfIncorporation":"%s",
                 "registeredAddress":{"street":"%s","city":"%s","state":"%s"},
                 "operatingAddress":{"street":"%s","city":"%s","state":"%s"},"statesOfOperation":["Lagos"]},
                 "primaryContact":{"fullName":"%s","roleTitle":"%s","workEmail":"%s","phone":"%s",
                 "govIdType":"%s","govIdNumber":"%s"},
                 "presence":{"companyEmail":"%s","companyPhone":"%s","socials":{}},"plan":"%s"}"""
                .formatted(i.registeredName(), i.rcNumber(), i.companyType(), i.dateOfIncorporation(),
                        i.registeredAddress().street(), i.registeredAddress().city(), i.registeredAddress().state(),
                        i.operatingAddress().street(), i.operatingAddress().city(), i.operatingAddress().state(),
                        p.fullName(), p.roleTitle(), p.workEmail(), p.phone(), p.govIdType(), p.govIdNumber(),
                        c.companyEmail(), c.companyPhone(), request.plan());
    }

    // --- raw JDBC ---

    private static Connection connection() {
        try {
            return DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void execute(String sql) {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID userIdOf(String email) {
        return UUID.fromString(singleString(
                "SELECT id::text FROM users WHERE lower(email) = lower('" + email + "')"));
    }

    private static long countRows(String table, String where) {
        return singleLong("SELECT count(*) FROM " + table + " WHERE " + where);
    }

    private static long singleLong(String sql) {
        return Long.parseLong(singleString(sql));
    }

    private static String singleString(String sql) {
        try (Connection c = connection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
