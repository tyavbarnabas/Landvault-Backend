package com.techcomfort.landvaultbackend.inventory;

import com.techcomfort.landvaultbackend.checkout.dto.CreateReservationRequest;
import com.techcomfort.landvaultbackend.checkout.dto.ReservationDto;
import com.techcomfort.landvaultbackend.checkout.dto.TransactionDto;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonPolygonDto;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.inventory.dto.BlockDto;
import com.techcomfort.landvaultbackend.inventory.dto.CreateBlockRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreateEstateRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotsRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePriceTierRequest;
import com.techcomfort.landvaultbackend.inventory.dto.EstateDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotDetailDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierImpactDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierUpdateDto;
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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inventory editing, slice 1 — tier price, label and size, and block names —
 * against a database where row-level security is actually enforced.
 * <p>
 * <strong>Do not convert this class to {@code @ServiceConnection}.</strong>
 * The size change is a native {@code UPDATE} on {@code plots} running under
 * the tenant's own policies, and the reserved-plot fixtures go through the
 * buyer-side definer functions; on a superuser connection neither would be
 * tested at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class InventoryEditUnderRlsIT {

    private static final String APP_ROLE = "landvault_app_inventory_edit_it";
    private static final String APP_ROLE_PASSWORD = "inventory-edit-it-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple";

    private static final BigDecimal TIER_PRICE = new BigDecimal("20000000.0000");
    private static final BigDecimal TIER_SIZE = new BigDecimal("250.00");

    /** Each estate gets its own longitude band, so fixtures never overlap into conflicts. */
    private static final AtomicInteger BAND = new AtomicInteger();

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

    private Tenant seller;
    private UUID estateId;
    private UUID tierId;
    private UUID blockId;
    private UUID plotId;

    @BeforeEach
    void setUp() {
        seller = verifiedTenant();
        Listing listing = publishedListing(seller);
        estateId = listing.estateId();
        tierId = listing.tierId();
        blockId = listing.blockId();
        plotId = listing.plotId();
    }

    // ------------------------------------------------------------------
    // IE-1 — price
    // ------------------------------------------------------------------

    @Test
    void oneEditRepricesEveryPlotOnTheTier() {
        UUID second = addPlot("002", "available-inv");

        ResponseEntity<PriceTierUpdateDto> response = putTier("{\"price\":25000000}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().tier().price()).isEqualByComparingTo("25000000");
        assertThat(response.getBody().sizeChange()).as("no size was sent").isNull();
        assertThat(plotDetail(plotId).price()).isEqualByComparingTo("25000000");
        assertThat(plotDetail(second).price())
                .as("that is the point of tiers — one edit, every plot")
                .isEqualByComparingTo("25000000");
    }

    /**
     * IE-3, through the real edit endpoint: the buyer agreed when they took
     * the hold, and a price change afterwards must not reach them.
     */
    @Test
    void aBuyerWhoReservedKeepsTheirCapturedPrice() {
        Buyer buyer = verifiedBuyer();
        ReservationDto reservation = reserve(buyer, plotId);

        assertThat(putTier("{\"price\":99000000}").getStatusCode()).isEqualTo(HttpStatus.OK);
        TransactionDto transaction = createTransaction(buyer, reservation.id());

        assertThat(transaction.basePrice()).isEqualByComparingTo(TIER_PRICE);
        assertThat(transaction.totalPrice()).isEqualByComparingTo(TIER_PRICE);
        assertThat(new BigDecimal(queryString(
                "SELECT total_price FROM reservations WHERE id = '" + reservation.id() + "'")))
                .isEqualByComparingTo(TIER_PRICE);
    }

    // ------------------------------------------------------------------
    // IE-4 — size, and the available-only rule
    // ------------------------------------------------------------------

    @Test
    void aSizeChangeReachesOnlyAvailablePlots() {
        UUID reserved = addPlot("002", "available-dev");
        UUID sold = addPlot("003", "available-dev");
        reserve(verifiedBuyer(), reserved);
        execute("UPDATE plots SET status = 'SOLD' WHERE id = '" + sold + "'");

        ResponseEntity<PriceTierUpdateDto> response = putTier("{\"sizeSqm\":300}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PriceTierUpdateDto.SizeChange change = response.getBody().sizeChange();
        assertThat(change.previousSizeSqm()).isEqualByComparingTo(TIER_SIZE);
        assertThat(change.newSizeSqm()).isEqualByComparingTo("300");
        assertThat(change.plotsUpdated()).isEqualTo(1);
        assertThat(change.keptPreviousSize().total()).isEqualTo(2);
        assertThat(change.keptPreviousSize().byStatus()).isEqualTo(Map.of("reserved", 1L, "sold", 1L));

        assertThat(nominalSize(plotId)).isEqualByComparingTo("300");
        assertThat(nominalSize(reserved))
                .as("a reserved plot's size is what its buyer agreed to — the reservation locks price, not size")
                .isEqualByComparingTo(TIER_SIZE);
        assertThat(nominalSize(sold)).isEqualByComparingTo(TIER_SIZE);
    }

    /**
     * Changeset 060: a plot skipped by a size change because it was held
     * takes the tier's current size when the hold ends, rather than going
     * back on sale disagreeing with its own tier.
     */
    @Test
    void aReservedPlotReturningToThePoolTakesTheTiersCurrentSize() {
        Buyer buyer = verifiedBuyer();
        ReservationDto reservation = reserve(buyer, plotId);
        putTier("{\"sizeSqm\":300}");
        assertThat(nominalSize(plotId)).isEqualByComparingTo(TIER_SIZE);

        ResponseEntity<String> cancelled = restTemplate.exchange(
                "/api/reservations/" + reservation.id(), HttpMethod.DELETE,
                new HttpEntity<>(bearer(buyer.token())), String.class);

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).isEqualTo("AVAILABLE_DEV");
        assertThat(nominalSize(plotId)).isEqualByComparingTo("300");
    }

    @Test
    void aSizeCannotBeGivenToAUnitTypeTier() {
        UUID unitTier = post(seller, "/api/portal/estates/" + estateId + "/price-tiers",
                new CreatePriceTierRequest("UNIT_TYPE", null, new BigDecimal("85000000"),
                        Currency.NGN, "3-bedroom terrace"), PriceTierDto.class).id();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/price-tiers/" + unitTier, HttpMethod.PUT,
                entity(seller.token(), "{\"sizeSqm\":300}"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aSizeAnotherTierAlreadyHasIsAConflict() {
        post(seller, "/api/portal/estates/" + estateId + "/price-tiers",
                new CreatePriceTierRequest("LAND_SIZE", new BigDecimal("500.00"), new BigDecimal("35000000"),
                        Currency.NGN, "Large"), PriceTierDto.class);

        ResponseEntity<String> response = putTierRaw("{\"sizeSqm\":500}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(nominalSize(plotId)).as("nothing partially applied").isEqualByComparingTo(TIER_SIZE);
    }

    // ------------------------------------------------------------------
    // Immutable fields
    // ------------------------------------------------------------------

    @Test
    void aTiersTypeCannotChange() {
        ResponseEntity<String> response = putTierRaw("{\"tierType\":\"UNIT_TYPE\",\"price\":1}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("TIER_TYPE_IMMUTABLE");
        assertThat(tierPrice()).as("refused outright, not half-applied").isEqualByComparingTo(TIER_PRICE);
    }

    @Test
    void aTiersCurrencyCannotChange() {
        ResponseEntity<String> response = putTierRaw("{\"currency\":\"USD\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("TIER_CURRENCY_IMMUTABLE");
    }

    /** A client echoing the tier back unchanged is not asking to change them. */
    @Test
    void sendingTheCurrentTypeAndCurrencyIsAccepted() {
        ResponseEntity<PriceTierUpdateDto> response =
                putTier("{\"tierType\":\"LAND_SIZE\",\"currency\":\"NGN\",\"price\":21000000}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().tier().price()).isEqualByComparingTo("21000000");
    }

    // ------------------------------------------------------------------
    // IE-2 — impact preview
    // ------------------------------------------------------------------

    @Test
    void theImpactPreviewCountsMatchRealityByStatus() {
        addPlot("002", "available-inv");
        UUID reserved = addPlot("003", "available-dev");
        UUID sold = addPlot("004", "available-dev");
        reserve(verifiedBuyer(), reserved);
        execute("UPDATE plots SET status = 'SOLD' WHERE id = '" + sold + "'");

        ResponseEntity<PriceTierImpactDto> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/price-tiers/" + tierId + "/impact", HttpMethod.GET,
                new HttpEntity<>(bearer(seller.token())), PriceTierImpactDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().plots().total()).isEqualTo(4);
        assertThat(response.getBody().plots().byStatus()).isEqualTo(Map.of(
                "available-dev", 1L, "available-inv", 1L, "reserved", 1L, "sold", 1L));
    }

    // ------------------------------------------------------------------
    // IE-6 — blocks
    // ------------------------------------------------------------------

    @Test
    void renamingABlockToAnExistingNameIsACleanConflict() {
        post(seller, "/api/portal/estates/" + estateId + "/blocks",
                new CreateBlockRequest("B", "Block B"), BlockDto.class);

        ResponseEntity<String> response = putBlock("{\"name\":\"B\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("DUPLICATE_RECORD").contains("already exists");
    }

    @Test
    void aBlockCanBeRenamed() {
        ResponseEntity<String> response = putBlock("{\"name\":\"D\",\"label\":\"Block D\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT name FROM blocks WHERE id = '" + blockId + "'")).isEqualTo("D");
        assertThat(queryString("SELECT label FROM blocks WHERE id = '" + blockId + "'")).isEqualTo("Block D");
    }

    // ------------------------------------------------------------------
    // Audit
    // ------------------------------------------------------------------

    @Test
    void everyEditIsAuditedWithThePreviousValue() {
        putTier("{\"price\":25000000,\"sizeSqm\":300}");
        putBlock("{\"name\":\"D\"}");

        String tierDetail = queryString("SELECT detail FROM audit_log_entries WHERE action = "
                + "'estate.price_tier_updated' AND target_id = '" + tierId + "'");
        assertThat(tierDetail)
                .contains("20000000.0000 -> NGN 25000000")
                .contains("size 250.00 -> 300");
        assertThat(queryString("SELECT detail FROM audit_log_entries WHERE action = "
                + "'estate.block_updated' AND target_id = '" + blockId + "'"))
                .contains("name 'A' -> 'D'");
    }

    /** Nothing happened, so nothing is recorded — the same rule publish uses. */
    @Test
    void anEditThatChangesNothingWritesNoAuditEntry() {
        assertThat(putTier("{\"price\":20000000}").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = "
                + "'estate.price_tier_updated' AND target_id = '" + tierId + "'")).isEqualTo("0");
    }

    // ------------------------------------------------------------------
    // Who may edit
    // ------------------------------------------------------------------

    @Test
    void aBuyerCannotEditATier() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/price-tiers/" + tierId, HttpMethod.PUT,
                entity(verifiedBuyer().token(), "{\"price\":1}"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** portal.estates.view previews; it does not edit. */
    @Test
    void aViewOnlyUserCanPreviewButNotEdit() {
        String salesManager = staffToken(seller.id(), "sales_manager");

        assertThat(restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/price-tiers/" + tierId, HttpMethod.PUT,
                entity(salesManager, "{\"price\":1}"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/blocks/" + blockId, HttpMethod.PUT,
                entity(salesManager, "{\"name\":\"Z\"}"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/price-tiers/" + tierId + "/impact", HttpMethod.GET,
                new HttpEntity<>(bearer(salesManager)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void anotherCompanyCannotEditThisTier() {
        Tenant stranger = verifiedTenant();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/price-tiers/" + tierId, HttpMethod.PUT,
                entity(stranger.token(), "{\"price\":1}"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(tierPrice()).isEqualByComparingTo(TIER_PRICE);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private ResponseEntity<PriceTierUpdateDto> putTier(String json) {
        return restTemplate.exchange("/api/portal/estates/" + estateId + "/price-tiers/" + tierId,
                HttpMethod.PUT, entity(seller.token(), json), PriceTierUpdateDto.class);
    }

    private ResponseEntity<String> putTierRaw(String json) {
        return restTemplate.exchange("/api/portal/estates/" + estateId + "/price-tiers/" + tierId,
                HttpMethod.PUT, entity(seller.token(), json), String.class);
    }

    private ResponseEntity<String> putBlock(String json) {
        return restTemplate.exchange("/api/portal/estates/" + estateId + "/blocks/" + blockId,
                HttpMethod.PUT, entity(seller.token(), json), String.class);
    }

    private PlotDetailDto plotDetail(UUID plot) {
        ResponseEntity<PlotDetailDto> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/plots/" + plot, HttpMethod.GET,
                new HttpEntity<>(bearer(seller.token())), PlotDetailDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ReservationDto reserve(Buyer who, UUID plot) {
        ResponseEntity<ReservationDto> response = restTemplate.exchange("/api/reservations", HttpMethod.POST,
                entity(who.token(), new CreateReservationRequest(plot)), ReservationDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TransactionDto createTransaction(Buyer who, UUID reservationId) {
        ResponseEntity<TransactionDto> response = restTemplate.exchange(
                "/api/checkout/transactions", HttpMethod.POST,
                entity(who.token(), """
                        {"reservationId":"%s","intent":"development","plan":"outright"}
                        """.formatted(reservationId)), TransactionDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private Buyer verifiedBuyer() {
        String email = "buyer+" + UUID.randomUUID() + "@example.com";
        RegisterRequest request = new RegisterRequest(
                "Ada", "Buyer", email, "+2348000000000", PASSWORD, "NG", Currency.NGN);
        assertThat(restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = userId(email);
        String token = login(email);
        postJson(token, "/api/kyc", "{\"ninNumber\":\"12345678901\",\"ninFile\":{\"fileName\":\"nin.pdf\"}}");
        postJson(adminToken(), "/api/admin/kyc/" + id + "/decision", "{\"decision\":\"approved\"}");
        return new Buyer(id, email, login(email));
    }

    private Tenant verifiedTenant() {
        String rc = "RC-" + UUID.randomUUID();
        CreateTenantRequest request = new CreateTenantRequest(
                new CompanyIdentityDto("Edit Co " + rc, null, rc, "Limited Liability (Ltd)", "2020-01-01",
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
        UUID id = response.getBody().id();

        execute("UPDATE organizations SET verification_state = 'VERIFIED', marketplace_publishing = true, "
                + "status = 'ACTIVE' WHERE id = '" + id + "'");
        return new Tenant(id, staffToken(id, "executive_director"));
    }

    private Listing publishedListing(Tenant tenant) {
        UUID branchId = UUID.randomUUID();
        execute("INSERT INTO branches (id, created_at, deleted, organization_id, name) VALUES ('"
                + branchId + "', now(), false, '" + tenant.id() + "', 'Head Office')");

        int band = BAND.incrementAndGet();
        EstateDto estate = restTemplate.exchange("/api/portal/estates", HttpMethod.POST,
                entity(tenant.token(), new CreateEstateRequest(
                        "Edit Gardens " + band, null, "Gwarinpa", "FCT", "Abuja", "1 Test Close",
                        new BigDecimal("10.00"), null, List.of("Perimeter fence"), branchId,
                        new GeoJsonPolygonDto("Polygon", List.of(estateRing(band))))),
                EstateDto.class).getBody();

        UUID tier = post(tenant, "/api/portal/estates/" + estate.id() + "/price-tiers",
                new CreatePriceTierRequest("LAND_SIZE", TIER_SIZE, TIER_PRICE, Currency.NGN, "Standard 250"),
                PriceTierDto.class).id();
        UUID block = post(tenant, "/api/portal/estates/" + estate.id() + "/blocks",
                new CreateBlockRequest("A", "Block A"), BlockDto.class).id();

        ResponseEntity<String> plots = restTemplate.exchange(
                "/api/portal/estates/" + estate.id() + "/plots", HttpMethod.POST,
                entity(tenant.token(), new CreatePlotsRequest(List.of(
                        new CreatePlotRequest("001", block, tier, false, "available-dev",
                                null, null, null, null, null, null)))),
                String.class);
        assertThat(plots.getStatusCode()).as(plots.getBody()).isEqualTo(HttpStatus.CREATED);

        // Disclosure is a publication condition (changeset 059); these tests
        // are not about fees, so they declare the honest minimum.
        assertThat(restTemplate.exchange(
                "/api/portal/estates/" + estate.id() + "/fees", HttpMethod.PUT,
                entity(tenant.token(), "{\"fees\":[]}"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.exchange(
                "/api/portal/estates/" + estate.id() + "/refund-terms", HttpMethod.PUT,
                entity(tenant.token(), """
                        {"deductionPct":20.00,"processingDays":90,"appliesTo":"total_price"}
                        """), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> published = restTemplate.exchange(
                "/api/portal/estates/" + estate.id() + "/publish", HttpMethod.POST,
                entity(tenant.token(), ""), String.class);
        assertThat(published.getStatusCode()).as(published.getBody()).isEqualTo(HttpStatus.OK);

        return new Listing(estate.id(), tier, block, UUID.fromString(queryString(
                "SELECT id FROM plots WHERE estate_id = '" + estate.id() + "' AND plot_number = '001'")));
    }

    private UUID addPlot(String number, String status) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/plots", HttpMethod.POST,
                entity(seller.token(), new CreatePlotsRequest(List.of(
                        new CreatePlotRequest(number, null, tierId, false, status,
                                null, null, null, null, null, null)))),
                String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(queryString(
                "SELECT id FROM plots WHERE estate_id = '" + estateId + "' AND plot_number = '" + number + "'"));
    }

    /** ~1.1km squares, each estate in its own longitude band inside FCT. */
    private static List<List<BigDecimal>> estateRing(int band) {
        double lng = 7.30 + band * 0.05;
        double lat = 9.05;
        return Arrays.stream(new double[][] {
                        {lng, lat}, {lng + 0.01, lat}, {lng + 0.01, lat + 0.01}, {lng, lat + 0.01}, {lng, lat}})
                .map(position -> List.of(BigDecimal.valueOf(position[0]), BigDecimal.valueOf(position[1])))
                .toList();
    }

    private String staffToken(UUID owningTenantId, String roleCode) {
        String email = "staff+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest(
                "Portal", "Staff", email, "+2348000000000", PASSWORD, "NG", Currency.NGN);
        assertThat(restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        execute("UPDATE users SET tenant_id = '" + owningTenantId + "' WHERE lower(email) = lower('" + email + "')");
        // The leftover buyer assignment would widen this user's scope — see
        // PortalEstateReadUnderRlsIT for the hazard this works around.
        execute("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('"
                + email + "'))");
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

    private <T> T post(Tenant tenant, String path, Object body, Class<T> type) {
        ResponseEntity<T> response = restTemplate.exchange(path, HttpMethod.POST,
                entity(tenant.token(), body), type);
        assertThat(response.getStatusCode()).as("POST " + path).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void postJson(String token, String path, String json) {
        ResponseEntity<String> response = restTemplate.exchange(
                path, HttpMethod.POST, entity(token, json), String.class);
        assertThat(response.getStatusCode()).as("POST " + path).isIn(HttpStatus.OK, HttpStatus.CREATED);
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

    // --- direct database reads, as the superuser

    private BigDecimal nominalSize(UUID plot) {
        return new BigDecimal(queryString("SELECT nominal_size_sqm FROM plots WHERE id = '" + plot + "'"));
    }

    private BigDecimal tierPrice() {
        return new BigDecimal(queryString("SELECT price FROM price_tiers WHERE id = '" + tierId + "'"));
    }

    private UUID userId(String email) {
        return UUID.fromString(queryString(
                "SELECT id FROM users WHERE lower(email) = lower('" + email + "')"));
    }

    private static String queryString(String sql) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
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

    private record Buyer(UUID id, String email, String token) {
    }

    private record Tenant(UUID id, String token) {
    }

    private record Listing(UUID estateId, UUID tierId, UUID blockId, UUID plotId) {
    }
}
