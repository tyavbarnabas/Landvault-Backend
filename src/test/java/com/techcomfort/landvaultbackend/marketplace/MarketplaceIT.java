package com.techcomfort.landvaultbackend.marketplace;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonFeatureCollectionDto;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonPolygonDto;
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
import com.techcomfort.landvaultbackend.inventory.dto.EstateDto;
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierDto;
import com.techcomfort.landvaultbackend.inventory.dto.PublicationDto;
import com.techcomfort.landvaultbackend.marketplace.dto.MarketplaceListingDto;
import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantStatusRequest;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public marketplace and the publication gate, against a database where
 * row-level security is <strong>actually enforced</strong>: the app connects
 * as the restricted role.
 * <p>
 * <strong>Do not convert this class to {@code @ServiceConnection}.</strong>
 * Its first test exists to prove an anonymous request can read published
 * estates at all. Under a superuser connection that would pass whether the
 * changeset-049 views existed or not; under the real role, without them, the
 * feed is silently empty.
 * <p>
 * Every public request here is sent with <strong>no Authorization
 * header</strong>, not an empty one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class MarketplaceIT {

    private static final String APP_ROLE = "landvault_app_marketplace_it";
    private static final String APP_ROLE_PASSWORD = "marketplace-it-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple";

    /** Each test method gets its own 0.1° longitude band, so estates never overlap across tests. */
    private static final AtomicInteger AREA_BAND = new AtomicInteger();
    private final BigDecimal bandLng = new BigDecimal("7.000")
            .add(new BigDecimal("0.100").multiply(BigDecimal.valueOf(AREA_BAND.getAndIncrement())));

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

    // --- the RLS escape ---

    /** Without changeset 049 this returns an empty page: silently, with a 200. */
    @Test
    void anAnonymousRequestSeesAPublishedEstate() {
        Listing listing = publishedListing("Anon Gardens");

        ResponseEntity<String> raw = anonymous("/api/marketplace/estates?limit=100");
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(feedIds()).contains(listing.estateId());

        assertThat(anonymous("/api/marketplace/estates/" + listing.estateId()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(anonymous("/api/marketplace/estates/" + listing.estateId() + "/geojson").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void theListingCarriesTiersSellerAndVerificationWithItsSource() {
        Listing listing = publishedListing("Detail Gardens");

        MarketplaceListingDto dto = restTemplate.getForObject(
                "/api/marketplace/estates/" + listing.estateId(), MarketplaceListingDto.class);

        assertThat(dto.seller().branchName()).isEqualTo("Head Office");
        assertThat(dto.seller().companyName()).isEqualTo(listing.tenant().name());
        assertThat(dto.verified()).isTrue();
        assertThat(dto.titleType()).isEqualTo("C of O");
        assertThat(dto.priceTiers()).singleElement().satisfies(tier -> {
            assertThat(tier.price()).isEqualByComparingTo("20000000");
            assertThat(tier.pricePerSqm()).isEqualByComparingTo("80000.00");
            assertThat(tier.plotsRemaining()).as("one of the three plots is available").isEqualTo(1);
            assertThat(tier.availability()).isEqualTo("low_stock");
        });
        assertThat(dto.fromPrice()).isEqualByComparingTo("20000000");
        assertThat(dto.fromPriceCurrency()).isEqualTo(Currency.NGN);
        assertThat(dto.verificationChecks()).singleElement().satisfies(check -> {
            assertThat(check.checkType()).isEqualTo("title_verification");
            assertThat(check.status()).isEqualTo("verified");
            assertThat(check.verificationSource())
                    .as("a registry lookup and a human reading a PDF must be distinguishable")
                    .isEqualTo("manual_review");
        });
        assertThat(dto.lastVerifiedDate()).isNotNull();
    }

    // --- the five conditions, each on the feed and on publish ---

    @Test
    void aTenantUnderReviewIsNotListedAndCannotPublish() {
        Listing listing = publishedListing("Review Gardens");
        execute("UPDATE organizations SET verification_state = 'UNDER_REVIEW' WHERE id = '" + listing.tenant().id() + "'");

        assertThat(feedIds()).doesNotContain(listing.estateId());
        assertRefused(listing, "PUBLICATION_VERIFICATION_PENDING", "verification");
    }

    @Test
    void aTenantWithoutTheEntitlementIsNotListedAndCannotPublish() {
        Listing listing = publishedListing("Entitlement Gardens");
        execute("UPDATE organizations SET marketplace_publishing = false WHERE id = '" + listing.tenant().id() + "'");

        assertThat(feedIds()).doesNotContain(listing.estateId());
        assertRefused(listing, "PUBLICATION_ENTITLEMENT_MISSING", "plan");
    }

    @Test
    void aSuspendedTenantIsNotListedAndCannotPublish() {
        Listing listing = publishedListing("Suspended Gardens");
        setTenantStatus(listing.tenant(), "suspended", "Non-payment.");

        assertThat(feedIds()).doesNotContain(listing.estateId());
        assertRefused(listing, "PUBLICATION_TENANT_NOT_ACTIVE", "isn't active");
    }

    /**
     * The corrected fourth condition. The story as first written said "not
     * SUSPENDED", which would have left an offboarded company's land for sale.
     */
    @Test
    void anOffboardedTenantIsNotListed() {
        Listing listing = publishedListing("Offboarded Gardens");
        setTenantStatus(listing.tenant(), "offboarded", "Left the platform.");

        assertThat(feedIds()).doesNotContain(listing.estateId());
        assertThat(anonymous("/api/marketplace/estates/" + listing.estateId()).getStatusCode())
                .as("same answer as an id that never existed")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** PB-6: a conflict raised after publication pulls the estate, with no separate mechanism. */
    @Test
    void aNewHighConflictPullsALiveEstateAndBlocksPublishing() {
        Listing listing = publishedListing("Contested Gardens");
        assertThat(feedIds()).contains(listing.estateId());

        Tenant rival = tenant("Rival Holdings");
        EstateDto rivalEstate = createEstate(rival, "Rival Heights", overlappingEstateRing());

        assertThat(feedIds()).as("pulled the moment the overlap is detected").doesNotContain(listing.estateId());

        ResponseEntity<String> refusal = publish(listing.tenant(), listing.estateId());
        assertThat(refusal.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refusal.getBody())
                .contains("PUBLICATION_CONFLICT_OUTSTANDING")
                .as("CD-11: never name the other party")
                .doesNotContain(rival.name())
                .doesNotContain(rival.id().toString())
                .doesNotContain(rivalEstate.id().toString())
                .doesNotContain("Rival");
    }

    @Test
    void aMediumConflictIsListedAndPublishingCarriesTheWarning() {
        Tenant company = verifiedTenant("Medium Holdings");
        EstateDto first = createEstate(company, "Medium North", estateRing());
        createEstate(company, "Medium South", overlappingEstateRing());

        ResponseEntity<PublicationDto> published = restTemplate.exchange(
                "/api/portal/estates/" + first.id() + "/publish", HttpMethod.POST,
                entity(company.token(), null), PublicationDto.class);

        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(published.getBody().warningConflictCount()).isEqualTo(1);
        assertThat(published.getBody().warning()).isNotBlank();
        assertThat(feedIds()).contains(first.id());
    }

    // --- intent versus eligibility (PB-5) ---

    @Test
    void suspensionHidesListingsWithoutUnpublishingAndReinstatementRestoresThem() {
        Listing listing = publishedListing("Reinstated Gardens");

        setTenantStatus(listing.tenant(), "suspended", "Non-payment.");
        assertThat(feedIds()).doesNotContain(listing.estateId());
        assertThat(publishedFlag(listing.estateId()))
                .as("the developer's intent is untouched by the tenant's state")
                .isTrue();

        setTenantStatus(listing.tenant(), "active", null);
        assertThat(feedIds()).as("back without republishing").contains(listing.estateId());
    }

    @Test
    void unpublishingPullsOneListingAndLeavesTheEstate() {
        Listing listing = publishedListing("Pulled Gardens");

        ResponseEntity<PublicationDto> result = restTemplate.exchange(
                "/api/portal/estates/" + listing.estateId() + "/unpublish", HttpMethod.POST,
                entity(listing.tenant().token(), null), PublicationDto.class);

        assertThat(result.getBody().published()).isFalse();
        assertThat(feedIds()).doesNotContain(listing.estateId());
        assertThat(anonymous("/api/marketplace/estates/" + listing.estateId()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.exchange("/api/portal/estates/" + listing.estateId(), HttpMethod.GET,
                entity(listing.tenant().token(), null), String.class).getStatusCode())
                .as("the estate itself is untouched")
                .isEqualTo(HttpStatus.OK);
    }

    // --- MP-3: nothing private leaks ---

    /** Asserted on the serialized JSON, so a field leaking by any route fails here. */
    @Test
    void thePublicPayloadContainsNothingTenantPrivate() {
        Listing listing = publishedListing("Private Gardens");

        for (String path : List.of(
                "/api/marketplace/estates?limit=100",
                "/api/marketplace/estates/" + listing.estateId(),
                "/api/marketplace/estates/" + listing.estateId() + "/geojson")) {
            String body = anonymous(path).getBody();
            assertThat(body).as(path)
                    .doesNotContain(listing.tenant().id().toString())
                    .doesNotContain(listing.tenant().branchId().toString())
                    .doesNotContain(listing.tenant().rcNumber())
                    .doesNotContain("org+")              // company email
                    .doesNotContain("12345678901")       // primary contact's NIN
                    .doesNotContain("LA-SECRET-TITLE")   // title number
                    .doesNotContain("SECRET-NOTE")       // verification-check notes
                    .doesNotContain("1 Hidden Close");   // estate address
        }
    }

    /** Collapsed inside the view: reserved and sold are both UNAVAILABLE, and neither word leaves the database. */
    @Test
    void publicPlotStatusIsOnlyAvailableOrUnavailable() {
        Listing listing = publishedListing("Status Gardens");

        GeoJsonFeatureCollectionDto map = restTemplate.getForObject(
                "/api/marketplace/estates/" + listing.estateId() + "/geojson", GeoJsonFeatureCollectionDto.class);
        List<Object> plotStatuses = map.features().stream()
                .filter(f -> "plot".equals(f.properties().get("kind")))
                .map(f -> f.properties().get("availability"))
                .toList();
        assertThat(plotStatuses)
                .as("all three plots still appear, so the estate reads as a real place")
                .containsExactlyInAnyOrder("AVAILABLE", "UNAVAILABLE", "UNAVAILABLE");

        String raw = anonymous("/api/marketplace/estates/" + listing.estateId() + "/geojson").getBody()
                .toLowerCase(Locale.ROOT);
        assertThat(raw).doesNotContain("reserved").doesNotContain("sold").doesNotContain("available-dev");
        assertThat(anonymous("/api/marketplace/estates/" + listing.estateId()).getBody().toLowerCase(Locale.ROOT))
                .doesNotContain("reserved");
    }

    // --- the views are read-only for the app role ---

    /**
     * Asks Postgres about the grants directly rather than attempting a write.
     * The first version tried an UPDATE and expected "permission denied", and
     * got "cannot update view" instead: Postgres checks updatability before
     * privileges, and these views join, so they aren't updatable. That test
     * would have passed even with the REVOKE missing, which is exactly the
     * dependency changeset 049 says the grant must not rely on. A future
     * single-table view WOULD be auto-updatable, and then only the grant
     * stands between the app role and a write that bypasses RLS.
     */
    @Test
    void theAppRoleHoldsSelectOnlyOnEveryMarketplaceView() {
        for (String view : List.of("marketplace_estate_eligibility", "marketplace_listings",
                "marketplace_price_tiers", "marketplace_plots", "marketplace_verification_checks",
                "marketplace_amenities")) {
            assertThat(hasPrivilege(view, "SELECT")).as(view + " SELECT").isTrue();
            for (String write : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
                assertThat(hasPrivilege(view, write)).as(view + " " + write).isFalse();
            }
        }
    }

    // --- fixtures ---

    private record Tenant(UUID id, String name, String rcNumber, UUID branchId, String token) {
    }

    private record Listing(Tenant tenant, UUID estateId) {
    }

    /**
     * A verified, entitled, active tenant's estate with a boundary, one land
     * tier, three plots (available, reserved, sold), a title, a verified
     * check, and planted private values the payload must never contain.
     */
    private Listing publishedListing(String name) {
        Tenant tenant = verifiedTenant(name + " Ltd");
        EstateDto estate = createEstate(tenant, name, estateRing());

        UUID tierId = post(tenant, "/api/portal/estates/" + estate.id() + "/price-tiers",
                new CreatePriceTierRequest("LAND_SIZE", new BigDecimal("250.00"),
                        new BigDecimal("20000000.0000"), Currency.NGN, "Standard 250"), PriceTierDto.class).id();
        UUID blockId = post(tenant, "/api/portal/estates/" + estate.id() + "/blocks",
                new CreateBlockRequest("A", "Block A"), BlockDto.class).id();
        ResponseEntity<String> plots = restTemplate.exchange(
                "/api/portal/estates/" + estate.id() + "/plots", HttpMethod.POST,
                entity(tenant.token(), new CreatePlotsRequest(List.of(
                        plot("1", blockId, tierId, "available-dev", plotRing(0)),
                        plot("2", blockId, tierId, "reserved", plotRing(1)),
                        plot("3", blockId, tierId, "sold", plotRing(2))))),
                String.class);
        assertThat(plots.getStatusCode()).as(plots.getBody()).isEqualTo(HttpStatus.CREATED);
        post(tenant, "/api/portal/estates/" + estate.id() + "/title",
                new CreateEstateTitleRequest("C of O", "LA-SECRET-TITLE", "2024-01-01", null, null), String.class);
        post(tenant, "/api/portal/estates/" + estate.id() + "/verification-checks",
                new CreateVerificationCheckRequest("title_verification", "verified", "manual_review",
                        "SECRET-NOTE internal only"), String.class);

        ResponseEntity<String> published = publish(tenant, estate.id());
        assertThat(published.getStatusCode()).as(published.getBody()).isEqualTo(HttpStatus.OK);
        return new Listing(tenant, estate.id());
    }

    private Tenant verifiedTenant(String name) {
        Tenant tenant = tenant(name);
        execute("UPDATE organizations SET verification_state = 'VERIFIED', marketplace_publishing = true, "
                + "status = 'ACTIVE' WHERE id = '" + tenant.id() + "'");
        return tenant;
    }

    private Tenant tenant(String name) {
        String rc = "RC-" + UUID.randomUUID();
        CreateTenantRequest request = new CreateTenantRequest(
                new CompanyIdentityDto(name, null, rc, "Limited Liability (Ltd)", "2020-01-01",
                        new AddressDto("1 Broad Street", "Abuja", "FCT"),
                        new AddressDto("1 Broad Street", "Abuja", "FCT"), List.of("FCT")),
                new PrimaryContactDto("A Director", "Chief Executive Officer",
                        "ed+" + UUID.randomUUID() + "@example.com", "+2348000000001", "NIN", "12345678901"),
                new CompanyPresenceDto("org+" + rc + "@example.com", "+2348000000002", null,
                        new SocialsDto(null, null, null, null)),
                "starter");
        ResponseEntity<TenantDetailDto> created = restTemplate.exchange(
                "/api/admin/tenants", HttpMethod.POST, entity(adminToken(), request), TenantDetailDto.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tenantId = created.getBody().id();

        UUID branchId = UUID.randomUUID();
        execute("INSERT INTO branches (id, created_at, deleted, organization_id, name) VALUES ('"
                + branchId + "', now(), false, '" + tenantId + "', 'Head Office')");

        String email = "ed+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest(
                "Test", "Director", email, "+2348000000000", PASSWORD, "NG", Currency.NGN);
        assertThat(restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        execute("UPDATE users SET tenant_id = '" + tenantId + "' WHERE lower(email) = lower('" + email + "')");
        execute("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('"
                + email + "'))");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id FROM users u, roles r "
                + "WHERE lower(u.email) = lower('" + email + "') AND r.code = 'executive_director'");

        return new Tenant(tenantId, name, rc, branchId, login(email));
    }

    private EstateDto createEstate(Tenant tenant, String name, List<List<BigDecimal>> ring) {
        CreateEstateRequest request = new CreateEstateRequest(
                name, "A quiet estate.", "Gwarinpa", "Abuja", "FCT", "1 Hidden Close", new BigDecimal("10.00"),
                "development", List.of("Perimeter fence"), tenant.branchId(),
                new GeoJsonPolygonDto("Polygon", List.of(ring)));
        return post(tenant, "/api/portal/estates", request, EstateDto.class);
    }

    private void setTenantStatus(Tenant tenant, String status, String reason) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/tenants/" + tenant.id() + "/status", HttpMethod.POST,
                entity(adminToken(), new TenantStatusRequest(status, reason)), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private void assertRefused(Listing listing, String code, String messageFragment) {
        ResponseEntity<String> refusal = publish(listing.tenant(), listing.estateId());
        assertThat(refusal.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refusal.getBody()).contains(code).contains(messageFragment);
    }

    private ResponseEntity<String> publish(Tenant tenant, UUID estateId) {
        return restTemplate.exchange("/api/portal/estates/" + estateId + "/publish", HttpMethod.POST,
                entity(tenant.token(), null), String.class);
    }

    private List<UUID> feedIds() {
        return restTemplate.exchange("/api/marketplace/estates?limit=100", HttpMethod.GET, HttpEntity.EMPTY,
                new ParameterizedTypeReference<PageResponse<MarketplaceListingDto>>() {
                }).getBody().items().stream().map(MarketplaceListingDto::id).toList();
    }

    /** No Authorization header at all, not an empty or invalid one. */
    private ResponseEntity<String> anonymous(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, HttpEntity.EMPTY, String.class);
    }

    private <T> T post(Tenant tenant, String path, Object body, Class<T> type) {
        ResponseEntity<T> response = restTemplate.exchange(path, HttpMethod.POST, entity(tenant.token(), body), type);
        assertThat(response.getStatusCode()).as("POST " + path).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static CreatePlotRequest plot(String number, UUID blockId, UUID tierId, String status,
                                          List<List<BigDecimal>> ring) {
        return new CreatePlotRequest(number, blockId, tierId, false, status, null, null, null, null, null,
                new GeoJsonPolygonDto("Polygon", List.of(ring)));
    }

    private List<List<BigDecimal>> estateRing() {
        return rect(lng("0.000"), "9.100", lng("0.009"), "9.109");
    }

    private List<List<BigDecimal>> overlappingEstateRing() {
        return rect(lng("0.005"), "9.105", lng("0.014"), "9.114");
    }

    /** Small, separate plots inside the estate, so plots never conflict with each other. */
    private List<List<BigDecimal>> plotRing(int index) {
        BigDecimal x = new BigDecimal("0.001").add(new BigDecimal("0.002").multiply(BigDecimal.valueOf(index)));
        return rect(lng(x.toPlainString()), "9.101", lng(x.add(new BigDecimal("0.001")).toPlainString()), "9.102");
    }

    private String lng(String delta) {
        return bandLng.add(new BigDecimal(delta)).toPlainString();
    }

    private static List<List<BigDecimal>> rect(String minLng, String minLat, String maxLng, String maxLat) {
        BigDecimal x1 = new BigDecimal(minLng);
        BigDecimal y1 = new BigDecimal(minLat);
        BigDecimal x2 = new BigDecimal(maxLng);
        BigDecimal y2 = new BigDecimal(maxLat);
        return List.of(List.of(x1, y1), List.of(x2, y1), List.of(x2, y2), List.of(x1, y2), List.of(x1, y1));
    }

    private String login(String email) {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().token();
    }

    private String adminToken() {
        return login(ADMIN_EMAIL);
    }

    private static HttpEntity<Object> entity(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static boolean publishedFlag(UUID estateId) {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT published FROM estates WHERE id = '" + estateId + "'")) {
            rs.next();
            return rs.getBoolean(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // Superuser: fixture setup the restricted role deliberately cannot do.
    private static void execute(String sql) {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean hasPrivilege(String view, String privilege) {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT has_table_privilege('" + APP_ROLE + "', '" + view + "', '"
                     + privilege + "')")) {
            rs.next();
            return rs.getBoolean(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
