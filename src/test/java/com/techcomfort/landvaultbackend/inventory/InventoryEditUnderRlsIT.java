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
import com.techcomfort.landvaultbackend.inventory.dto.BulkPlotStatusRequest;
import com.techcomfort.landvaultbackend.inventory.dto.ChangePlotStatusRequest;
import com.techcomfort.landvaultbackend.inventory.dto.PlotStatusChangeDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotImportIssueDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotImportReportDto;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import java.nio.charset.StandardCharsets;
import com.techcomfort.landvaultbackend.inventory.dto.CorrectPlotBoundaryRequest;
import com.techcomfort.landvaultbackend.inventory.dto.MovePlotTierRequest;
import com.techcomfort.landvaultbackend.inventory.dto.PlotBoundaryDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotTierChangeDto;
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
    // IE-9 — correcting a plot's boundary
    // ------------------------------------------------------------------

    @Test
    void correctingABoundaryRecomputesTheSurveyedArea() {
        PlotBoundaryDto first = putPlotBoundary(plotId, square(0.001, 0.001, 0.001)).getBody();
        PlotBoundaryDto second = putPlotBoundary(plotId, square(0.001, 0.001, 0.002)).getBody();

        assertThat(first.previousActualAreaSqm()).as("the fixture plot had no boundary").isNull();
        assertThat(second.previousActualAreaSqm()).isEqualByComparingTo(first.actualAreaSqm());
        // A 0.002° square is four times a 0.001° one; metres, not degrees.
        assertThat(second.actualAreaSqm().doubleValue())
                .isCloseTo(first.actualAreaSqm().doubleValue() * 4, org.assertj.core.data.Percentage.withPercentage(1));
        assertThat(first.actualAreaSqm().doubleValue()).isBetween(10_000.0, 14_000.0);
        assertThat(new BigDecimal(queryString("SELECT actual_area_sqm FROM plots WHERE id = '" + plotId + "'")))
                .as("stored, not just reported")
                .isEqualByComparingTo(second.actualAreaSqm());
    }

    /**
     * The point of IE-9: plot-conflict auto-resolution was built and could
     * never fire while footprints were immutable. A correction that creates
     * an overlap is recorded too.
     */
    @Test
    void aCorrectionCanCreateAnOverlapAndAnotherCanClearIt() {
        UUID second = addPlot("002", "available-dev");
        putPlotBoundary(plotId, square(0.001, 0.001, 0.002));

        PlotBoundaryDto overlapping = putPlotBoundary(second, square(0.002, 0.002, 0.002)).getBody();
        assertThat(overlapping.plotOverlapsInEstateBefore()).isZero();
        assertThat(overlapping.plotOverlapsInEstateAfter()).as("a correction may also create a conflict").isEqualTo(1);
        assertThat(conflictCount("OPEN")).isEqualTo(1);

        PlotBoundaryDto fixed = putPlotBoundary(second, square(0.005, 0.005, 0.002)).getBody();
        assertThat(fixed.plotOverlapsInEstateBefore()).isEqualTo(1);
        assertThat(fixed.plotOverlapsInEstateAfter()).isZero();
        assertThat(conflictCount("AUTO_RESOLVED")).as("resolved, and the record kept").isEqualTo(1);
    }

    @Test
    void aBoundaryOutsideTheEstateIsRefusedAndNothingChanges() {
        ResponseEntity<String> refused = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/plots/" + plotId + "/boundary", HttpMethod.PUT,
                entity(seller.token(), boundaryBody(square(0.02, 0.0, 0.001))), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("PLOT_OUTSIDE_ESTATE").contains("Block A, Plot 001");
        assertThat(queryString("SELECT footprint IS NULL FROM plots WHERE id = '" + plotId + "'")).isEqualTo("t");
    }

    @Test
    void aReservedPlotsBoundaryCannotBeCorrected() {
        reserve(verifiedBuyer(), plotId);

        ResponseEntity<String> refused = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/plots/" + plotId + "/boundary", HttpMethod.PUT,
                entity(seller.token(), boundaryBody(square(0.001, 0.001, 0.001))), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("PLOT_NOT_EDITABLE");
    }

    // ------------------------------------------------------------------
    // IE-10 — moving a plot to another tier
    // ------------------------------------------------------------------

    @Test
    void movingToAnotherLandTierChangesPriceAndSizeAndIsAudited() {
        UUID large = landTier("500.00", "35000000", Currency.NGN, "Large 500");

        ResponseEntity<PlotTierChangeDto> moved = moveTier(plotId, large, null);

        assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.OK);
        PlotTierChangeDto change = moved.getBody();
        assertThat(change.previousTierId()).isEqualTo(tierId);
        assertThat(change.previousPrice()).isEqualByComparingTo(TIER_PRICE);
        assertThat(change.price()).isEqualByComparingTo("35000000");
        assertThat(change.previousNominalSizeSqm()).isEqualByComparingTo(TIER_SIZE);
        assertThat(change.nominalSizeSqm()).as("the size on the deed follows a land tier").isEqualByComparingTo("500");
        assertThat(plotDetail(plotId).price()).isEqualByComparingTo("35000000");
        assertThat(queryString("SELECT detail FROM audit_log_entries WHERE action = 'estate.plot_tier_changed' "
                + "AND target_id = '" + plotId + "'"))
                .contains("'Standard 250' -> 'Large 500'")
                .contains("size 250.00 -> 500.00");
    }

    @Test
    void aCornerPlotKeepsItsPremiumOnTheNewTier() {
        execute("UPDATE plots SET is_corner = true WHERE id = '" + plotId + "'");
        UUID large = landTier("500.00", "30000000", Currency.NGN, "Large 500");

        PlotTierChangeDto change = moveTier(plotId, large, null).getBody();

        // The fixture estate's corner premium is 10%.
        assertThat(change.previousPrice()).isEqualByComparingTo("22000000");
        assertThat(change.price()).isEqualByComparingTo("33000000");
    }

    /**
     * Between two unit tiers a built plot keeps its land area unless an
     * override is given — never left sizeless by a move.
     */
    @Test
    void aBuiltPlotMovingBetweenUnitTiersKeepsItsSizeUnlessOverridden() {
        UUID terrace = unitTier("3-bed terrace", "85000000");
        UUID duplex = unitTier("4-bed duplex", "120000000");
        UUID first = addPlotOnTier("T01", terrace, new BigDecimal("300"));
        UUID second = addPlotOnTier("T02", terrace, new BigDecimal("300"));

        PlotTierChangeDto kept = moveTier(first, duplex, null).getBody();
        assertThat(kept.nominalSizeSqm()).isEqualByComparingTo("300");
        assertThat(kept.price()).isEqualByComparingTo("120000000");
        assertThat(moveTier(second, duplex, new BigDecimal("180")).getBody().nominalSizeSqm())
                .isEqualByComparingTo("180");
    }

    /** Found in the walkthrough: a land plot must not end up priced as a built unit. */
    @Test
    void aLandPlotCannotMoveToAUnitTier() {
        UUID terrace = unitTier("3-bed terrace", "85000000");

        ResponseEntity<String> refused = moveTierRaw(plotId, terrace, null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("PROPERTY_TYPE_MISMATCH");
        assertThat(queryString("SELECT price_tier_id FROM plots WHERE id = '" + plotId + "'"))
                .isEqualTo(tierId.toString());
    }

    /** At creation the property type follows the tier when left out, and a contradiction is refused. */
    @Test
    void aPlotsPropertyTypeFollowsItsTier() {
        UUID terrace = unitTier("3-bed terrace", "85000000");
        UUID built = addPlotOnTier("T09", terrace, null);
        assertThat(queryString("SELECT property_type FROM plots WHERE id = '" + built + "'")).isEqualTo("BUILT");
        assertThat(queryString("SELECT property_type FROM plots WHERE id = '" + plotId + "'")).isEqualTo("LAND");

        ResponseEntity<String> refused = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/plots", HttpMethod.POST,
                entity(seller.token(), new CreatePlotsRequest(List.of(
                        new CreatePlotRequest("T10", null, terrace, false, "available-dev",
                                null, "land", null, null, null, null)))),
                String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("PROPERTY_TYPE_MISMATCH");
    }

    @Test
    void aLandTierRefusesASizeOverride() {
        UUID large = landTier("500.00", "35000000", Currency.NGN, "Large 500");

        ResponseEntity<String> refused = moveTierRaw(plotId, large, new BigDecimal("180"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(nominalSize(plotId)).as("refused, not partly applied").isEqualByComparingTo(TIER_SIZE);
    }

    @Test
    void aTierInAnotherCurrencyIsRefused() {
        UUID dollars = landTier("500.00", "40000", Currency.USD, "Diaspora 500");

        ResponseEntity<String> refused = moveTierRaw(plotId, dollars, null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("TIER_CURRENCY_MISMATCH");
    }

    @Test
    void aSoldPlotCannotMove() {
        execute("UPDATE plots SET status = 'SOLD' WHERE id = '" + plotId + "'");
        UUID large = landTier("500.00", "35000000", Currency.NGN, "Large 500");

        ResponseEntity<String> refused = moveTierRaw(plotId, large, null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("PLOT_NOT_EDITABLE");
        assertThat(nominalSize(plotId)).isEqualByComparingTo(TIER_SIZE);
    }

    @Test
    void aTierFromAnotherEstateIsNotFound() {
        Tenant other = verifiedTenant();
        UUID foreignTier = publishedListing(other).tierId();

        assertThat(moveTierRaw(plotId, foreignTier, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void movingToTheSameTierWritesNothing() {
        assertThat(moveTier(plotId, tierId, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'estate.plot_tier_changed' "
                + "AND target_id = '" + plotId + "'")).isEqualTo("0");
    }

    @Test
    void aViewOnlyUserCanNeitherCorrectNorMoveAPlot() {
        String salesManager = staffToken(seller.id(), "sales_manager");
        UUID large = landTier("500.00", "35000000", Currency.NGN, "Large 500");

        assertThat(restTemplate.exchange("/api/portal/estates/" + estateId + "/plots/" + plotId + "/boundary",
                HttpMethod.PUT, entity(salesManager, boundaryBody(square(0.001, 0.001, 0.001))), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange("/api/portal/estates/" + estateId + "/plots/" + plotId + "/tier",
                HttpMethod.PUT, entity(salesManager, new MovePlotTierRequest(large, null)), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------
    // FU-1..FU-3 — plot import from a GeoJSON file
    // ------------------------------------------------------------------

    /** FU-3: the template is a working file — it previews clean and imports as it stands. */
    @Test
    void theTemplateIsAWorkingFileForThisEstate() {
        ResponseEntity<String> template = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/plots/import/template", HttpMethod.GET,
                new HttpEntity<>(bearer(seller.token())), String.class);

        assertThat(template.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(template.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains("-plots-template.geojson");
        assertThat(template.getBody()).contains("FeatureCollection").contains("Standard 250").contains("EPSG:4326");

        PlotImportReportDto preview = upload("preview", template.getBody(), seller.token(), PlotImportReportDto.class).getBody();
        assertThat(preview.canImport()).as(String.valueOf(preview.errors())).isTrue();
        assertThat(preview.featureCount()).isEqualTo(2);

        PlotImportReportDto imported = upload("", template.getBody(), seller.token(), PlotImportReportDto.class).getBody();
        assertThat(imported.imported()).isTrue();
        assertThat(imported.createdCount()).isEqualTo(2);
    }

    /** FU-2: one report for the whole file — every problem at once — and nothing written. */
    @Test
    void thePreviewReportsEveryProblemAtOnceAndWritesNothing() {
        String file = collection(
                feature("10", "A", "Standard 250", null, square(0.001, 0.001, 0.001)),
                feature("11", "A", "Gold", null, square(0.003, 0.001, 0.001)),
                feature("10", "A", "Standard 250", null, square(0.005, 0.001, 0.001)),
                feature("12", "A", "Standard 250", null, square(0.02, 0.0, 0.001)),
                feature("13", "A", "Standard 250", null, swapped(square(0.001, 0.003, 0.001))),
                feature("14", "A", "Standard 250", null, utm()),
                feature(null, "A", "Standard 250", null, square(0.001, 0.005, 0.001)),
                feature("001", "A", "Standard 250", null, square(0.003, 0.005, 0.001)),
                feature("15", "A", "Standard 250", null, bowTie(0.005, 0.005, 0.001)));
        long plotsBefore = plotCount();

        PlotImportReportDto report = upload("preview", file, seller.token(), PlotImportReportDto.class).getBody();

        assertThat(report.featureCount()).isEqualTo(9);
        assertThat(report.importableCount()).isEqualTo(1);
        assertThat(report.canImport()).isFalse();
        assertThat(report.errors()).extracting(PlotImportIssueDto::code).contains(
                "UNKNOWN_TIER", "DUPLICATE_PLOT_NUMBER", "OUTSIDE_ESTATE", "INVALID_GEOMETRY",
                "MISSING_PLOT_NUMBER", "PLOT_NUMBER_EXISTS");
        // Transposed near Abuja the coordinates stay inside Nigeria, so the
        // national check can't see it (AGENTS.md) — the estate boundary can.
        assertThat(report.errors()).filteredOn(e -> e.feature() == 5).extracting(PlotImportIssueDto::code)
                .containsExactly("OUTSIDE_ESTATE");
        assertThat(report.errors()).filteredOn(e -> e.feature() == 6).extracting(PlotImportIssueDto::message)
                .anySatisfy(m -> assertThat(m).contains("metres").contains("EPSG:4326"));
        assertThat(report.errors()).filteredOn(e -> e.feature() == 9).extracting(PlotImportIssueDto::message)
                .anySatisfy(m -> assertThat(m).startsWith("The boundary is not a valid shape"));
        assertThat(plotCount()).as("a preview writes nothing").isEqualTo(plotsBefore);
    }

    /** All or nothing: one bad feature and not even the good ones — or the new block — are created. */
    @Test
    void anImportWithAnyErrorCreatesNothingAndReturnsTheReport() {
        String file = collection(
                feature("20", "Z", "Standard 250", null, square(0.001, 0.001, 0.001)),
                feature("21", "Z", "Gold", null, square(0.003, 0.001, 0.001)));
        long plotsBefore = plotCount();

        ResponseEntity<PlotImportReportDto> refused = upload("", file, seller.token(), PlotImportReportDto.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().imported()).isFalse();
        assertThat(refused.getBody().errors()).extracting(PlotImportIssueDto::code).containsExactly("UNKNOWN_TIER");
        assertThat(plotCount()).isEqualTo(plotsBefore);
        assertThat(queryString("SELECT count(*) FROM blocks WHERE estate_id = '" + estateId + "' AND name = 'Z'"))
                .as("not even the block").isEqualTo("0");
    }

    /** FU-1: tiers matched by label or by size, new blocks created, sizes from the tier, areas computed. */
    @Test
    void aCleanFileImportsEveryPlotWithTiersBlocksAndAreas() {
        landTier("500.00", "35000000", Currency.NGN, "Large 500");
        String file = collection(
                feature("30", "A", "standard 250", null, square(0.001, 0.001, 0.001)),
                feature("31", "B", 500, true, square(0.003, 0.001, 0.001)),
                feature("32", "B", "Large 500", "yes", square(0.005, 0.001, 0.001)));

        ResponseEntity<PlotImportReportDto> response = upload("", file, seller.token(), PlotImportReportDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PlotImportReportDto report = response.getBody();
        assertThat(report.createdCount()).isEqualTo(3);
        assertThat(report.blocksToCreate()).containsExactly("B");
        assertThat(report.plotsPerTier()).isEqualTo(Map.of("Standard 250", 1, "Large 500", 2));
        assertThat(queryString("SELECT nominal_size_sqm || '/' || is_corner || '/' || (actual_area_sqm > 10000) "
                + "FROM plots WHERE estate_id = '" + estateId + "' AND plot_number = '31'"))
                .as("size from the tier, corner from the file, surveyed area computed")
                .isEqualTo("500.00/true/true");
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'estate.plots_imported' "
                + "AND target_id = '" + estateId + "'")).isEqualTo("1");
    }

    /** Overlaps warn and are recorded for review; they never stop an import. */
    @Test
    void overlappingPlotsInTheFileAreWarningsNotErrors() {
        String file = collection(
                feature("40", "A", "Standard 250", null, square(0.001, 0.001, 0.002)),
                feature("41", "A", "Standard 250", null, square(0.002, 0.002, 0.002)));

        PlotImportReportDto preview = upload("preview", file, seller.token(), PlotImportReportDto.class).getBody();
        assertThat(preview.canImport()).isTrue();
        assertThat(preview.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo("PLOT_OVERLAP");
            assertThat(w.plotNumber()).as("named in the field, not only the message").isEqualTo("40");
        });

        PlotImportReportDto imported = upload("", file, seller.token(), PlotImportReportDto.class).getBody();
        assertThat(imported.plotOverlapsInEstate()).isEqualTo(1);
    }

    /** What QGIS often exports for a single shape. Two separate parts is not a plot. */
    @Test
    void aSinglePartMultiPolygonIsAcceptedAndATwoPartOneIsNot() {
        String file = collection(
                multiPolygonFeature("50", List.of(square(0.001, 0.001, 0.001))),
                multiPolygonFeature("51", List.of(square(0.003, 0.001, 0.001), square(0.005, 0.001, 0.001))));

        PlotImportReportDto report = upload("preview", file, seller.token(), PlotImportReportDto.class).getBody();

        assertThat(report.importableCount()).isEqualTo(1);
        assertThat(report.errors()).singleElement().satisfies(e -> {
            assertThat(e.feature()).isEqualTo(2);
            assertThat(e.message()).contains("2 separate parts");
        });
    }

    @Test
    void aFileDeclaringAProjectedCoordinateSystemIsRefusedWithTheReason() {
        String file = """
                {"type":"FeatureCollection",
                 "crs":{"type":"name","properties":{"name":"urn:ogc:def:crs:EPSG::26332"}},
                 "features":[%s]}""".formatted(feature("60", "A", "Standard 250", null, square(0.001, 0.001, 0.001)));

        PlotImportReportDto report = upload("preview", file, seller.token(), PlotImportReportDto.class).getBody();

        assertThat(report.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo("PROJECTED_COORDINATES");
            assertThat(e.message()).contains("Minna").contains("EPSG:4326");
        });
    }

    @Test
    void aViewOnlyUserCanDownloadTheTemplateButNotImport() {
        String salesManager = staffToken(seller.id(), "sales_manager");
        String file = collection(feature("70", "A", "Standard 250", null, square(0.001, 0.001, 0.001)));

        assertThat(restTemplate.exchange("/api/portal/estates/" + estateId + "/plots/import/template",
                HttpMethod.GET, new HttpEntity<>(bearer(salesManager)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(upload("", file, salesManager, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload("preview", file, salesManager, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------
    // IE-7 — withholding a plot
    // ------------------------------------------------------------------

    /** Off the market, unreservable, and back as exactly the variant it was. */
    @Test
    void aWithheldPlotCannotBeReservedAndReturnsAsTheVariantItWas() {
        UUID investment = addPlot("002", "available-inv");
        assertThat(bulkStatus(List.of(plotId, investment), "withheld", null).changed())
                .containsExactlyInAnyOrder(plotId, investment);
        assertThat(plotStatus(plotId)).isEqualTo("WITHHELD");

        ResponseEntity<String> refused = restTemplate.exchange("/api/reservations", HttpMethod.POST,
                entity(verifiedBuyer().token(), new CreateReservationRequest(plotId)), String.class);
        assertThat(refused.getStatusCode()).as("withheld means no buyer can take it").isEqualTo(HttpStatus.CONFLICT);

        assertThat(bulkStatus(List.of(plotId, investment), "available", null).changed()).hasSize(2);
        assertThat(plotStatus(plotId)).isEqualTo("AVAILABLE_DEV");
        assertThat(plotStatus(investment)).as("never flattened to development").isEqualTo("AVAILABLE_INV");
    }

    /** A tier resize skips withheld plots; returning one brings it back at the tier's current size. */
    @Test
    void aWithheldPlotReturnsAtTheTiersCurrentSize() {
        putPlotStatus(plotId, "withheld");
        putTier("{\"sizeSqm\":300}");
        assertThat(nominalSize(plotId)).isEqualByComparingTo(TIER_SIZE);

        putPlotStatus(plotId, "available");

        assertThat(nominalSize(plotId)).isEqualByComparingTo("300");
    }

    @Test
    void reservedAndSoldPlotsCannotBeWithheld() {
        UUID sold = addPlot("002", "available-dev");
        execute("UPDATE plots SET status = 'SOLD' WHERE id = '" + sold + "'");
        reserve(verifiedBuyer(), plotId);

        Map<UUID, String> labels = Map.of(plotId, "Block A, Plot 001 is reserved", sold, "Plot 002 is sold");
        for (UUID plot : List.of(plotId, sold)) {
            ResponseEntity<String> refused = restTemplate.exchange(
                    "/api/portal/estates/" + estateId + "/plots/" + plot + "/status", HttpMethod.PUT,
                    entity(seller.token(), new ChangePlotStatusRequest("withheld", null)), String.class);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getBody()).contains("PLOT_NOT_EDITABLE").contains(labels.get(plot));
        }
    }

    /** Reserved and sold are reached only through checkout. */
    @Test
    void aDeveloperCannotSetReservedOrSold() {
        for (String status : List.of("reserved", "sold")) {
            assertThat(restTemplate.exchange(
                    "/api/portal/estates/" + estateId + "/plots/" + plotId + "/status", HttpMethod.PUT,
                    entity(seller.token(), new ChangePlotStatusRequest(status, null)), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(plotStatus(plotId)).isEqualTo("AVAILABLE_DEV");
    }

    /**
     * The compare-and-swap: a developer withholding a plot at the instant a
     * buyer reserves it. Exactly one wins, and the loser is told — the
     * buyer's hold is never overwritten.
     */
    @Test
    void withholdingAndReservingTheSamePlotAtOnceNeverOverwriteEachOther() throws Exception {
        Buyer buyer = verifiedBuyer();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var reservation = pool.submit(() -> {
                start.await();
                return restTemplate.exchange("/api/reservations", HttpMethod.POST,
                        entity(buyer.token(), new CreateReservationRequest(plotId)), String.class);
            });
            var withhold = pool.submit(() -> {
                start.await();
                return bulkStatus(List.of(plotId), "withheld", null);
            });
            start.countDown();

            boolean reserved = reservation.get().getStatusCode() == HttpStatus.CREATED;
            boolean withheld = !withhold.get().changed().isEmpty();
            assertThat(reserved ^ withheld).as("exactly one wins").isTrue();
            assertThat(plotStatus(plotId)).isEqualTo(reserved ? "RESERVED" : "WITHHELD");
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // IE-8 — bulk status changes
    // ------------------------------------------------------------------

    /** Skip and report: one reserved plot never blocks the rest. */
    @Test
    void aBulkChangeSkipsWhatItCannotChangeAndSaysWhy() {
        UUID second = addPlot("002", "available-dev");
        UUID reserved = addPlot("003", "available-dev");
        UUID sold = addPlot("004", "available-dev");
        reserve(verifiedBuyer(), reserved);
        execute("UPDATE plots SET status = 'SOLD' WHERE id = '" + sold + "'");
        UUID foreign = publishedListing(verifiedTenant()).plotId();
        UUID unknown = UUID.randomUUID();

        PlotStatusChangeDto result = bulkStatus(List.of(plotId, second, reserved, sold, foreign, unknown),
                "withheld", "Phase 2 survey dispute");

        assertThat(result.changed()).containsExactlyInAnyOrder(plotId, second);
        assertThat(result.skipped()).extracting(PlotStatusChangeDto.Skipped::code)
                .containsExactlyInAnyOrder("RESERVED", "SOLD", "NOT_FOUND", "NOT_FOUND");
        assertThat(queryString("SELECT detail FROM audit_log_entries WHERE action = 'estate.plot_status_changed' "
                + "AND target_id = '" + estateId + "'"))
                .contains("2 plot(s)").contains("4 skipped").contains("Phase 2 survey dispute");
        assertThat(plotStatus(foreign)).as("another company's plot is untouched").isEqualTo("AVAILABLE_DEV");
    }

    @Test
    void aDryRunChangesNothingAndWritesNothing() {
        PlotStatusChangeDto result = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/plots/status", HttpMethod.POST,
                entity(seller.token(), new BulkPlotStatusRequest(List.of(plotId), "withheld", null, true)),
                PlotStatusChangeDto.class).getBody();

        assertThat(result.dryRun()).isTrue();
        assertThat(result.changed()).containsExactly(plotId);
        assertThat(plotStatus(plotId)).isEqualTo("AVAILABLE_DEV");
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'estate.plot_status_changed'"
                + " AND target_id = '" + estateId + "'")).isEqualTo("0");
    }

    // ------------------------------------------------------------------
    // IE-5 — retiring a tier
    // ------------------------------------------------------------------

    /** No new plots join — created, imported or moved — but existing plots stay sellable. */
    @Test
    void aRetiredTierTakesNoNewPlotsButItsPlotsStaySellable() {
        UUID large = landTier("500.00", "35000000", Currency.NGN, "Large 500");
        UUID onLarge = addPlotOnTier("L01", large, null);
        assertThat(restTemplate.exchange("/api/portal/estates/" + estateId + "/price-tiers/" + large + "/retire",
                HttpMethod.POST, entity(seller.token(), null), PriceTierDto.class).getBody().retiredAt()).isNotNull();

        ResponseEntity<String> created = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/plots", HttpMethod.POST,
                entity(seller.token(), new CreatePlotsRequest(List.of(new CreatePlotRequest(
                        "L02", null, large, false, "available-dev", null, null, null, null, null, null)))),
                String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(created.getBody()).contains("TIER_RETIRED");
        assertThat(moveTierRaw(plotId, large, null).getBody()).contains("TIER_RETIRED");
        assertThat(upload("preview", collection(feature("L03", "A", "Large 500", null, square(0.001, 0.001, 0.001))),
                seller.token(), PlotImportReportDto.class).getBody().errors())
                .extracting(PlotImportIssueDto::code).containsExactly("TIER_RETIRED");

        reserve(verifiedBuyer(), onLarge);
        assertThat(plotStatus(onLarge)).as("existing plots stay on sale at the tier's price").isEqualTo("RESERVED");

        restTemplate.exchange("/api/portal/estates/" + estateId + "/price-tiers/" + large + "/reinstate",
                HttpMethod.POST, entity(seller.token(), null), PriceTierDto.class);
        addPlotOnTier("L02", large, null);
    }

    // ------------------------------------------------------------------
    // IE-11 — withdrawing a plot
    // ------------------------------------------------------------------

    @Test
    void anUntouchedPlotCanBeWithdrawnAndItsNumberReused() {
        UUID mistake = addPlot("002", "available-dev");

        assertThat(withdraw(mistake).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(restTemplate.exchange("/api/portal/estates/" + estateId + "/plots/" + mistake, HttpMethod.GET,
                new HttpEntity<>(bearer(seller.token())), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(queryString("SELECT deleted FROM plots WHERE id = '" + mistake + "'"))
                .as("soft-deleted, never removed").isEqualTo("t");
        assertThat(addPlot("002", "available-dev")).as("the number is free again").isNotEqualTo(mistake);
    }

    /** An expired or cancelled hold is still history. */
    @Test
    void aPlotThatWasEverReservedCannotBeWithdrawn() {
        Buyer buyer = verifiedBuyer();
        ReservationDto reservation = reserve(buyer, plotId);
        restTemplate.exchange("/api/reservations/" + reservation.id(), HttpMethod.DELETE,
                new HttpEntity<>(bearer(buyer.token())), String.class);
        assertThat(plotStatus(plotId)).isEqualTo("AVAILABLE_DEV");

        ResponseEntity<String> refused = withdraw(plotId);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("PLOT_HAS_HISTORY");
    }

    /** Conflicts are platform-scope only; the definer function is what lets this check see them. */
    @Test
    void aPlotThatWasEverInAConflictCannotBeWithdrawn() {
        UUID second = addPlot("002", "available-dev");
        putPlotBoundary(plotId, square(0.001, 0.001, 0.002));
        putPlotBoundary(second, square(0.002, 0.002, 0.002));
        putPlotBoundary(second, square(0.005, 0.005, 0.002));
        assertThat(conflictCount("AUTO_RESOLVED")).as("resolved, but still on record").isEqualTo(1);

        ResponseEntity<String> refused = withdraw(second);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("PLOT_HAS_HISTORY");
    }

    @Test
    void aSoldPlotCannotBeWithdrawn() {
        execute("UPDATE plots SET status = 'SOLD' WHERE id = '" + plotId + "'");

        assertThat(withdraw(plotId).getBody()).contains("PLOT_NOT_EDITABLE");
    }

    // ------------------------------------------------------------------
    // Updating an estate's details
    // ------------------------------------------------------------------

    @Test
    void plainFieldsChangeAndTheAuditRecordsTheOldValues() {
        ResponseEntity<EstateDto> updated = putEstate("""
                {"name":"Renamed Gardens","description":"Phase 2","address":"  ","amenities":["Borehole","Paved roads"]}
                """, EstateDto.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        EstateDto estate = updated.getBody();
        assertThat(estate.name()).isEqualTo("Renamed Gardens");
        assertThat(estate.slug()).isEqualTo("renamed-gardens");
        assertThat(estate.description()).isEqualTo("Phase 2");
        assertThat(estate.address()).as("blank clears").isNull();
        assertThat(estate.amenities()).containsExactlyInAnyOrder("Borehole", "Paved roads");
        assertThat(estate.state()).as("left out, unchanged — and stored under its standard name (SB-1)")
                .isEqualTo("Federal Capital Territory (Abuja)");
        assertThat(queryString("SELECT detail FROM audit_log_entries WHERE action = 'estate.updated' "
                + "AND target_id = '" + estateId + "'"))
                .contains("name 'Edit Gardens").contains("-> 'Renamed Gardens'")
                .contains("amenities [Perimeter fence] -> [Borehole, Paved roads]");

        // Re-adding a removed amenity works: removal is a hard delete.
        assertThat(putEstate("{\"amenities\":[\"Perimeter fence\"]}", EstateDto.class).getBody().amenities())
                .containsExactly("Perimeter fence");
    }

    /** Every corner plot reprices at once; a buyer who already reserved keeps their price. */
    @Test
    void aCornerPremiumChangeRepricesCornerPlotsButNotAReservation() {
        UUID corner = addPlot("002", "available-dev");
        execute("UPDATE plots SET is_corner = true WHERE id IN ('" + plotId + "', '" + corner + "')");
        ReservationDto held = reserve(verifiedBuyer(), plotId);
        assertThat(new BigDecimal(queryString("SELECT total_price FROM reservations WHERE id = '" + held.id() + "'")))
                .isEqualByComparingTo("22000000");

        putEstate("{\"cornerPremiumPct\":20}", EstateDto.class);

        assertThat(plotDetail(corner).price()).isEqualByComparingTo("24000000");
        assertThat(new BigDecimal(queryString("SELECT total_price FROM reservations WHERE id = '" + held.id() + "'")))
                .as("captured at reservation, never recomputed").isEqualByComparingTo("22000000");
        assertThat(queryString("SELECT detail FROM audit_log_entries WHERE action = 'estate.updated' "
                + "AND target_id = '" + estateId + "'")).contains("corner premium 10.00% -> 20%");
    }

    /** Refused, never silently ignored: each has its own route and its own consequences. */
    @Test
    void theBoundaryPublicationAndBranchAreRefusedNotIgnored() {
        assertThat(putEstate("{\"footprint\":{\"type\":\"Polygon\",\"coordinates\":[]}}", String.class).getBody())
                .contains("BOUNDARY_NOT_EDITABLE_HERE");
        assertThat(putEstate("{\"published\":false}", String.class).getBody())
                .contains("PUBLICATION_NOT_EDITABLE_HERE");
        assertThat(putEstate("{\"branchId\":\"" + UUID.randomUUID() + "\"}", String.class).getBody())
                .contains("BRANCH_NOT_EDITABLE");
        assertThat(queryString("SELECT published FROM estates WHERE id = '" + estateId + "'")).isEqualTo("t");
    }

    @Test
    void aNameAnotherOfYourEstatesHasIsAConflict() {
        UUID branchId = UUID.fromString(queryString("SELECT branch_id FROM estates WHERE id = '" + estateId + "'"));
        assertThat(restTemplate.exchange("/api/portal/estates", HttpMethod.POST,
                entity(seller.token(), new CreateEstateRequest("Second Gardens", null, null, null, "FCT", null,
                        null, null, List.of(), branchId, null)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> clash = putEstate("{\"name\":\"Second Gardens\"}", String.class);

        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(queryString("SELECT name FROM estates WHERE id = '" + estateId + "'")).startsWith("Edit Gardens");
    }

    @Test
    void aBlankNameOrStateIsRefusedAndANoOpWritesNothing() {
        assertThat(putEstate("{\"name\":\" \"}", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(putEstate("{\"state\":\"\"}", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(putEstate("{\"state\":\"Abuja\",\"amenities\":[\"Perimeter fence\"]}", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'estate.updated' "
                + "AND target_id = '" + estateId + "'")).isEqualTo("0");
    }

    @Test
    void aViewOnlyUserCannotUpdateAnEstate() {
        String salesManager = staffToken(seller.id(), "sales_manager");

        assertThat(restTemplate.exchange("/api/portal/estates/" + estateId, HttpMethod.PUT,
                entity(salesManager, "{\"name\":\"Nope\"}"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
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

    private <T> ResponseEntity<T> putEstate(String json, Class<T> type) {
        return restTemplate.exchange("/api/portal/estates/" + estateId, HttpMethod.PUT,
                entity(seller.token(), json), type);
    }

    private PlotStatusChangeDto bulkStatus(List<UUID> plots, String status, String reason) {
        ResponseEntity<PlotStatusChangeDto> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/plots/status", HttpMethod.POST,
                entity(seller.token(), new BulkPlotStatusRequest(plots, status, reason, null)), PlotStatusChangeDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void putPlotStatus(UUID plot, String status) {
        assertThat(restTemplate.exchange("/api/portal/estates/" + estateId + "/plots/" + plot + "/status",
                HttpMethod.PUT, entity(seller.token(), new ChangePlotStatusRequest(status, null)), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> withdraw(UUID plot) {
        return restTemplate.exchange("/api/portal/estates/" + estateId + "/plots/" + plot, HttpMethod.DELETE,
                new HttpEntity<>(bearer(seller.token())), String.class);
    }

    private String plotStatus(UUID plot) {
        return queryString("SELECT status FROM plots WHERE id = '" + plot + "'");
    }

    private <T> ResponseEntity<T> upload(String action, String geojson, String token, Class<T> type) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(geojson.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "plots.geojson";
            }
        });
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        String path = "/api/portal/estates/" + estateId + "/plots/import" + (action.isEmpty() ? "" : "/" + action);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private static String collection(String... features) {
        return "{\"type\":\"FeatureCollection\",\"features\":[" + String.join(",", features) + "]}";
    }

    private static String feature(String plotNumber, String block, Object tier, Object corner,
                                  List<List<BigDecimal>> ring) {
        return "{\"type\":\"Feature\",\"properties\":{"
                + (plotNumber == null ? "" : "\"plot_number\":\"" + plotNumber + "\",")
                + "\"block\":\"" + block + "\","
                + "\"tier\":" + (tier instanceof String ? "\"" + tier + "\"" : tier)
                + (corner == null ? "" : ",\"corner\":" + (corner instanceof String ? "\"" + corner + "\"" : corner))
                + "},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[" + ringJson(ring) + "]}}";
    }

    private static String multiPolygonFeature(String plotNumber, List<List<List<BigDecimal>>> parts) {
        return "{\"type\":\"Feature\",\"properties\":{\"plot_number\":\"" + plotNumber
                + "\",\"block\":\"A\",\"tier\":\"Standard 250\"},\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":["
                + parts.stream().map(r -> "[" + ringJson(r) + "]").collect(java.util.stream.Collectors.joining(","))
                + "]}}";
    }

    private static String ringJson(List<List<BigDecimal>> ring) {
        return "[" + ring.stream().map(p -> "[" + p.get(0).toPlainString() + "," + p.get(1).toPlainString() + "]")
                .collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private static List<List<BigDecimal>> swapped(List<List<BigDecimal>> ring) {
        return ring.stream().map(p -> List.of(p.get(1), p.get(0))).toList();
    }

    /** A plot in UTM zone 32N metres, as Nigerian survey software exports it. */
    private static List<List<BigDecimal>> utm() {
        return Arrays.stream(new double[][] {{326000, 1001000}, {326020, 1001000}, {326020, 1001020},
                        {326000, 1001020}, {326000, 1001000}})
                .map(p -> List.of(BigDecimal.valueOf(p[0]), BigDecimal.valueOf(p[1])))
                .toList();
    }

    /** Two corners in the wrong order: closed, but the edges cross. */
    private List<List<BigDecimal>> bowTie(double dLng, double dLat, double side) {
        List<List<BigDecimal>> ring = square(dLng, dLat, side);
        return List.of(ring.get(0), ring.get(2), ring.get(1), ring.get(3), ring.get(4));
    }

    private UUID unitTier(String label, String price) {
        return post(seller, "/api/portal/estates/" + estateId + "/price-tiers",
                new CreatePriceTierRequest("UNIT_TYPE", null, new BigDecimal(price), Currency.NGN, label),
                PriceTierDto.class).id();
    }

    private UUID addPlotOnTier(String number, UUID tier, BigDecimal sizeOverride) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/plots", HttpMethod.POST,
                entity(seller.token(), new CreatePlotsRequest(List.of(
                        new CreatePlotRequest(number, null, tier, false, "available-dev",
                                null, null, null, null, sizeOverride, null)))),
                String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(queryString(
                "SELECT id FROM plots WHERE estate_id = '" + estateId + "' AND plot_number = '" + number
                        + "' AND deleted = false"));
    }

    private long plotCount() {
        return Long.parseLong(queryString("SELECT count(*) FROM plots WHERE estate_id = '" + estateId + "'"));
    }

    private ResponseEntity<PlotBoundaryDto> putPlotBoundary(UUID plot, List<List<BigDecimal>> ring) {
        ResponseEntity<PlotBoundaryDto> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/plots/" + plot + "/boundary", HttpMethod.PUT,
                entity(seller.token(), boundaryBody(ring)), PlotBoundaryDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private static CorrectPlotBoundaryRequest boundaryBody(List<List<BigDecimal>> ring) {
        return new CorrectPlotBoundaryRequest(new GeoJsonPolygonDto("Polygon", List.of(ring)));
    }

    /** A square of {@code side} degrees, offset from the fixture estate's south-west corner. */
    private List<List<BigDecimal>> square(double dLng, double dLat, double side) {
        double lng = Double.parseDouble(queryString("SELECT ST_XMin(footprint) FROM estates WHERE id = '" + estateId + "'")) + dLng;
        double lat = Double.parseDouble(queryString("SELECT ST_YMin(footprint) FROM estates WHERE id = '" + estateId + "'")) + dLat;
        return Arrays.stream(new double[][] {
                        {lng, lat}, {lng + side, lat}, {lng + side, lat + side}, {lng, lat + side}, {lng, lat}})
                .map(position -> List.of(BigDecimal.valueOf(position[0]), BigDecimal.valueOf(position[1])))
                .toList();
    }

    private UUID landTier(String size, String price, Currency currency, String label) {
        return post(seller, "/api/portal/estates/" + estateId + "/price-tiers",
                new CreatePriceTierRequest("LAND_SIZE", new BigDecimal(size), new BigDecimal(price), currency, label),
                PriceTierDto.class).id();
    }

    private ResponseEntity<PlotTierChangeDto> moveTier(UUID plot, UUID tier, BigDecimal override) {
        return restTemplate.exchange("/api/portal/estates/" + estateId + "/plots/" + plot + "/tier",
                HttpMethod.PUT, entity(seller.token(), new MovePlotTierRequest(tier, override)), PlotTierChangeDto.class);
    }

    private ResponseEntity<String> moveTierRaw(UUID plot, UUID tier, BigDecimal override) {
        return restTemplate.exchange("/api/portal/estates/" + estateId + "/plots/" + plot + "/tier",
                HttpMethod.PUT, entity(seller.token(), new MovePlotTierRequest(tier, override)), String.class);
    }

    private int conflictCount(String status) {
        return Integer.parseInt(queryString("SELECT count(*) FROM listing_conflicts WHERE estate_id = '" + estateId
                + "' AND conflict_type = 'PLOT_OVERLAP' AND status = '" + status + "'"));
    }

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
                "SELECT id FROM plots WHERE estate_id = '" + estateId + "' AND plot_number = '" + number
                        + "' AND deleted = false"));
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
