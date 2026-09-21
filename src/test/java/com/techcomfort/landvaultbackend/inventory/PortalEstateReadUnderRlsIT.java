package com.techcomfort.landvaultbackend.inventory;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.inventory.dto.BlockDto;
import com.techcomfort.landvaultbackend.inventory.dto.CreateBlockRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateEstateRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateEstateTitleRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotsRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePriceTierRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateVerificationCheckRequest;
import com.techcomfort.landvaultbackend.inventory.dto.EstateDetailDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateDto;
import com.techcomfort.landvaultbackend.inventory.dto.EstateSummaryDto;
import com.techcomfort.landvaultbackend.inventory.dto.GeoJsonFeatureCollectionDto;
import com.techcomfort.landvaultbackend.inventory.dto.GeoJsonFeatureDto;
import com.techcomfort.landvaultbackend.inventory.dto.GeoJsonPolygonDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotDetailDto;
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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The inventory read endpoints against a database where row-level security
 * is <strong>actually enforced</strong> — the app connects as the restricted
 * {@code landvault_app} role, not as the container superuser.
 * <p>
 * <strong>Do not convert this class to {@code @ServiceConnection}.</strong>
 * Every isolation assertion below would then pass whether changeset 044's
 * policies exist or not, because a superuser satisfies every {@code USING}
 * clause by never being subject to one. That is exactly how the
 * tenant-staff login bug survived a full green suite, and exactly the shape
 * the three guard tests in {@code RowLevelSecurityIT} exist to make loud.
 * <p>
 * It is also the only place slice 2's <em>write</em> path is exercised under
 * real policies: {@code PortalEstateCreationIT} uses
 * {@code @ServiceConnection}, so it proved the writes work — but not that
 * they satisfy a {@code WITH CHECK}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class PortalEstateReadUnderRlsIT {

    private static final String APP_ROLE = "landvault_app_inventory_read_it";
    private static final String APP_ROLE_PASSWORD = "inventory-read-it-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple";

    /** ~2.2km x 2.2km in Lagos. Lagos deliberately — see PortalEstateCreationIT. */
    private static final List<List<BigDecimal>> ESTATE_RING = ring(
            new double[][] {{3.35, 6.45}, {3.37, 6.45}, {3.37, 6.47}, {3.35, 6.47}, {3.35, 6.45}});
    private static final List<List<BigDecimal>> PLOT_RING = ring(
            new double[][] {{3.355, 6.455}, {3.356, 6.455}, {3.356, 6.456}, {3.355, 6.456}, {3.355, 6.455}});

    private static final BigDecimal CORNER_PREMIUM_PCT = new BigDecimal("10.00");
    private static final BigDecimal LAND_TIER_PRICE = new BigDecimal("20000000.0000");
    private static final BigDecimal LAND_TIER_SIZE = new BigDecimal("250.00");
    private static final BigDecimal UNIT_TIER_PRICE = new BigDecimal("85000000.0000");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "integration-test-signing-secret-of-at-least-32-bytes");

        // Liquibase migrates as the superuser; the app connects as the
        // restricted role. @ServiceConnection cannot express this split, and
        // the split is the entire point of this class.
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

    private UUID tenantId;
    private UUID headOfficeBranchId;
    private UUID satelliteBranchId;
    private String directorToken;
    private String branchManagerToken;

    private UUID alphaEstateId;
    private UUID landTierId;
    private UUID unitTierId;
    private UUID blockId;

    /**
     * One tenant with two branches, an organization-wide Executive Director
     * and a branch manager walled to the second branch — the arrangement the
     * branch-scope rule actually describes, and the first time inventory has
     * exercised it.
     */
    @BeforeEach
    void setUp() {
        tenantId = createTenant(loginAsSuperAdmin());
        headOfficeBranchId = createBranch("Head Office");
        satelliteBranchId = createBranch("Lekki Annex");

        directorToken = staffToken("executive_director", null);
        branchManagerToken = staffToken("branch_manager", satelliteBranchId);

        alphaEstateId = createEstate("Alpha Gardens", headOfficeBranchId, ESTATE_RING).id();
        landTierId = createLandTier(alphaEstateId);
        unitTierId = createUnitTier(alphaEstateId);
        blockId = createBlock(alphaEstateId);

        createPlots(alphaEstateId, new CreatePlotsRequest(List.of(
                plot("001", blockId, landTierId, false, "available-dev", PLOT_RING),
                plot("002", blockId, landTierId, true, "available-dev", null),
                plot("003", blockId, landTierId, false, "reserved", null),
                plot("004", blockId, landTierId, false, "sold", null),
                plot("T01", blockId, unitTierId, false, "available-dev", null))));

        createEstate("Beta Courts", satelliteBranchId, null);
    }

    // --- isolation ---

    /**
     * The branch wall, on inventory, for the first time. Nothing in the
     * repository layer filters by branch — if this passes, it is because
     * Postgres removed the row.
     */
    @Test
    void aBranchManagerSeesOnlyTheirOwnBranchesEstates() {
        List<EstateSummaryDto> visible = listEstates(branchManagerToken).items();

        assertThat(visible)
                .as("a branch manager's scope is a hard wall, not a default they can widen")
                .extracting(EstateSummaryDto::name)
                .containsExactly("Beta Courts");
        assertThat(visible).allSatisfy(estate ->
                assertThat(estate.branchId()).isEqualTo(satelliteBranchId));
    }

    @Test
    void anOrganizationWideDirectorSeesEveryBranchesEstates() {
        assertThat(listEstates(directorToken).items())
                .extracting(EstateSummaryDto::name)
                .containsExactlyInAnyOrder("Alpha Gardens", "Beta Courts");
    }

    /**
     * A branch-scoped caller asking for a sibling branch's estate by id gets
     * the same 404 as for an id that never existed — the policy removes the
     * row before the query runs, so there is nothing to distinguish. A 403
     * here would confirm the estate exists.
     */
    @Test
    void aBranchManagerCannotReachAnotherBranchesEstateById() {
        assertThat(get("/api/portal/estates/" + alphaEstateId, branchManagerToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anotherTenantsEstatesAreInvisible() {
        UUID otherTenantId = createTenant(loginAsSuperAdmin());
        UUID otherBranchId = UUID.randomUUID();
        execute("INSERT INTO branches (id, created_at, deleted, organization_id, name) VALUES ('"
                + otherBranchId + "', now(), false, '" + otherTenantId + "', 'Rival HQ')");
        String rivalToken = staffToken(otherTenantId, "executive_director", null);
        createEstate(rivalToken, "Rival Heights", otherBranchId, ESTATE_RING);

        assertThat(listEstates(directorToken).items())
                .as("tenant isolation is absolute, and it is the database that enforces it")
                .extracting(EstateSummaryDto::name)
                .doesNotContain("Rival Heights");
        assertThat(get("/api/portal/estates/" + alphaEstateId, rivalToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The hazard the fixture above works around, pinned so it is recorded
     * rather than quietly avoided.
     * <p>
     * {@code TenantContextFilter} resolves a user holding both an
     * organization-wide role assignment and a branch-scoped one to
     * <strong>organization-wide</strong> — deliberately, since such a user
     * has legitimate reach beyond one branch and narrowing would hide data
     * they are entitled to (AGENTS.md). That rule was written for two
     * <em>staff</em> roles. It does not distinguish a {@code buyer}
     * assignment, which {@code POST /api/auth/register} grants to every
     * account it creates and which is organization-wide because a buyer is
     * never tenant-scoped at all.
     * <p>
     * The consequence: give a branch manager's user row a leftover buyer
     * assignment and their hard wall silently becomes organization-wide
     * access. No endpoint can produce this today — a buyer has no
     * {@code tenant_id}, and nothing exposes "grant a staff role to an
     * existing account" over HTTP, so it takes direct SQL (which is exactly
     * what a test fixture and the manual-walkthrough setup both do). It
     * becomes reachable the moment an admin role-assignment endpoint ships.
     * <p>
     * This test asserts the CURRENT behaviour, not the desired one. If it
     * ever fails because the wall now holds, that is the fix landing — read
     * the note in AGENTS.md and delete this test rather than restoring it.
     */
    @Test
    void branchScopeIsLostWhenAStaffUserAlsoHoldsAnOrganizationWideRole() {
        String email = "mixed+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest(
                "Mixed", "Scope", email, "+2348000000000", PASSWORD, "NG", Currency.NGN);
        assertThat(restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        execute("UPDATE users SET tenant_id = '" + tenantId + "' WHERE lower(email) = lower('" + email + "')");
        // The buyer assignment from registration is deliberately LEFT in place.
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id, scoped_branch_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id, '" + satelliteBranchId + "' "
                + "FROM users u, roles r WHERE lower(u.email) = lower('" + email
                + "') AND r.code = 'branch_manager'");

        assertThat(listEstates(login(email)).items())
                .as("the leftover organization-wide buyer assignment widens the branch-scoped one")
                .extracting(EstateSummaryDto::name)
                .containsExactlyInAnyOrder("Alpha Gardens", "Beta Courts");
    }

    // --- permissions ---

    /**
     * {@code portal.estates.view} must not imply {@code portal.estates.manage}
     * — the whole reason they are two slugs.
     */
    @Test
    void readPermissionDoesNotGrantWriteAccess() {
        String salesToken = staffToken("sales_manager", null);

        assertThat(listEstates(salesToken).items())
                .as("a sales manager reads the inventory")
                .isNotEmpty();
        assertThat(restTemplate.exchange(
                "/api/portal/estates", HttpMethod.POST,
                entity(salesToken, estateBody("Sales Invented Estate", headOfficeBranchId, null)), String.class)
                .getStatusCode())
                .as("but must not be able to define it")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- pricing ---

    @Test
    void aCornerPlotCostsTheTierPricePlusThePremiumAndANonCornerDoesNot() {
        Map<String, PlotDetailDto> plots = plotsByNumber(alphaEstateId, directorToken);

        PlotDetailDto plain = plots.get("001");
        assertThat(plain.isCorner()).isFalse();
        assertThat(plain.price()).isEqualByComparingTo(LAND_TIER_PRICE);
        assertThat(plain.cornerPremiumPct())
                .as("a premium that was not applied must not be echoed as though it were")
                .isNull();
        assertThat(plain.pricePerSqm()).isEqualByComparingTo("80000.00");

        PlotDetailDto corner = plots.get("002");
        assertThat(corner.isCorner()).isTrue();
        assertThat(corner.basePrice()).isEqualByComparingTo(LAND_TIER_PRICE);
        assertThat(corner.cornerPremiumPct()).isEqualByComparingTo(CORNER_PREMIUM_PCT);
        assertThat(corner.price())
                .as("20,000,000 + 10%")
                .isEqualByComparingTo("22000000");
        assertThat(corner.pricePerSqm()).isEqualByComparingTo("88000.00");
    }

    /**
     * An apartment has no exclusive land area, so it has no per-square-metre
     * rate. Absent, never zero — see AGENTS.md's no-fabricated-data rule.
     */
    @Test
    void aUnitTypePlotHasNoNominalSizeAndThereforeNoPricePerSqm() {
        PlotDetailDto unit = plotsByNumber(alphaEstateId, directorToken).get("T01");

        assertThat(unit.nominalSizeSqm()).isNull();
        assertThat(unit.pricePerSqm()).isNull();
        assertThat(unit.price())
                .as("the tier's price still applies — only the derived rate is absent")
                .isEqualByComparingTo(UNIT_TIER_PRICE);
    }

    // --- counts ---

    @Test
    void plotCountsBreakDownByStatusAndOmitStatusesWithNoPlots() {
        EstateSummaryDto alpha = listEstates(directorToken).items().stream()
                .filter(estate -> estate.name().equals("Alpha Gardens"))
                .findFirst()
                .orElseThrow();

        assertThat(alpha.plotCounts().total()).isEqualTo(5L);
        assertThat(alpha.plotCounts().byStatus())
                .containsEntry("available-dev", 3L)
                .containsEntry("reserved", 1L)
                .containsEntry("sold", 1L)
                .as("a status with no plots is absent, not reported as zero")
                .doesNotContainKey("available-inv");
    }

    @Test
    void anEstateWithNoPlotsReportsZeroAndAnEmptyBreakdown() {
        EstateSummaryDto beta = listEstates(branchManagerToken).items().getFirst();

        assertThat(beta.plotCounts().total()).isZero();
        assertThat(beta.plotCounts().byStatus()).isEmpty();
    }

    // --- detail ---

    @Test
    void estateDetailCarriesItsBlocksTiersTitleAndChecks() {
        post("/api/portal/estates/" + alphaEstateId + "/title", directorToken,
                new CreateEstateTitleRequest("C of O", "LA-2024-001", "2024-03-01", null, null));
        post("/api/portal/estates/" + alphaEstateId + "/verification-checks", directorToken,
                new CreateVerificationCheckRequest("title_verification", "verified", "manual_review",
                        "Confirmed at Alausa."));

        EstateDetailDto detail = get(
                "/api/portal/estates/" + alphaEstateId, directorToken, EstateDetailDto.class).getBody();

        assertThat(detail.name()).isEqualTo("Alpha Gardens");
        assertThat(detail.blocks()).extracting(BlockDto::name).containsExactly("A");
        assertThat(detail.priceTiers()).extracting(PriceTierDto::id)
                .containsExactlyInAnyOrder(landTierId, unitTierId);
        assertThat(detail.title().titleNumber()).isEqualTo("LA-2024-001");
        assertThat(detail.verificationChecks()).singleElement()
                .satisfies(check -> assertThat(check.status()).isEqualTo("verified"));
        assertThat(detail.footprintAreaSqm())
                .as("square metres via the geography cast, not square degrees")
                .isCloseTo(new BigDecimal("4900000"), within(new BigDecimal("100000")));
    }

    /**
     * An absent check is not a passed one — nothing manufactures a
     * placeholder row to fill the shape out.
     */
    @Test
    void anEstateWithNoTitleOrChecksReportsTheirAbsenceHonestly() {
        EstateDetailDto detail = get(
                "/api/portal/estates/" + alphaEstateId, directorToken, EstateDetailDto.class).getBody();

        assertThat(detail.title()).isNull();
        assertThat(detail.verificationChecks()).isEmpty();
    }

    // --- geojson ---

    /**
     * The point of the whole GeoJSON surface: what was posted is what comes
     * back, coordinate for coordinate and in the same
     * {@code [longitude, latitude]} order.
     */
    @Test
    void geoJsonRoundTripsTheExactBoundaryThatWasPosted() {
        GeoJsonFeatureCollectionDto collection = get(
                "/api/portal/estates/" + alphaEstateId + "/geojson",
                directorToken, GeoJsonFeatureCollectionDto.class).getBody();

        assertThat(collection.type()).isEqualTo("FeatureCollection");

        GeoJsonFeatureDto estateFeature = collection.features().getFirst();
        assertThat(estateFeature.properties()).containsEntry("kind", "estate");
        assertThat(estateFeature.geometry().type()).isEqualTo("Polygon");
        assertCoordinatesMatch(estateFeature.geometry(), ESTATE_RING);
    }

    @Test
    void geoJsonOmitsPlotsThatHaveNoBoundaryRatherThanEmittingEmptyOnes() {
        GeoJsonFeatureCollectionDto collection = get(
                "/api/portal/estates/" + alphaEstateId + "/geojson",
                directorToken, GeoJsonFeatureCollectionDto.class).getBody();

        List<GeoJsonFeatureDto> plotFeatures = collection.features().stream()
                .filter(feature -> "plot".equals(feature.properties().get("kind")))
                .toList();

        assertThat(plotFeatures)
                .as("four of the five plots have no footprint; a null geometry would render at [0,0]")
                .singleElement()
                .satisfies(feature -> {
                    assertThat(feature.properties()).containsEntry("plotNumber", "001");
                    assertThat(feature.properties()).containsEntry("status", "available-dev");
                    assertCoordinatesMatch(feature.geometry(), PLOT_RING);
                });
        assertThat(collection.features()).allSatisfy(feature ->
                assertThat(feature.geometry()).isNotNull());
    }

    @Test
    void aBranchManagerCannotReadAnotherBranchesGeoJson() {
        assertThat(get("/api/portal/estates/" + alphaEstateId + "/geojson", branchManagerToken, String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- filters ---

    @Test
    void plotsCanBeFilteredByStatus() {
        var page = restTemplate.exchange(
                "/api/portal/estates/" + alphaEstateId + "/plots?status=sold",
                HttpMethod.GET, new HttpEntity<>(bearer(directorToken)),
                new ParameterizedTypeReference<PageResponse<PlotDetailDto>>() {
                }).getBody();

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items()).singleElement()
                .satisfies(plot -> assertThat(plot.plotNumber()).isEqualTo("004"));
    }

    @Test
    void estatesCanBeFilteredByName() {
        assertThat(listEstates(directorToken, "?q=beta").items())
                .extracting(EstateSummaryDto::name)
                .containsExactly("Beta Courts");
    }

    // --- helpers ---

    private static void assertCoordinatesMatch(GeoJsonPolygonDto geometry, List<List<BigDecimal>> expectedRing) {
        List<List<BigDecimal>> actual = geometry.coordinates().getFirst();
        assertThat(actual).hasSameSizeAs(expectedRing);
        for (int i = 0; i < expectedRing.size(); i++) {
            assertThat(actual.get(i).get(0).doubleValue())
                    .as("longitude at position " + i)
                    .isEqualTo(expectedRing.get(i).get(0).doubleValue());
            assertThat(actual.get(i).get(1).doubleValue())
                    .as("latitude at position " + i)
                    .isEqualTo(expectedRing.get(i).get(1).doubleValue());
        }
    }

    private static List<List<BigDecimal>> ring(double[][] positions) {
        return Arrays.stream(positions)
                .map(position -> List.of(BigDecimal.valueOf(position[0]), BigDecimal.valueOf(position[1])))
                .toList();
    }

    private PageResponse<EstateSummaryDto> listEstates(String token) {
        return listEstates(token, "");
    }

    private PageResponse<EstateSummaryDto> listEstates(
            String token, String queryString) {
        return restTemplate.exchange(
                "/api/portal/estates" + queryString, HttpMethod.GET, new HttpEntity<>(bearer(token)),
                new ParameterizedTypeReference<PageResponse<EstateSummaryDto>>() {
                }).getBody();
    }

    private Map<String, PlotDetailDto> plotsByNumber(UUID estateId, String token) {
        return restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/plots?limit=100", HttpMethod.GET,
                new HttpEntity<>(bearer(token)),
                new ParameterizedTypeReference<PageResponse<PlotDetailDto>>() {
                }).getBody().items().stream()
                .collect(Collectors.toMap(PlotDetailDto::plotNumber, plot -> plot));
    }

    private static CreatePlotRequest plot(
            String number, UUID blockId, UUID tierId, boolean corner, String status,
            List<List<BigDecimal>> footprintRing) {
        return new CreatePlotRequest(number, blockId, tierId, corner, status, null, null, null, null, null,
                footprintRing == null ? null : new GeoJsonPolygonDto("Polygon", List.of(footprintRing)));
    }

    private EstateDto createEstate(String name, UUID branchId, List<List<BigDecimal>> footprintRing) {
        return createEstate(directorToken, name, branchId, footprintRing);
    }

    private EstateDto createEstate(
            String token, String name, UUID branchId, List<List<BigDecimal>> footprintRing) {
        ResponseEntity<EstateDto> response = restTemplate.exchange(
                "/api/portal/estates", HttpMethod.POST,
                entity(token, estateBody(name, branchId, footprintRing)), EstateDto.class);
        assertThat(response.getStatusCode())
                .as("estate creation must satisfy the WITH CHECK clause of changeset 044's policies")
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static CreateEstateRequest estateBody(
            String name, UUID branchId, List<List<BigDecimal>> footprintRing) {
        return new CreateEstateRequest(
                name, null, "Ikoyi", "Lagos", "Lagos", "1 Test Close", CORNER_PREMIUM_PCT,
                null, List.of("Perimeter fence"), branchId,
                footprintRing == null ? null : new GeoJsonPolygonDto("Polygon", List.of(footprintRing)));
    }

    private UUID createLandTier(UUID estateId) {
        return post("/api/portal/estates/" + estateId + "/price-tiers", directorToken,
                new CreatePriceTierRequest("LAND_SIZE", LAND_TIER_SIZE, LAND_TIER_PRICE, Currency.NGN,
                        "Standard 250"), PriceTierDto.class).id();
    }

    private UUID createUnitTier(UUID estateId) {
        return post("/api/portal/estates/" + estateId + "/price-tiers", directorToken,
                new CreatePriceTierRequest("UNIT_TYPE", null, UNIT_TIER_PRICE, Currency.NGN,
                        "3-bedroom terrace"), PriceTierDto.class).id();
    }

    private UUID createBlock(UUID estateId) {
        return post("/api/portal/estates/" + estateId + "/blocks", directorToken,
                new CreateBlockRequest("A", "Block A"), BlockDto.class).id();
    }

    private void createPlots(UUID estateId, CreatePlotsRequest request) {
        ResponseEntity<List<PlotDto>> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/plots", HttpMethod.POST,
                entity(directorToken, request), new ParameterizedTypeReference<>() {
                });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private UUID createBranch(String name) {
        UUID branchId = UUID.randomUUID();
        execute("INSERT INTO branches (id, created_at, deleted, organization_id, name) VALUES ('"
                + branchId + "', now(), false, '" + tenantId + "', '" + name + "')");
        return branchId;
    }

    private String staffToken(String roleCode, UUID scopedBranchId) {
        return staffToken(tenantId, roleCode, scopedBranchId);
    }

    /**
     * A staff account whose password we know. The Executive Director the
     * tenant-creation flow makes has an unrecoverable random password by
     * design, so the role is granted to a registered account instead.
     * <p>
     * {@code scoped_branch_id} lives on the <em>role assignment</em>, not on
     * the user — the same person can hold an organization-wide role and a
     * branch-scoped one at once. See AGENTS.md.
     */
    private String staffToken(UUID owningTenantId, String roleCode, UUID scopedBranchId) {
        String email = roleCode + "+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest(
                "Portal", "Staff", email, "+2348000000000", PASSWORD, "NG", Currency.NGN);
        assertThat(restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        execute("UPDATE users SET tenant_id = '" + owningTenantId + "' WHERE lower(email) = lower('" + email + "')");
        // Drop the buyer assignment /api/auth/register grants. It is not
        // tidiness: a buyer role is organization-wide (no scoped_branch_id),
        // and TenantContextFilter widens a user holding BOTH an
        // organization-wide and a branch-scoped assignment to
        // organization-wide — so leaving it here silently dissolves the
        // branch manager's hard wall and made this whole class pass for the
        // wrong reason. See branchScopeIsLostWhenAStaffUserAlsoHoldsAnOrganizationWideRole.
        execute("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('"
                + email + "'))");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id, scoped_branch_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id, "
                + (scopedBranchId == null ? "NULL" : "'" + scopedBranchId + "'")
                + " FROM users u, roles r WHERE lower(u.email) = lower('" + email + "') AND r.code = '"
                + roleCode + "'");

        return login(email);
    }

    private String login(String email) {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class);
        assertThat(response.getStatusCode()).as("login for " + email).isEqualTo(HttpStatus.OK);
        return response.getBody().token();
    }

    private String loginAsSuperAdmin() {
        return login(ADMIN_EMAIL);
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
                "/api/admin/tenants", HttpMethod.POST, entity(adminToken, request), TenantDetailDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private <T> ResponseEntity<T> get(String path, String token, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), type);
    }

    private <T> T post(String path, String token, Object body, Class<T> type) {
        ResponseEntity<T> response = restTemplate.exchange(
                path, HttpMethod.POST, entity(token, body), type);
        assertThat(response.getStatusCode()).as("POST " + path).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void post(String path, String token, Object body) {
        post(path, token, body, String.class);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static HttpEntity<Object> entity(String token, Object body) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    // Superuser connection, deliberately: fixture setup writes rows the app's
    // own restricted role could not see to write.
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
