package com.techcomfort.landvaultbackend.checkout;

import com.techcomfort.landvaultbackend.checkout.dto.CreateReservationRequest;
import com.techcomfort.landvaultbackend.checkout.dto.ReservationDto;
import com.techcomfort.landvaultbackend.checkout.dto.TransactionDto;
import com.techcomfort.landvaultbackend.checkout.internal.service.ReservationExpirySweeper;
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
import com.techcomfort.landvaultbackend.inventory.dto.PriceTierDto;
import com.techcomfort.landvaultbackend.kyc.dto.KycRecordDto;
import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import tools.jackson.databind.ObjectMapper;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reserving and buying, against a database where row-level security is
 * <strong>actually enforced</strong> — the app connects as the restricted
 * {@code landvault_app} role, not the container superuser.
 * <p>
 * <strong>Do not convert this class to {@code @ServiceConnection}.</strong>
 * A buyer has no tenant scope, so under real policies {@code plots} is
 * neither readable nor writable to them — and an RLS-blocked {@code UPDATE}
 * reports <em>zero affected rows</em>, exactly like losing a race for the
 * plot. Every test here would pass on a superuser connection whether the
 * {@code SECURITY DEFINER} functions of changeset 054 exist or not, which is
 * precisely how the tenant-staff login bug survived a green suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class ReservationCheckoutUnderRlsIT {

    private static final String APP_ROLE = "landvault_app_checkout_it";
    private static final String APP_ROLE_PASSWORD = "checkout-it-password";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "correct horse battery staple";

    private static final BigDecimal TIER_PRICE = new BigDecimal("20000000.0000");
    private static final BigDecimal CORNER_PREMIUM_PCT = new BigDecimal("10.00");

    /** Each estate gets its own longitude band, so fixtures never overlap into conflicts. */
    private static final AtomicInteger BAND = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "integration-test-signing-secret-of-at-least-32-bytes");

        // Liquibase migrates as the superuser; the app runs as the restricted
        // role. @ServiceConnection cannot express that split, and the split
        // is the entire point of this class.
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

    /** Called directly: expiry must be testable without waiting on a clock. */
    @Autowired
    private ReservationExpirySweeper sweeper;

    @Autowired
    private ObjectMapper objectMapper;

    private Tenant seller;
    private UUID estateId;
    private UUID tierId;
    private UUID plotId;
    private Buyer buyer;

    @BeforeEach
    void setUp() {
        seller = verifiedTenant();
        Listing listing = publishedListing(seller);
        estateId = listing.estateId();
        tierId = listing.tierId();
        plotId = listing.plotId();
        buyer = verifiedBuyer();
    }

    // ------------------------------------------------------------------
    // The RLS write escape — the test the whole slice turns on
    // ------------------------------------------------------------------

    /**
     * Without changeset 054's definer function this fails, and fails
     * <em>quietly</em>: the acquire's {@code UPDATE} matches zero rows under
     * the buyer's policy-filtered view of {@code plots}, which is
     * indistinguishable from the plot already being taken. Every buyer would
     * be told the plot was gone, forever, with no error anywhere.
     */
    @Test
    void aBuyerCanReserveAPlotEvenThoughRowLevelSecurityHidesItFromThem() {
        ReservationDto reservation = reserve(buyer, plotId);

        assertThat(reservation.status()).isEqualTo("active");
        assertThat(reservation.plotId()).isEqualTo(plotId);
        assertThat(reservation.estateId()).isEqualTo(estateId);
        assertThat(reservation.secondsRemaining())
                .as("the 45-minute window, counted down server-side")
                .isBetween(2600L, 2700L);
        assertThat(plotStatus(plotId)).isEqualTo("RESERVED");
    }

    /**
     * RS-2, the single correctness property this module exists for. Two
     * buyers, one plot, released together.
     * <p>
     * A read-then-write would let both through: both read "available" before
     * either wrote. The acquire is a compare-and-swap inside the database,
     * so exactly one can win.
     */
    @Test
    void twoBuyersReachingTheSamePlotAtOnceCannotBothHoldIt() throws Exception {
        Buyer other = verifiedBuyer();
        CountDownLatch startLine = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ResponseEntity<String>> first = pool.submit(attemptAfter(startLine, buyer));
            Future<ResponseEntity<String>> second = pool.submit(attemptAfter(startLine, other));
            // Both threads are parked on the latch, so they go at the plot
            // together rather than one comfortably after the other.
            startLine.countDown();

            List<ResponseEntity<String>> results = List.of(first.get(), second.get());

            assertThat(results).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED)
                    .as("exactly one buyer may hold a plot — double allocation is the fraud this prevents")
                    .hasSize(1);
            assertThat(results).filteredOn(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                    .as("the loser is told the plot is gone, not handed a second hold")
                    .hasSize(1)
                    .allSatisfy(r -> assertThat(r.getBody()).contains("PLOT_NOT_AVAILABLE"));
        }

        assertThat(activeReservationCount(plotId))
                .as("and only one reservation row exists for the plot")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Expiry and release
    // ------------------------------------------------------------------

    /**
     * RS-3. The sweep is the first scheduled job in this system, so it is
     * also the first thing that has to establish its own scope — there is no
     * ambient identity that sees past RLS.
     */
    @Test
    void anExpiredHoldIsSweptAndTheOriginalAvailabilityVariantComesBack() {
        UUID investmentPlot = addPlot("INV-1", "available-inv");
        ReservationDto reservation = reserve(buyer, investmentPlot);
        assertThat(plotStatus(investmentPlot)).isEqualTo("RESERVED");

        execute("UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE id = '"
                + reservation.id() + "'");
        sweeper.sweep();

        assertThat(plotStatus(investmentPlot))
                .as("an investment plot must not come back as a development plot")
                .isEqualTo("AVAILABLE_INV");
        assertThat(reservationStatus(reservation.id())).isEqualTo("EXPIRED");
        assertThat(queryString(
                "SELECT actor_user_id FROM audit_log_entries WHERE action = 'reservation.expired'"
                        + " AND target_id = '" + reservation.id() + "'"))
                .as("expiry is the system's doing — attributing it to the buyer would put an action "
                        + "they never took in the permanent record")
                .isNull();
    }

    @Test
    void cancellingReturnsThePlotImmediately() {
        ReservationDto reservation = reserve(buyer, plotId);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/reservations/" + reservation.id(), HttpMethod.DELETE,
                new HttpEntity<>(bearer(buyer.token())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(plotStatus(plotId)).isEqualTo("AVAILABLE_DEV");
        assertThat(reservationStatus(reservation.id())).isEqualTo("RELEASED");
    }

    /**
     * 404 rather than 403: a 403 would confirm that someone else's hold
     * exists at that id.
     */
    @Test
    void anotherBuyerCannotCancelAHoldTheyDoNotOwn() {
        ReservationDto reservation = reserve(buyer, plotId);
        Buyer stranger = verifiedBuyer();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/reservations/" + reservation.id(), HttpMethod.DELETE,
                new HttpEntity<>(bearer(stranger.token())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(plotStatus(plotId)).as("and the real holder keeps it").isEqualTo("RESERVED");
    }

    // ------------------------------------------------------------------
    // The gates
    // ------------------------------------------------------------------

    /** TX-4: a buyer holding a stale page cannot reserve on a delisted estate. */
    @Test
    void reservingIsRefusedOnceTheEstateStopsQualifying() {
        execute("UPDATE organizations SET status = 'SUSPENDED' WHERE id = '" + seller.id() + "'");

        ResponseEntity<String> response = attemptReserve(buyer, plotId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(plotStatus(plotId)).as("and nothing was held").isEqualTo("AVAILABLE_DEV");
    }

    /** KY-1: verification gates buying — and only buying. */
    @Test
    void anUnverifiedBuyerIsRefusedAndToldToVerify() {
        Buyer unverified = registerBuyer();

        ResponseEntity<String> response = attemptReserve(unverified, plotId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .as("a distinct code, so the frontend can route into verification rather than a dead end")
                .contains("KYC_REQUIRED");
        assertThat(plotStatus(plotId)).isEqualTo("AVAILABLE_DEV");
    }

    @Test
    void aBuyerWithoutTheCheckoutPermissionIsForbidden() {
        Buyer stripped = verifiedBuyer();
        execute("DELETE FROM user_roles WHERE user_id = '" + stripped.id() + "'");

        assertThat(attemptReserve(loginAgain(stripped), plotId).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------
    // Money
    // ------------------------------------------------------------------

    /**
     * TX-1. The frontend's current {@code initiateTransaction} posts
     * {@code basePrice}, {@code totalPrice} and {@code amountDue} from the
     * browser; a backend that honoured them would let the payer name their
     * own price.
     */
    @Test
    void aPriceSentByTheClientIsIgnoredEntirely() {
        ReservationDto reservation = reserve(buyer, plotId);

        TransactionDto transaction = postJson(buyer, "/api/checkout/transactions", """
                {
                  "reservationId": "%s",
                  "intent": "development",
                  "plan": "outright",
                  "basePrice": 1.00,
                  "totalPrice": 1.00,
                  "amountDue": 1.00,
                  "cornerPremiumPct": 0.00
                }
                """.formatted(reservation.id()), TransactionDto.class);

        assertThat(transaction.totalPrice()).isEqualByComparingTo(TIER_PRICE);
        assertThat(transaction.basePrice()).isEqualByComparingTo(TIER_PRICE);
        assertThat(storedTotalPrice(transaction.id()))
                .as("and what was stored is the server's figure, not the browser's")
                .isEqualByComparingTo(TIER_PRICE);
    }

    /**
     * TX-1's other half: captured at reservation, not recomputed when the
     * transaction is opened a few minutes later.
     */
    @Test
    void repricingTheTierAfterAHoldDoesNotChangeWhatTheBuyerAgreedTo() {
        ReservationDto reservation = reserve(buyer, plotId);

        execute("UPDATE price_tiers SET price = 99000000.0000 WHERE id = '" + tierId + "'");

        TransactionDto transaction = createTransaction(buyer, reservation.id(), "outright", null);

        assertThat(transaction.totalPrice())
                .as("the buyer agreed when they took the hold, not when they pressed pay")
                .isEqualByComparingTo(TIER_PRICE);
    }

    /** A corner plot's premium is applied, and all three figures are returned. */
    @Test
    void aCornerPlotCarriesItsPremiumAndExplainsIt() {
        UUID cornerPlot = addCornerPlot("C-1");
        ReservationDto reservation = reserve(buyer, cornerPlot);

        TransactionDto transaction = createTransaction(buyer, reservation.id(), "outright", null);

        assertThat(transaction.cornerPremiumPct()).isEqualByComparingTo(CORNER_PREMIUM_PCT);
        assertThat(transaction.basePrice()).isEqualByComparingTo(TIER_PRICE);
        assertThat(transaction.totalPrice())
                .as("tierPrice x (1 + premium/100), computed once in PlotPricing")
                .isEqualByComparingTo(new BigDecimal("22000000.0000"));
    }

    /** TX-3. Holding is not owning, and the status must never suggest otherwise. */
    @Test
    void aTransactionIsPendingAndThePlotIsHeldNeverSold() {
        ReservationDto reservation = reserve(buyer, plotId);

        TransactionDto transaction = createTransaction(buyer, reservation.id(), "outright", null);

        assertThat(transaction.status()).isEqualTo("pending_payment");
        assertThat(plotStatus(plotId))
                .as("reservation does not allocate — only finance verification can")
                .isEqualTo("RESERVED");
    }

    @Test
    void aSecondTransactionAgainstOneHoldIsRefused() {
        ReservationDto reservation = reserve(buyer, plotId);
        createTransaction(buyer, reservation.id(), "outright", null);

        ResponseEntity<String> second = restTemplate.exchange(
                "/api/checkout/transactions", HttpMethod.POST,
                entity(buyer.token(), body(reservation.id(), "outright", null)), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("TRANSACTION_ALREADY_EXISTS");
    }

    @Test
    void anInstallmentPlanNeedsAMonthCountAndNoOtherPlanMayCarryOne() {
        ReservationDto held = reserve(buyer, plotId);
        ResponseEntity<String> missingMonths = restTemplate.exchange(
                "/api/checkout/transactions", HttpMethod.POST,
                entity(buyer.token(), body(held.id(), "installment", null)), String.class);
        assertThat(missingMonths.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missingMonths.getBody()).contains("INVALID_PAYMENT_PLAN");

        ResponseEntity<String> strayMonths = restTemplate.exchange(
                "/api/checkout/transactions", HttpMethod.POST,
                entity(buyer.token(), body(held.id(), "outright", 12)), String.class);
        assertThat(strayMonths.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(createTransaction(buyer, held.id(), "installment", 12).installmentMonths()).isEqualTo(12);
    }

    // ------------------------------------------------------------------
    // Verification
    // ------------------------------------------------------------------

    /** KY-2: the document set follows residence, and a local buyer is not asked for a bill. */
    @Test
    void aLocalBuyerSubmitsAnNinAndIsNeverAskedForProofOfAddress() {
        Buyer local = registerBuyer("NG");

        KycRecordDto before = get("/api/kyc", local.token(), KycRecordDto.class).getBody();
        assertThat(before.buyerType()).isEqualTo("local");
        assertThat(before.status()).as("no record is written just by looking").isEqualTo("unsubmitted");
        assertThat(before.documents()).extracting(doc -> doc.type()).containsExactly("nin");

        ResponseEntity<String> withoutNin = restTemplate.exchange("/api/kyc", HttpMethod.POST,
                entity(local.token(), "{\"passportFile\":{\"fileName\":\"passport.pdf\"}}"), String.class);
        assertThat(withoutNin.getStatusCode())
                .as("a passport does not substitute for the NIN a local buyer must give")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(withoutNin.getBody()).contains("MISSING_KYC_DOCUMENTS");
    }

    @Test
    void aDiasporaBuyerSubmitsAPassportAndProofOfAddress() {
        Buyer diaspora = registerBuyer("GB");

        KycRecordDto record = get("/api/kyc", diaspora.token(), KycRecordDto.class).getBody();
        assertThat(record.buyerType()).isEqualTo("diaspora");
        assertThat(record.documents()).extracting(doc -> doc.type())
                .containsExactly("passport", "proof_of_address");
    }

    /**
     * The NIN is NDPR-regulated. {@code directors.id_number} carries four
     * unmet obligations for exactly this kind of value; this table was
     * created not to inherit the first of them.
     */
    @Test
    void theSubmittedNinIsNotReadableInTheDatabaseOrAnyResponse() {
        Buyer local = registerBuyer("NG");
        String nin = "12345678901";
        String body = postJson(local, "/api/kyc",
                "{\"ninNumber\":\"" + nin + "\",\"ninFile\":{\"fileName\":\"nin.pdf\"}}", String.class);

        assertThat(body).as("no route returns the NIN, not even masked").doesNotContain(nin);
        String stored = queryString("SELECT nin_number FROM kyc_records WHERE user_id = '" + local.id() + "'");
        assertThat(stored).isNotBlank();
        assertThat(stored)
                .as("the column holds ciphertext, never eleven digits")
                .doesNotContain(nin);
    }

    /**
     * A rejection names the document that failed, and approves the rest —
     * so the buyer resubmits one file rather than starting again.
     */
    @Test
    void aRejectionNamesTheFailedDocumentAndResubmissionReopensOnlyThat() {
        Buyer diaspora = registerBuyer("GB");
        postJson(diaspora, "/api/kyc",
                "{\"passportFile\":{\"fileName\":\"p.pdf\"},\"proofOfAddressFile\":{\"fileName\":\"a.pdf\"}}",
                String.class);

        KycRecordDto decided = postJson(adminToken(), "/api/admin/kyc/" + diaspora.id() + "/decision",
                "{\"decision\":\"rejected\",\"reason\":\"Address document is illegible.\","
                        + "\"failedDocumentTypes\":[\"proof_of_address\"]}", KycRecordDto.class);

        assertThat(decided.status()).isEqualTo("rejected");
        assertThat(decided.documents()).filteredOn(doc -> doc.type().equals("proof_of_address"))
                .allSatisfy(doc -> {
                    assertThat(doc.status()).isEqualTo("rejected");
                    assertThat(doc.rejectionReason()).isEqualTo("Address document is illegible.");
                });
        assertThat(decided.documents()).filteredOn(doc -> doc.type().equals("passport"))
                .as("a rejection is about specific evidence, not the whole submission")
                .allSatisfy(doc -> assertThat(doc.status()).isEqualTo("approved"));
    }

    /**
     * The login response carried a hardcoded {@code "unsubmitted"} for every
     * account until this module existed to answer it.
     */
    @Test
    void loginReportsTheBuyersRealVerificationStatus() {
        Buyer verified = verifiedBuyer();

        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(verified.email(), PASSWORD), AuthResponse.class);

        assertThat(response.getBody().user().kycStatus()).isEqualTo("approved");
        assertThat(restTemplate.postForEntity("/api/auth/login",
                new LoginRequest(registerBuyer().email(), PASSWORD), AuthResponse.class)
                .getBody().user().kycStatus())
                .as("and an absent record still reads as unsubmitted")
                .isEqualTo("unsubmitted");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private Callable<ResponseEntity<String>> attemptAfter(CountDownLatch startLine, Buyer who) {
        return () -> {
            startLine.await();
            return attemptReserve(who, plotId);
        };
    }

    /**
     * Deserialized by hand from the raw body, so that a refusal reports the
     * status and the error code rather than a deserialization failure. That
     * matters here more than usual: the way this test goes red when the
     * definer functions are missing is a 409, and the next person to break
     * changeset 054 should be told so in one line.
     */
    private ReservationDto reserve(Buyer who, UUID plot) {
        ResponseEntity<String> response = attemptReserve(who, plot);
        assertThat(response.getStatusCode())
                .as("a buyer must be able to hold a plot RLS hides from them — see changeset 054. Body: %s",
                        response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        try {
            return objectMapper.readValue(response.getBody(), ReservationDto.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read: " + response.getBody(), e);
        }
    }

    private ResponseEntity<String> attemptReserve(Buyer who, UUID plot) {
        return restTemplate.exchange("/api/reservations", HttpMethod.POST,
                entity(who.token(), new CreateReservationRequest(plot)), String.class);
    }

    private TransactionDto createTransaction(Buyer who, UUID reservationId, String plan, Integer months) {
        ResponseEntity<TransactionDto> response = restTemplate.exchange(
                "/api/checkout/transactions", HttpMethod.POST,
                entity(who.token(), body(reservationId, plan, months)), TransactionDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static String body(UUID reservationId, String plan, Integer months) {
        return """
                {"reservationId":"%s","intent":"development","plan":"%s"%s}
                """.formatted(reservationId, plan,
                months == null ? "" : ",\"installmentMonths\":" + months);
    }

    private Buyer verifiedBuyer() {
        Buyer newBuyer = registerBuyer();
        postJson(newBuyer, "/api/kyc",
                "{\"ninNumber\":\"12345678901\",\"ninFile\":{\"fileName\":\"nin.pdf\"}}", String.class);
        postJson(adminToken(), "/api/admin/kyc/" + newBuyer.id() + "/decision",
                "{\"decision\":\"approved\"}", String.class);
        return loginAgain(newBuyer);
    }

    private Buyer registerBuyer() {
        return registerBuyer("NG");
    }

    private Buyer registerBuyer(String country) {
        String email = "buyer+" + UUID.randomUUID() + "@example.com";
        RegisterRequest request = new RegisterRequest(
                "Ada", "Buyer", email, "+2348000000000", PASSWORD, country, Currency.NGN);
        ResponseEntity<AuthResponse> response =
                restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new Buyer(userId(email), email, response.getBody().token());
    }

    private Buyer loginAgain(Buyer who) {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(who.email(), PASSWORD), AuthResponse.class);
        return new Buyer(who.id(), who.email(), response.getBody().token());
    }

    private Tenant verifiedTenant() {
        String rc = "RC-" + UUID.randomUUID();
        CreateTenantRequest request = new CreateTenantRequest(
                new CompanyIdentityDto("Checkout Co " + rc, null, rc, "Limited Liability (Ltd)", "2020-01-01",
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
        return new Tenant(id, staffToken(id));
    }

    private Listing publishedListing(Tenant tenant) {
        UUID branchId = UUID.randomUUID();
        execute("INSERT INTO branches (id, created_at, deleted, organization_id, name) VALUES ('"
                + branchId + "', now(), false, '" + tenant.id() + "', 'Head Office')");

        int band = BAND.incrementAndGet();
        EstateDto estate = restTemplate.exchange("/api/portal/estates", HttpMethod.POST,
                entity(tenant.token(), new CreateEstateRequest(
                        "Checkout Gardens " + band, null, "Gwarinpa", "FCT", "Abuja", "1 Test Close",
                        CORNER_PREMIUM_PCT, null, List.of("Perimeter fence"), branchId,
                        new GeoJsonPolygonDto("Polygon", List.of(estateRing(band))))),
                EstateDto.class).getBody();

        UUID tier = post(tenant, "/api/portal/estates/" + estate.id() + "/price-tiers",
                new CreatePriceTierRequest("LAND_SIZE", new BigDecimal("250.00"), TIER_PRICE,
                        Currency.NGN, "Standard 250"), PriceTierDto.class).id();
        UUID block = post(tenant, "/api/portal/estates/" + estate.id() + "/blocks",
                new CreateBlockRequest("A", "Block A"), BlockDto.class).id();

        ResponseEntity<String> plots = restTemplate.exchange(
                "/api/portal/estates/" + estate.id() + "/plots", HttpMethod.POST,
                entity(tenant.token(), new CreatePlotsRequest(List.of(
                        new CreatePlotRequest("001", block, tier, false, "available-dev",
                                null, null, null, null, null, null)))),
                String.class);
        assertThat(plots.getStatusCode()).as(plots.getBody()).isEqualTo(HttpStatus.CREATED);

        // Full cost disclosure is a publication condition now (changeset
        // 059). These tests are not about fees, so they declare the honest
        // minimum: an explicitly empty schedule, plus refund terms. Silence
        // would refuse the publish, which is the feature working.
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

        return new Listing(estate.id(), tier, block,
                UUID.fromString(queryString(
                        "SELECT id FROM plots WHERE estate_id = '" + estate.id() + "' AND plot_number = '001'")));
    }

    private UUID addPlot(String number, String status) {
        return addPlot(number, status, false);
    }

    private UUID addCornerPlot(String number) {
        return addPlot(number, "available-dev", true);
    }

    private UUID addPlot(String number, String status, boolean corner) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/portal/estates/" + estateId + "/plots", HttpMethod.POST,
                entity(seller.token(), new CreatePlotsRequest(List.of(
                        new CreatePlotRequest(number, null, tierId, corner, status,
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

    private String staffToken(UUID owningTenantId) {
        String email = "ed+" + UUID.randomUUID() + "@example.com";
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
                + "WHERE lower(u.email) = lower('" + email + "') AND r.code = 'executive_director'");

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

    private <T> T postJson(Buyer who, String path, String json, Class<T> type) {
        return postJson(who.token(), path, json, type);
    }

    private <T> T postJson(String token, String path, String json, Class<T> type) {
        ResponseEntity<T> response = restTemplate.exchange(
                path, HttpMethod.POST, entity(token, json), type);
        assertThat(response.getStatusCode()).as("POST " + path).isIn(HttpStatus.OK, HttpStatus.CREATED);
        return response.getBody();
    }

    private <T> ResponseEntity<T> get(String path, String token, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), type);
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

    // --- direct database reads, as the superuser: assertions about what the
    // --- app cannot see are only meaningful from outside its own policies.

    private String plotStatus(UUID plot) {
        return queryString("SELECT status FROM plots WHERE id = '" + plot + "'");
    }

    private String reservationStatus(UUID reservationId) {
        return queryString("SELECT status FROM reservations WHERE id = '" + reservationId + "'");
    }

    private BigDecimal storedTotalPrice(UUID transactionId) {
        return new BigDecimal(queryString(
                "SELECT total_price FROM transactions WHERE id = '" + transactionId + "'"));
    }

    private int activeReservationCount(UUID plot) {
        return Integer.parseInt(queryString(
                "SELECT count(*) FROM reservations WHERE plot_id = '" + plot + "' AND status = 'ACTIVE'"));
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
