package com.techcomfort.landvaultbackend.checkout;

import com.techcomfort.landvaultbackend.testsupport.EstatePhotoFixture;
import com.techcomfort.landvaultbackend.testsupport.LetterApprovalFixture;
import com.techcomfort.landvaultbackend.documents.dto.DocumentDto;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.core.ParameterizedTypeReference;
import com.techcomfort.landvaultbackend.common.PageResponse;
import com.techcomfort.landvaultbackend.testsupport.EstateTitleFixture;
import com.techcomfort.landvaultbackend.portfolio.dto.PortfolioSummaryDto;
import com.techcomfort.landvaultbackend.portfolio.dto.OwnedPlotDto;
import com.techcomfort.landvaultbackend.checkout.PlanStanding;
import com.techcomfort.landvaultbackend.checkout.dto.CreateReservationRequest;
import com.techcomfort.landvaultbackend.checkout.dto.ReservationDto;
import com.techcomfort.landvaultbackend.checkout.OfferSummary;
import com.techcomfort.landvaultbackend.checkout.PlanSchedule;
import com.techcomfort.landvaultbackend.checkout.dto.TransactionDto;
import com.techcomfort.landvaultbackend.checkout.internal.service.ReservationExpirySweeper;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonPolygonDto;
import com.techcomfort.landvaultbackend.documents.internal.render.DisclosureClauses;
import com.techcomfort.landvaultbackend.documents.internal.render.DocumentFormats;
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
import com.techcomfort.landvaultbackend.payments.dto.PayoutDto;
import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import com.techcomfort.landvaultbackend.tenancy.dto.AddressDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyIdentityDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CompanyPresenceDto;
import com.techcomfort.landvaultbackend.tenancy.dto.CreateTenantRequest;
import com.techcomfort.landvaultbackend.tenancy.dto.PrimaryContactDto;
import com.techcomfort.landvaultbackend.tenancy.dto.SocialsDto;
import com.techcomfort.landvaultbackend.tenancy.dto.TenantDetailDto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackAmounts;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackClient;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackSignature;
import org.springframework.beans.factory.annotation.Value;
import com.techcomfort.landvaultbackend.payments.internal.service.InstalmentReminderJob;
import com.techcomfort.landvaultbackend.payments.internal.service.PaymentSweeper;
import com.techcomfort.landvaultbackend.payments.internal.service.PayoutRecorder;
import com.techcomfort.landvaultbackend.payments.internal.service.PayoutSweeper;
import com.techcomfort.landvaultbackend.payments.internal.service.RefundSweeper;
import com.techcomfort.landvaultbackend.payments.internal.service.SettlementAlertSender;
import org.springframework.test.util.ReflectionTestUtils;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
    private static final String PASSWORD = "correct horse battery staple 9";
    private static final String LAGOS_ADDRESS = """
            {"line1":"12 Adeola Odeku Street","city":"Ikeja","state":"Lagos State","country":"NG"}""";
    /** How {@link #LAGOS_ADDRESS} prints on a letter. */
    private static final String LAGOS_LINES = "12 Adeola Odeku Street|Ikeja, Lagos|Nigeria";

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

    /** The key webhooks are signed with in tests — the test classpath's dummy key. */
    @Value("${landvault.paystack.secret-key}")
    private String paystackSecretKey;

    /** Called directly: abandonment must be testable without waiting on a clock. */
    @Autowired
    private PaymentSweeper paymentSweeper;

    /** Its per-transfer cap is changed directly to test a cap raised halfway. */
    @Autowired
    private PayoutRecorder payoutRecorder;

    /** Called directly: a lost transfer webhook must be testable without waiting on a clock. */
    @Autowired
    private PayoutSweeper payoutSweeper;

    /** Called directly with a chosen day: reminders must be testable without waiting on a clock. */
    @Autowired
    private InstalmentReminderJob instalmentReminders;

    /** Called directly: a lost refund webhook must be testable without waiting on a clock. */
    @Autowired
    private RefundSweeper refundSweeper;

    /** Payout alerts, checked rather than sent. */
    @MockitoBean
    private SettlementAlertSender alerts;

    /** Real, watched: payout lists must look company names up in one batch. */
    @MockitoSpyBean
    private TenancyApi tenancyApi;

    /** Stands in for Paystack: these tests never call the real gateway. */
    @MockitoBean
    private PaystackClient paystack;

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

    /**
     * The agreed price is rounded to the kobo once, at reservation, so it is
     * an amount Paystack can actually charge: ₦20,000,001 × 1.125 is
     * ₦22,500,001.125, agreed as ₦22,500,001.13 (half up).
     */
    @Test
    void theAgreedPriceIsRoundedToTheKoboAtReservation() {
        execute("UPDATE price_tiers SET price = 20000001 WHERE id = '" + tierId + "'");
        execute("UPDATE estates SET corner_premium_pct = 12.50 WHERE id = '" + estateId + "'");
        UUID cornerPlot = addCornerPlot("C-9");

        ReservationDto reservation = reserve(buyer, cornerPlot);

        assertThat(new BigDecimal(queryString("SELECT total_price FROM reservations WHERE id = '" + reservation.id() + "'")))
                .isEqualByComparingTo(new BigDecimal("22500001.13"));
        TransactionDto transaction = createTransaction(buyer, reservation.id(), "outright", null);
        assertThat(transaction.totalPrice()).as("the transaction copies the agreed figure")
                .isEqualByComparingTo(new BigDecimal("22500001.13"));
    }

    // ------------------------------------------------------------------
    // PY-1: starting a payment (Paystack replaced by a stand-in)
    // ------------------------------------------------------------------

    /** The amount is the agreed price, in kobo, with our reference and enough metadata to trace it back. */
    @Test
    void aBuyerGetsAPaystackLinkForTheAgreedPrice() {
        TransactionDto transaction = outrightTransaction();
        paystackReturnsALink();

        ResponseEntity<String> started = pay(buyer, transaction.id());

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(started.getBody()).contains("\"authorizationUrl\":\"https://checkout.paystack.com/test-link\"")
                .contains("\"status\":\"initialized\"").contains("\"amount\":20000000.00");
        ArgumentCaptor<PaystackClient.InitializeRequest> sent = ArgumentCaptor.forClass(PaystackClient.InitializeRequest.class);
        verify(paystack).initialize(sent.capture());
        assertThat(sent.getValue().amountKobo()).as("₦20,000,000 in kobo").isEqualTo(2_000_000_000L);
        assertThat(sent.getValue().email()).isEqualTo(buyer.email());
        assertThat(sent.getValue().reference()).startsWith("LV-PAY-");
        assertThat(sent.getValue().metadata()).containsEntry("transactionId", transaction.id().toString())
                .containsKeys("paymentId", "reservationId", "plotId");
        assertThat(queryString("SELECT status || ':' || amount_kobo FROM payments WHERE reference = '"
                + sent.getValue().reference() + "'")).isEqualTo("INITIALIZED:2000000000");
    }

    /** Pressing pay twice must not open two payments for one plot. */
    @Test
    void pressingPayAgainHandsBackTheSameOpenLink() {
        TransactionDto transaction = outrightTransaction();
        paystackReturnsALink();

        String first = pay(buyer, transaction.id()).getBody();
        ResponseEntity<String> again = pay(buyer, transaction.id());

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).isEqualTo(first);
        verify(paystack, times(1)).initialize(any());
    }

    @Test
    void onlyTheBuyerWhoseTransactionItIsCanPay() {
        TransactionDto transaction = outrightTransaction();

        assertThat(pay(verifiedBuyer(), transaction.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(paystack, never()).initialize(any());
    }

    @Test
    void milestonePlansAndOtherCurrenciesAreRefusedBeforePaystackIsCalled() {
        TransactionDto milestone = createTransaction(buyer, reserve(buyer, plotId).id(), "milestone", null);
        assertThat(pay(buyer, milestone.id()).getBody()).contains("PAYMENT_PLAN_NOT_SUPPORTED");

        execute("UPDATE transactions SET plan = 'OUTRIGHT', currency = 'USD' WHERE id = '" + milestone.id() + "'");
        assertThat(pay(buyer, milestone.id()).getBody()).contains("CURRENCY_NOT_SUPPORTED");
        verify(paystack, never()).initialize(any());
    }

    @Test
    void aTransactionPastWaitingForPaymentCannotBePaidAgain() {
        TransactionDto transaction = outrightTransaction();
        execute("UPDATE transactions SET status = 'PAYMENT_RECEIVED' WHERE id = '" + transaction.id() + "'");

        ResponseEntity<String> refused = pay(buyer, transaction.id());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("TRANSACTION_NOT_PAYABLE");
    }

    /** If Paystack is down nothing is half-saved: no payment row, a clear "try again". */
    @Test
    void paystackBeingDownLeavesNoPaymentBehind() {
        TransactionDto transaction = outrightTransaction();
        when(paystack.initialize(any())).thenThrow(new PaymentException.GatewayUnavailable());

        ResponseEntity<String> refused = pay(buyer, transaction.id());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getBody()).contains("PAYMENT_PROVIDER_UNAVAILABLE");
        assertThat(queryString("SELECT count(*) FROM payments WHERE transaction_id = '" + transaction.id() + "'"))
                .isEqualTo("0");
    }

    // ------------------------------------------------------------------
    // PY-3/4/6/8: confirming with Paystack (stand-in), never by trusting a browser
    // ------------------------------------------------------------------

    /** Confirmed once, however many times it is reported — and the plot is still not sold. */
    @Test
    void aConfirmedPaymentMovesTheTransactionOnceAndTheDuplicateChangesNothing() {
        TransactionDto transaction = outrightTransaction();
        String reference = startPayment(transaction);
        paystackSays(reference, "success", 2_000_000_000L, "NGN", "Approved");

        ResponseEntity<String> confirmed = confirm(buyer, reference);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody()).contains("\"status\":\"succeeded\"").contains("\"channel\":\"bank_transfer\"")
                .contains("\"gatewayResponse\":\"Approved\"");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'"))
                .isEqualTo("AWAITING_FINANCE");
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'"))
                .as("paid is not sold: finance verifies first").isEqualTo("RESERVED");

        assertThat(confirm(buyer, reference).getBody()).contains("\"status\":\"succeeded\"");
        verify(paystack, times(1)).verify(reference);
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'transaction.payment_received' "
                + "AND target_id = '" + transaction.id() + "'")).as("advanced exactly once").isEqualTo("1");
    }

    /** A success for a different amount is not this payment succeeding. */
    @Test
    void aWrongAmountIsMismatchedAndTheTransactionDoesNotMove() {
        TransactionDto transaction = outrightTransaction();
        String reference = startPayment(transaction);
        paystackSays(reference, "success", 100L, "NGN", "Approved");

        assertThat(confirm(buyer, reference).getBody()).contains("\"status\":\"mismatched\"");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'"))
                .isEqualTo("PENDING_PAYMENT");
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'payment.mismatched'"))
                .isNotEqualTo("0");
    }

    /** PY-6: the real reason, and the buyer can try again. */
    @Test
    void aDeclinedPaymentKeepsTheRealReasonAndTheBuyerCanTryAgain() {
        TransactionDto transaction = outrightTransaction();
        String reference = startPayment(transaction);
        paystackSays(reference, "failed", 2_000_000_000L, "NGN", "Insufficient Funds");

        assertThat(confirm(buyer, reference).getBody()).contains("\"status\":\"failed\"")
                .contains("\"gatewayResponse\":\"Insufficient Funds\"");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'"))
                .isEqualTo("PENDING_PAYMENT");
        assertThat(pay(buyer, transaction.id()).getStatusCode()).as("a new attempt, not the failed link")
                .isEqualTo(HttpStatus.CREATED);
    }

    /** A transfer still on its way is left open — it can land minutes later. */
    @Test
    void anUnfinishedPaymentStaysOpenUntilPaystackSaysOtherwise() {
        TransactionDto transaction = outrightTransaction();
        String reference = startPayment(transaction);

        paystackSays(reference, "pending", 2_000_000_000L, "NGN", "Transfer pending");
        assertThat(confirm(buyer, reference).getBody()).contains("\"status\":\"initialized\"");

        paystackSays(reference, "success", 2_000_000_000L, "NGN", "Approved");
        assertThat(confirm(buyer, reference).getBody()).contains("\"status\":\"succeeded\"");
    }

    @Test
    void onlyTheBuyerWhosePaymentItIsCanAskForConfirmation() {
        String reference = startPayment(outrightTransaction());

        assertThat(confirm(verifiedBuyer(), reference).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(confirm(buyer, "LV-PAY-NOSUCHREFERENCE").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(paystack, never()).verify(any());
    }

    // ------------------------------------------------------------------
    // PY-7/10/4: Paystack's webhook — signed, stored exactly, confirmed once
    // ------------------------------------------------------------------

    /** The buyer never comes back (a closed tab): the webhook alone confirms the payment. */
    @Test
    void aSignedChargeSuccessWebhookConfirmsThePaymentAndIsStoredExactly() {
        TransactionDto transaction = outrightTransaction();
        String reference = startPayment(transaction);
        paystackSays(reference, "success", 2_000_000_000L, "NGN", "Approved");
        // Odd spacing on purpose: if the body were parsed and re-serialised before
        // checking, the signature would no longer match and this would be refused.
        String body = "{\"event\":\"charge.success\",  \"data\": {\"reference\":\"" + reference + "\",\"amount\":2000000000}}";

        ResponseEntity<String> received = webhook(body, PaystackSignature.sign(body.getBytes(StandardCharsets.UTF_8), paystackSecretKey));

        assertThat(received.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT status FROM payments WHERE reference = '" + reference + "'")).isEqualTo("SUCCEEDED");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'"))
                .isEqualTo("AWAITING_FINANCE");
        assertThat(queryString("SELECT raw_body FROM payment_gateway_events WHERE reference = '" + reference + "'"))
                .as("kept byte for byte, spacing included").isEqualTo(body);
    }

    /** PY-7: nobody can post themselves a payment. */
    @Test
    void anUnsignedOrForgedWebhookIsRefusedAndNothingIsStoredOrChanged() {
        TransactionDto transaction = outrightTransaction();
        String reference = startPayment(transaction);
        paystackSays(reference, "success", 2_000_000_000L, "NGN", "Approved");
        String body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"" + reference + "\"}}";

        assertThat(webhook(body, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(webhook(body, PaystackSignature.sign(body.getBytes(StandardCharsets.UTF_8), "sk_test_someone_elses_key"))
                .getBody()).contains("WEBHOOK_SIGNATURE_INVALID");
        String tampered = body.replace("charge.success", "charge.success ");
        assertThat(webhook(tampered, PaystackSignature.sign(body.getBytes(StandardCharsets.UTF_8), paystackSecretKey))
                .getStatusCode()).as("signed for different bytes").isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(queryString("SELECT status FROM payments WHERE reference = '" + reference + "'")).isEqualTo("INITIALIZED");
        assertThat(queryString("SELECT count(*) FROM payment_gateway_events WHERE reference = '" + reference + "'"))
                .isEqualTo("0");
        verify(paystack, never()).verify(any());
    }

    /** Paystack resends; the return page may confirm too. One confirmation, every delivery recorded. */
    @Test
    void aRepeatedWebhookIsRecordedButConfirmsOnlyOnce() {
        TransactionDto transaction = outrightTransaction();
        String reference = startPayment(transaction);
        paystackSays(reference, "success", 2_000_000_000L, "NGN", "Approved");
        String body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"" + reference + "\"}}";
        String signature = PaystackSignature.sign(body.getBytes(StandardCharsets.UTF_8), paystackSecretKey);

        assertThat(webhook(body, signature).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(webhook(body, signature).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirm(buyer, reference).getBody()).contains("\"status\":\"succeeded\"");

        verify(paystack, times(1)).verify(reference);
        assertThat(queryString("SELECT count(*) FROM payment_gateway_events WHERE reference = '" + reference + "'"))
                .isEqualTo("2");
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'transaction.payment_received' "
                + "AND target_id = '" + transaction.id() + "'")).isEqualTo("1");
    }

    /** If confirming fails, the message is still kept and Paystack is told to retry. */
    @Test
    void whenPaystackIsDownTheEventIsKeptAndARetryIsAskedFor() {
        TransactionDto transaction = outrightTransaction();
        String reference = startPayment(transaction);
        when(paystack.verify(reference)).thenThrow(new PaymentException.GatewayUnavailable());
        String body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"" + reference + "\"}}";

        ResponseEntity<String> received = webhook(body, PaystackSignature.sign(body.getBytes(StandardCharsets.UTF_8), paystackSecretKey));

        assertThat(received.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(queryString("SELECT count(*) FROM payment_gateway_events WHERE reference = '" + reference + "'"))
                .as("stored in its own transaction, before the failure").isEqualTo("1");
        assertThat(queryString("SELECT status FROM payments WHERE reference = '" + reference + "'")).isEqualTo("INITIALIZED");
    }

    @Test
    void anUnknownReferenceOrOtherEventIsKeptAndOtherwiseIgnored() {
        String unknown = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"NOT-OURS-1\"}}";
        String transfer = "{\"event\":\"transfer.success\",\"data\":{\"reference\":\"TRF-1\"}}";

        assertThat(webhook(unknown, PaystackSignature.sign(unknown.getBytes(StandardCharsets.UTF_8), paystackSecretKey))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(webhook(transfer, PaystackSignature.sign(transfer.getBytes(StandardCharsets.UTF_8), paystackSecretKey))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT string_agg(event_type, ',' ORDER BY event_type) FROM payment_gateway_events "
                + "WHERE reference IN ('NOT-OURS-1', 'TRF-1')")).isEqualTo("charge.success,transfer.success");
        verify(paystack, never()).verify(any());
    }

    /** Append-only is the database's rule, not just the code's. */
    @Test
    void theAppCanNeverRewriteOrDeleteAStoredGatewayEvent() {
        assertThat(queryString("SELECT has_table_privilege('" + APP_ROLE + "', 'payment_gateway_events', 'INSERT')"))
                .isEqualTo("t");
        assertThat(queryString("SELECT has_table_privilege('" + APP_ROLE + "', 'payment_gateway_events', 'UPDATE') "
                + "OR has_table_privilege('" + APP_ROLE + "', 'payment_gateway_events', 'DELETE')")).isEqualTo("f");
    }

    // ------------------------------------------------------------------
    // PY-5: the payment sweep — nothing paid after the hold + grace → released
    // ------------------------------------------------------------------

    /** Without the sweep, an abandoned checkout keeps its plot off the market forever. */
    @Test
    void anAbandonedPurchaseIsReleasedAfterTheHoldAndGraceAndThePlotIsBackOnSale() {
        TransactionDto transaction = outrightTransaction();
        String reference = startPayment(transaction);
        paystackSays(reference, "abandoned", 2_000_000_000L, "NGN", "The customer left");
        holdEndedMinutesAgo(transaction, 20);

        paymentSweeper.sweep();

        assertThat(queryString("SELECT status FROM payments WHERE reference = '" + reference + "'")).isEqualTo("ABANDONED");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("ABANDONED");
        assertThat(queryString("SELECT status FROM reservations WHERE id = '" + transaction.reservationId() + "'"))
                .isEqualTo("EXPIRED");
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'"))
                .as("back on sale, with its original availability").isEqualTo("AVAILABLE_DEV");
        assertThat(attemptReserve(verifiedBuyer(), plotId).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** Pressing "buy" but never "pay" is abandonment too. */
    @Test
    void aPurchaseWithNoPaymentAttemptIsReleasedToo() {
        TransactionDto transaction = outrightTransaction();
        holdEndedMinutesAgo(transaction, 20);

        paymentSweeper.sweep();

        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("ABANDONED");
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).isEqualTo("AVAILABLE_DEV");
        verify(paystack, never()).verify(any());
    }

    /** Inside the grace period a transfer can still land: nothing is touched, Paystack isn't even asked. */
    @Test
    void aPurchaseInsideTheGracePeriodIsLeftAlone() {
        TransactionDto transaction = outrightTransaction();
        startPayment(transaction);
        holdEndedMinutesAgo(transaction, 5);

        paymentSweeper.sweep();

        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("PENDING_PAYMENT");
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).isEqualTo("RESERVED");
        verify(paystack, never()).verify(any());
    }

    /** The webhook went missing but the money arrived: the sweep finds it and confirms, it does not abandon. */
    @Test
    void aPaymentWhoseWebhookWasLostIsConfirmedByTheSweepNotAbandoned() {
        TransactionDto transaction = outrightTransaction();
        String reference = startPayment(transaction);
        paystackSays(reference, "success", 2_000_000_000L, "NGN", "Approved");
        holdEndedMinutesAgo(transaction, 20);

        paymentSweeper.sweep();

        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("AWAITING_FINANCE");
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).isEqualTo("RESERVED");
    }

    /** Decided with the user: late money is real money — recorded, flagged for finance, the plot not snatched back. */
    @Test
    void moneyArrivingAfterAbandonmentIsRecordedAndFlaggedForFinance() {
        TransactionDto transaction = outrightTransaction();
        String reference = startPayment(transaction);
        paystackSays(reference, "abandoned", 2_000_000_000L, "NGN", "The customer left");
        holdEndedMinutesAgo(transaction, 20);
        paymentSweeper.sweep();

        paystackSays(reference, "success", 2_000_000_000L, "NGN", "Approved");
        String body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"" + reference + "\"}}";
        assertThat(webhook(body, PaystackSignature.sign(body.getBytes(StandardCharsets.UTF_8), paystackSecretKey))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(queryString("SELECT status || ':' || (review_reason IS NOT NULL) FROM payments WHERE reference = '"
                + reference + "'")).isEqualTo("SUCCEEDED:true");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("ABANDONED");
        assertThat(confirm(buyer, reference).getBody()).contains("\"underReview\":true");
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'payment.needs_review' "
                + "AND detail LIKE '%" + reference + "%'")).isEqualTo("1");
    }

    /** Paystack down during the sweep: nothing changes now, it is retried next run. */
    @Test
    void whenPaystackIsDownTheSweepChangesNothingAndTriesAgainLater() {
        TransactionDto transaction = outrightTransaction();
        String reference = startPayment(transaction);
        when(paystack.verify(reference)).thenThrow(new PaymentException.GatewayUnavailable());
        holdEndedMinutesAgo(transaction, 20);

        paymentSweeper.sweep();

        assertThat(queryString("SELECT status FROM payments WHERE reference = '" + reference + "'")).isEqualTo("INITIALIZED");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("PENDING_PAYMENT");
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).isEqualTo("RESERVED");
    }

    // ------------------------------------------------------------------
    // FV-1..FV-3: the developer's finance verifies, then the plot is sold
    // ------------------------------------------------------------------

    /** FV-2: what was agreed beside what Paystack recorded — and only this company's sales. */
    @Test
    void financeSeesItsOwnPaidPurchasesBesidePaystacksRecord() {
        TransactionDto transaction = paidPurchase();
        String officer = staffWithRole(seller.id(), "finance_officer", null);

        String queue = restTemplate.exchange("/api/portal/finance/transactions", HttpMethod.GET,
                entity(officer, null), String.class).getBody();
        assertThat(queue).contains(transaction.id().toString()).contains("\"expectedAmount\":20000000")
                .contains("\"amountPaid\":20000000.00").contains("\"channel\":\"bank_transfer\"")
                .contains("\"amountsMatch\":true").contains(buyer.email());

        Tenant otherCompany = verifiedTenant();
        assertThat(restTemplate.exchange("/api/portal/finance/transactions", HttpMethod.GET,
                entity(staffWithRole(otherCompany.id(), "finance_officer", null), null), String.class).getBody())
                .doesNotContain(transaction.id().toString());
        assertThat(restTemplate.exchange("/api/portal/finance/transactions", HttpMethod.GET,
                entity(buyer.token(), null), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** FV-3: allocation — sold, verified, converted, together; and never twice. */
    @Test
    void verifyingAllocatesThePlotTransactionAndReservationTogether() {
        TransactionDto transaction = paidPurchase();
        String officer = staffWithRole(seller.id(), "finance_officer", null);

        ResponseEntity<String> verified = financeAction(officer, transaction.id(), "verify", null);

        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody()).contains("\"status\":\"verified\"");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("VERIFIED");
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).isEqualTo("SOLD");
        assertThat(queryString("SELECT status FROM reservations WHERE id = '" + transaction.reservationId() + "'"))
                .isEqualTo("CONVERTED");
        assertThat(queryString("SELECT actor_user_id IS NOT NULL FROM audit_log_entries WHERE action = 'transaction.verified' "
                + "AND target_id = '" + transaction.id() + "'")).as("a person decided").isEqualTo("t");

        assertThat(financeAction(officer, transaction.id(), "verify", null).getBody())
                .contains("TRANSACTION_NOT_AWAITING_FINANCE");
    }

    /** All or nothing: if the plot can't be sold, the transaction and reservation don't move either. */
    @Test
    void ifThePlotIsNoLongerHeldNothingChanges() {
        TransactionDto transaction = paidPurchase();
        execute("UPDATE plots SET status = 'AVAILABLE_DEV' WHERE id = '" + plotId + "'");

        ResponseEntity<String> refused = financeAction(staffWithRole(seller.id(), "finance_officer", null),
                transaction.id(), "verify", null);

        assertThat(refused.getBody()).contains("PLOT_NOT_RESERVED");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("AWAITING_FINANCE");
        assertThat(queryString("SELECT status FROM reservations WHERE id = '" + transaction.reservationId() + "'")).isEqualTo("ACTIVE");
    }

    /** The human check backs the gateway: no confirmed payment for the agreed amount, no allocation. */
    @Test
    void withoutAConfirmedPaymentForTheAgreedAmountNothingIsAllocated() {
        TransactionDto transaction = paidPurchase();
        execute("UPDATE payments SET amount = 1.00, amount_kobo = 100 WHERE transaction_id = '" + transaction.id() + "'");

        assertThat(financeAction(staffWithRole(seller.id(), "finance_officer", null), transaction.id(), "verify", null)
                .getBody()).contains("PAYMENT_NOT_CONFIRMED");
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).isEqualTo("RESERVED");
    }

    /** Decided with the user: rejected → the plot back on sale, the money flagged for refund. */
    @Test
    void rejectingPutsThePlotBackOnSaleAndFlagsTheRefund() {
        TransactionDto transaction = paidPurchase();
        String officer = staffWithRole(seller.id(), "finance_officer", null);

        assertThat(financeAction(officer, transaction.id(), "reject", "{\"reason\":\"\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(financeAction(officer, transaction.id(), "reject", "{\"reason\":\"Charged back by the bank.\"}")
                .getBody()).contains("\"status\":\"rejected\"");

        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("REJECTED");
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).isEqualTo("AVAILABLE_DEV");
        assertThat(queryString("SELECT status FROM reservations WHERE id = '" + transaction.reservationId() + "'")).isEqualTo("RELEASED");
        assertThat(queryString("SELECT review_reason FROM payments WHERE transaction_id = '" + transaction.id() + "'"))
                .startsWith("Refund due").contains("Charged back by the bank.");
    }

    /** The branch wall holds for finance too: another branch's officer can't see or decide it. */
    @Test
    void aBranchScopedOfficerCannotDecideAnotherBranchsSale() {
        TransactionDto transaction = paidPurchase();
        UUID otherBranch = UUID.randomUUID();
        execute("INSERT INTO branches (id, created_at, deleted, organization_id, name) VALUES ('"
                + otherBranch + "', now(), false, '" + seller.id() + "', 'Other Branch')");
        String elsewhere = staffWithRole(seller.id(), "finance_officer", otherBranch);

        assertThat(restTemplate.exchange("/api/portal/finance/transactions", HttpMethod.GET,
                entity(elsewhere, null), String.class).getBody()).doesNotContain(transaction.id().toString());
        assertThat(financeAction(elsewhere, transaction.id(), "verify", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).isEqualTo("RESERVED");
    }

    // ------------------------------------------------------------------
    // The buyer is emailed about the decision
    // ------------------------------------------------------------------

    @Test
    void verifyingEmailsTheBuyerOnceThatThePlotIsTheirsWithLinksToThePurchaseAndTheLetter() {
        TransactionDto transaction = paidPurchase();

        assertThat(financeAction(staffWithRole(seller.id(), "finance_officer", null), transaction.id(), "verify", null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<SettlementAlertSender.PurchaseDecisionAlert> sent =
                ArgumentCaptor.forClass(SettlementAlertSender.PurchaseDecisionAlert.class);
        verify(alerts, times(1)).purchaseDecided(sent.capture());
        assertThat(sent.getValue().to()).isEqualTo(buyer.email());
        assertThat(sent.getValue().verified()).isTrue();
        assertThat(new BigDecimal(sent.getValue().amount())).isEqualByComparingTo(TIER_PRICE);
        assertThat(sent.getValue().purchaseUrl()).as("the frontend origin from PAYSTACK_CALLBACK_URL, not its path")
                .matches("https?://[^/]+/purchases/" + transaction.id());
        assertLetterNamed(sent.getValue().letterNumber(), sent.getValue().letterUrl(), transaction.id());
    }

    /** The email names the letter this sale issued, and links to it in the buyer's document vault. */
    private void assertLetterNamed(String letterNumber, String letterUrl, UUID transactionId) {
        String where = " FROM allocation_letters WHERE transaction_id = '" + transactionId + "'";
        assertThat(letterNumber).isEqualTo(queryString("SELECT number" + where));
        assertThat(letterUrl).matches("https?://[^/]+/documents\\?letter=" + queryString("SELECT id" + where));
    }

    @Test
    void rejectingEmailsTheBuyerOnceWithTheReasonAndALinkToThePurchase() {
        TransactionDto transaction = paidPurchase();

        assertThat(financeAction(staffWithRole(seller.id(), "finance_officer", null), transaction.id(), "reject",
                "{\"reason\":\"Charged back by the bank.\"}").getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<SettlementAlertSender.PurchaseDecisionAlert> sent =
                ArgumentCaptor.forClass(SettlementAlertSender.PurchaseDecisionAlert.class);
        verify(alerts, times(1)).purchaseDecided(sent.capture());
        assertThat(sent.getValue().to()).isEqualTo(buyer.email());
        assertThat(sent.getValue().verified()).isFalse();
        assertThat(sent.getValue().reason()).isEqualTo("Charged back by the bank.");
        assertThat(sent.getValue().letterNumber()).as("no letter for a rejected sale").isNull();
        assertThat(sent.getValue().letterUrl()).isNull();
        assertThat(new BigDecimal(sent.getValue().amount())).isEqualByComparingTo(TIER_PRICE);
        assertThat(sent.getValue().purchaseUrl()).as("the frontend origin from PAYSTACK_CALLBACK_URL, not its path")
                .matches("https?://[^/]+/purchases/" + transaction.id());
    }

    @Test
    void aRefusedDecisionEmailsNobody() {
        TransactionDto transaction = paidPurchase();
        String insider = alsoFinanceAtSeller(buyer);

        assertThat(financeAction(insider, transaction.id(), "verify", null).getBody()).contains("OWN_PURCHASE");
        assertThat(financeAction(insider, transaction.id(), "reject", "{\"reason\":\"Mine\"}").getBody())
                .contains("OWN_PURCHASE");
        verify(alerts, never()).purchaseDecided(any());

        String officer = staffWithRole(seller.id(), "finance_officer", null);
        assertThat(financeAction(officer, transaction.id(), "verify", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(financeAction(officer, transaction.id(), "verify", null).getBody())
                .contains("TRANSACTION_NOT_AWAITING_FINANCE");
        verify(alerts, times(1)).purchaseDecided(any());
    }

    @Test
    void aFailedEmailLeavesTheDecisionApplied() {
        TransactionDto verified = paidPurchase();
        doThrow(new IllegalStateException("SMTP down")).when(alerts).purchaseDecided(any());
        String officer = staffWithRole(seller.id(), "finance_officer", null);

        assertThat(financeAction(officer, verified.id(), "verify", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + verified.id() + "'")).isEqualTo("VERIFIED");
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).isEqualTo("SOLD");
    }

    @Test
    void aFailedEmailLeavesTheRejectionApplied() {
        TransactionDto transaction = paidPurchase();
        doThrow(new IllegalStateException("SMTP down")).when(alerts).purchaseDecided(any());

        assertThat(financeAction(staffWithRole(seller.id(), "finance_officer", null), transaction.id(), "reject",
                "{\"reason\":\"Charged back by the bank.\"}").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("REJECTED");
        assertThat(queryString("SELECT refund_requested_at IS NOT NULL FROM payments WHERE transaction_id = '"
                + transaction.id() + "' AND status = 'SUCCEEDED'")).isEqualTo("t");
    }

    // ------------------------------------------------------------------
    // Step 8b — paying the company out
    // ------------------------------------------------------------------

    /**
     * TR-1, the whole path: a verified sale is listed as owed, a payout
     * starts and waits for Paystack's code, the code finishes it. The amount
     * and account come from the sale and the approved account; the code is
     * never kept anywhere.
     */
    @Test
    void aVerifiedSaleIsPaidOutOnlyOnceTheCodeIsEnteredAndTheCodeIsNeverKept() {
        TransactionDto sale = verifiedSale();
        approvedPayoutAccount(seller.id(), "RCP_seller");
        String admin = payoutAdmin(true);
        String ready = get("/api/admin/payouts/ready", admin, String.class).getBody();
        assertThat(ready).contains(sale.id().toString()).contains("\"blockers\":[]");
        when(paystack.initiateTransfer(anyLong(), anyString(), anyString(), any()))
                .thenReturn(transfer("otp", "TRF_seller", "Transfer requires OTP to continue"));
        when(paystack.finalizeTransfer("TRF_seller", "123456"))
                .thenReturn(transfer("pending", "TRF_seller", "Transfer has been queued"));

        ResponseEntity<String> started = sendPayout(admin, sale.id());

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(started.getBody()).contains("\"status\":\"awaiting_otp\"");
        assertThat(started.getBody()).as("a single payout names its company too").contains("\"companyName\":\""
                + queryString("SELECT registered_name FROM organizations WHERE id = '" + seller.id() + "'") + "\"");
        ArgumentCaptor<String> reference = ArgumentCaptor.forClass(String.class);
        // The ₦20M sale is over the ₦10M cap: part 1 of 2, half the money.
        verify(paystack).initiateTransfer(eq(1_000_000_000L), eq("RCP_seller"), reference.capture(), any());
        assertThat(started.getBody()).contains("\"partNumber\":1").contains("\"partCount\":2");
        assertThat(reference.getValue()).matches("lv-payout-[0-9a-f]{32}");
        String payoutId = queryString("SELECT id FROM payouts WHERE transaction_id = '" + sale.id() + "'");

        ResponseEntity<String> finished = restTemplate.exchange("/api/admin/payouts/" + payoutId + "/otp",
                HttpMethod.POST, entity(admin, "{\"otp\":\"123456\"}"), String.class);

        assertThat(finished.getBody()).contains("\"status\":\"pending\"");
        assertThat(queryString("SELECT status || ':' || transfer_code FROM payouts WHERE id = '" + payoutId + "'"))
                .isEqualTo("PENDING:TRF_seller");
        assertThat(queryString("SELECT count(*) FROM payouts p, audit_log_entries a WHERE "
                + "p::text LIKE '%123456%' OR a.detail LIKE '%123456%'")).as("the code is stored nowhere").isEqualTo("0");
        assertThat(get("/api/admin/payouts/ready", admin, String.class).getBody())
                .as("a sale with a part in flight isn't offered again").doesNotContain(sale.id().toString());
    }

    /**
     * Paystack caps one transfer (₦10M), so the ₦20M sale is paid as two even
     * parts. The parts add up exactly to the sale, and once both are paid
     * there is nothing more to send.
     */
    @Test
    void aSaleAboveTheCapIsPaidInEvenPartsThatAddUpExactly() {
        TransactionDto sale = verifiedSale();
        approvedPayoutAccount(seller.id(), "RCP_seller");
        String admin = payoutAdmin(true);

        payPart(admin, sale.id(), "TRF_part1");
        String ready = get("/api/admin/payouts/ready", admin, String.class).getBody();
        assertThat(ready).contains("\"amountOwed\":10000000").contains("\"partsPaid\":1")
                .contains("\"nextPart\":2").contains("\"partCount\":2");
        payPart(admin, sale.id(), "TRF_part2");

        verify(paystack, times(2)).initiateTransfer(eq(1_000_000_000L), eq("RCP_seller"), anyString(), any());
        assertThat(queryString("SELECT sum(amount_kobo) FROM payouts WHERE status = 'SUCCESS' AND transaction_id = '"
                + sale.id() + "'")).isEqualTo("2000000000");
        assertThat(get("/api/admin/payouts/ready", admin, String.class).getBody()).doesNotContain(sale.id().toString());
        ResponseEntity<String> again = sendPayout(admin, sale.id());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("SALE_ALREADY_PAID");
    }

    /** A failed second part is retried alone — the paid first part is never sent again. */
    @Test
    void aFailedSecondPartIsRetriedAloneAndThePaidFirstPartIsNeverSentAgain() {
        TransactionDto sale = verifiedSale();
        approvedPayoutAccount(seller.id(), "RCP_seller");
        String admin = payoutAdmin(true);
        payPart(admin, sale.id(), "TRF_first");
        when(paystack.initiateTransfer(anyLong(), anyString(), anyString(), any()))
                .thenThrow(new PaymentException.GatewayRefused("Bank unavailable"))
                .thenReturn(transfer("otp", "TRF_second_retry", null));

        assertThat(sendPayout(admin, sale.id()).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        ResponseEntity<String> retry = sendPayout(admin, sale.id());

        assertThat(retry.getBody()).contains("\"partNumber\":2").contains("\"status\":\"awaiting_otp\"");
        assertThat(queryString("SELECT string_agg(part_number || ':' || status, ',' ORDER BY created_at) FROM payouts "
                + "WHERE transaction_id = '" + sale.id() + "'")).isEqualTo("1:SUCCESS,2:FAILED,2:AWAITING_OTP");
    }

    /** The split is fixed by the first part paid: raising the cap halfway can't change what the parts add up to. */
    @Test
    void theSplitStaysTheSameIfTheCapIsRaisedHalfway() {
        TransactionDto sale = verifiedSale();
        approvedPayoutAccount(seller.id(), "RCP_seller");
        String admin = payoutAdmin(true);
        payPart(admin, sale.id(), "TRF_before");
        ReflectionTestUtils.setField(payoutRecorder, "maxTransferAmount", new BigDecimal("50000000"));
        try {
            when(paystack.initiateTransfer(anyLong(), anyString(), anyString(), any()))
                    .thenReturn(transfer("otp", "TRF_after", null));

            ResponseEntity<String> part2 = sendPayout(admin, sale.id());

            assertThat(part2.getBody()).contains("\"partNumber\":2").contains("\"partCount\":2")
                    .contains("\"amount\":10000000");
        } finally {
            ReflectionTestUtils.setField(payoutRecorder, "maxTransferAmount", new BigDecimal("10000000"));
        }
    }

    /** Decided with the user: the login that moves money must have its own second factor. */
    @Test
    void withoutTwoFactorOnTheSendersAccountNothingIsSent() {
        TransactionDto sale = verifiedSale();
        approvedPayoutAccount(seller.id(), "RCP_seller");

        ResponseEntity<String> refused = sendPayout(payoutAdmin(false), sale.id());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).contains("TWO_FACTOR_REQUIRED");
        verify(paystack, never()).initiateTransfer(anyLong(), anyString(), anyString(), any());
        assertThat(queryString("SELECT count(*) FROM payouts WHERE transaction_id = '" + sale.id() + "'")).isEqualTo("0");
    }

    @Test
    void aSaleCanNeverBePaidTwice() {
        TransactionDto sale = verifiedSale();
        approvedPayoutAccount(seller.id(), "RCP_seller");
        String admin = payoutAdmin(true);
        when(paystack.initiateTransfer(anyLong(), anyString(), anyString(), any()))
                .thenReturn(transfer("otp", "TRF_once", null));

        sendPayout(admin, sale.id());
        ResponseEntity<String> again = sendPayout(admin, sale.id());

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("PAYOUT_IN_PROGRESS");
        verify(paystack, times(1)).initiateTransfer(anyLong(), anyString(), anyString(), any());
    }

    /**
     * Decision 1: a lost reply leaves the payout {@code sending}. Sending again
     * asks Paystack about the SAME reference first and adopts its answer — it
     * never starts a second transfer.
     */
    @Test
    void aLostReplyIsResolvedByAskingPaystackAboutTheSameReference() {
        TransactionDto sale = verifiedSale();
        approvedPayoutAccount(seller.id(), "RCP_seller");
        String admin = payoutAdmin(true);
        when(paystack.initiateTransfer(anyLong(), anyString(), anyString(), any()))
                .thenThrow(new PaymentException.GatewayUnavailable());

        ResponseEntity<String> lost = sendPayout(admin, sale.id());

        assertThat(lost.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(lost.getBody()).contains("PAYOUT_OUTCOME_UNKNOWN");
        String reference = queryString("SELECT reference FROM payouts WHERE transaction_id = '" + sale.id()
                + "' AND status = 'SENDING'");
        assertThat(reference).as("kept, not failed").isNotNull();
        when(paystack.verifyTransfer(reference)).thenReturn(transfer("otp", "TRF_found", null));

        ResponseEntity<String> resent = sendPayout(admin, sale.id());

        assertThat(resent.getBody()).contains("\"status\":\"awaiting_otp\"").contains(reference);
        verify(paystack, times(1)).initiateTransfer(anyLong(), anyString(), anyString(), any());
        assertThat(queryString("SELECT count(*) FROM payouts WHERE transaction_id = '" + sale.id() + "'")).isEqualTo("1");
    }

    /** If Paystack never received it, the resend uses the same reference again — never a fresh one. */
    @Test
    void aResendThatPaystackNeverSawReusesTheReference() {
        TransactionDto sale = verifiedSale();
        approvedPayoutAccount(seller.id(), "RCP_seller");
        String admin = payoutAdmin(true);
        when(paystack.initiateTransfer(anyLong(), anyString(), anyString(), any()))
                .thenThrow(new PaymentException.GatewayUnavailable())
                .thenReturn(transfer("otp", "TRF_second", null));
        when(paystack.verifyTransfer(anyString())).thenReturn(new PaystackClient.Transfer(false, null, null, null, null, null));

        sendPayout(admin, sale.id());
        sendPayout(admin, sale.id());

        ArgumentCaptor<String> references = ArgumentCaptor.forClass(String.class);
        verify(paystack, times(2)).initiateTransfer(anyLong(), anyString(), references.capture(), any());
        assertThat(references.getAllValues().get(1)).isEqualTo(references.getAllValues().get(0));
    }

    /** Paystack refused to start it (e.g. balance too low): no money moved, so the attempt fails and the sale is owed again. */
    @Test
    void aRefusalFailsTheAttemptKeepsItAndTheSaleIsOwedAgain() {
        TransactionDto sale = verifiedSale();
        approvedPayoutAccount(seller.id(), "RCP_seller");
        String admin = payoutAdmin(true);
        when(paystack.initiateTransfer(anyLong(), anyString(), anyString(), any()))
                .thenThrow(new PaymentException.GatewayRefused("Your balance is not enough to fulfil this request"))
                .thenReturn(transfer("otp", "TRF_retry", null));

        ResponseEntity<String> refused = sendPayout(admin, sale.id());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(refused.getBody()).contains("balance is not enough");
        assertThat(refused.getBody()).as("our prefix once, never twice")
                .contains("\"message\":\"Paystack refused the request: Your balance");
        assertThat(queryString("SELECT gateway_message FROM payouts WHERE transaction_id = '" + sale.id() + "'"))
                .as("Paystack's own words are what's stored").isEqualTo("Your balance is not enough to fulfil this request");
        assertThat(get("/api/admin/payouts/ready", admin, String.class).getBody())
                .contains(sale.id().toString()).contains("\"status\":\"failed\"");

        sendPayout(admin, sale.id());
        assertThat(queryString("SELECT string_agg(status, ',' ORDER BY created_at) FROM payouts WHERE transaction_id = '"
                + sale.id() + "'")).as("history kept; the retry is a new row").isEqualTo("FAILED,AWAITING_OTP");
        assertThat(queryString("SELECT count(DISTINCT reference) FROM payouts WHERE transaction_id = '" + sale.id() + "'"))
                .isEqualTo("2");
    }

    @Test
    void aWrongCodeLeavesThePayoutWaiting() {
        TransactionDto sale = verifiedSale();
        approvedPayoutAccount(seller.id(), "RCP_seller");
        String admin = payoutAdmin(true);
        when(paystack.initiateTransfer(anyLong(), anyString(), anyString(), any()))
                .thenReturn(transfer("otp", "TRF_wrong", null));
        when(paystack.finalizeTransfer("TRF_wrong", "000000"))
                .thenThrow(new PaymentException.GatewayRefused("Invalid OTP"));
        when(paystack.verifyTransfer(anyString())).thenReturn(transfer("otp", "TRF_wrong", null));
        sendPayout(admin, sale.id());
        String payoutId = queryString("SELECT id FROM payouts WHERE transaction_id = '" + sale.id() + "'");

        ResponseEntity<String> wrong = restTemplate.exchange("/api/admin/payouts/" + payoutId + "/otp",
                HttpMethod.POST, entity(admin, "{\"otp\":\"000000\"}"), String.class);

        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(wrong.getBody()).contains("OTP_REJECTED").contains("Invalid OTP");
        assertThat(queryString("SELECT status FROM payouts WHERE id = '" + payoutId + "'")).isEqualTo("AWAITING_OTP");
    }

    /** Decision 4: cancelling before the code is entered sends nothing and frees the sale. */
    @Test
    void cancellingBeforeTheCodeSendsNothingAndTheSaleIsOwedAgain() {
        TransactionDto sale = verifiedSale();
        approvedPayoutAccount(seller.id(), "RCP_seller");
        String admin = payoutAdmin(true);
        when(paystack.initiateTransfer(anyLong(), anyString(), anyString(), any()))
                .thenReturn(transfer("otp", "TRF_cancel", null));
        sendPayout(admin, sale.id());
        String payoutId = queryString("SELECT id FROM payouts WHERE transaction_id = '" + sale.id() + "'");

        ResponseEntity<String> cancelled = restTemplate.exchange("/api/admin/payouts/" + payoutId + "/cancel",
                HttpMethod.POST, entity(admin, null), String.class);

        assertThat(cancelled.getBody()).contains("\"status\":\"cancelled\"");
        verify(paystack, never()).finalizeTransfer(anyString(), anyString());
        assertThat(get("/api/admin/payouts/ready", admin, String.class).getBody()).contains(sale.id().toString());
    }

    /** Decision 3, and no account: both hold the payout, and say why. */
    @Test
    void aCompanyWithNoApprovedAccountOrThatIsSuspendedIsHeld() {
        TransactionDto sale = verifiedSale();
        String admin = payoutAdmin(true);

        assertThat(get("/api/admin/payouts/ready", admin, String.class).getBody())
                .contains("NO_APPROVED_PAYOUT_ACCOUNT");
        assertThat(sendPayout(admin, sale.id()).getBody()).contains("NO_APPROVED_PAYOUT_ACCOUNT");

        approvedPayoutAccount(seller.id(), "RCP_seller");
        execute("UPDATE organizations SET status = 'SUSPENDED' WHERE id = '" + seller.id() + "'");
        try {
            assertThat(get("/api/admin/payouts/ready", admin, String.class).getBody()).contains("COMPANY_NOT_ACTIVE");
            assertThat(sendPayout(admin, sale.id()).getBody()).contains("COMPANY_NOT_ACTIVE");
        } finally {
            execute("UPDATE organizations SET status = 'ACTIVE' WHERE id = '" + seller.id() + "'");
        }
        verify(paystack, never()).initiateTransfer(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void onlyAVerifiedSaleCanBePaidOut() {
        TransactionDto paidNotVerified = paidPurchase();
        approvedPayoutAccount(seller.id(), "RCP_seller");

        ResponseEntity<String> refused = sendPayout(payoutAdmin(true), paidNotVerified.id());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(refused.getBody()).contains("SALE_NOT_PAYABLE");
    }

    // ------------------------------------------------------------------
    // Step 8c — how a payout actually ended (TR-3)
    // ------------------------------------------------------------------

    /** A transfer webhook prompts a check; Paystack's verified answer is what is recorded. */
    @Test
    void aTransferWebhookPromptsACheckAndPaystacksVerifiedAnswerIsRecorded() {
        String reference = pendingPayout("TRF_hook");
        when(paystack.verifyTransfer(reference)).thenReturn(new PaystackClient.Transfer(true, "success", "TRF_hook",
                reference, "Transfer successful", Instant.parse("2026-10-09T12:00:00Z")));

        assertThat(transferWebhook("transfer.success", reference).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(queryString("SELECT status || ' ' || transferred_at FROM payouts WHERE reference = '" + reference + "'"))
                .startsWith("SUCCESS 2026-10-09");
    }

    /** The message itself is never trusted: if Paystack's verify disagrees, nothing changes. */
    @Test
    void aWebhookPaystackDoesntBackUpChangesNothing() {
        String reference = pendingPayout("TRF_claim");
        when(paystack.verifyTransfer(reference)).thenReturn(transfer("pending", "TRF_claim", null));

        transferWebhook("transfer.success", reference);

        assertThat(queryString("SELECT status FROM payouts WHERE reference = '" + reference + "'")).isEqualTo("PENDING");
    }

    /** Decision 2: Paystack's latest word wins — a reversal after success makes the part owed again, and a person is told. */
    @Test
    void aReversalAfterSuccessMakesThePartOwedAgainAndTellsTheSuperAdmins() {
        TransactionDto sale = verifiedSale();
        approvedPayoutAccount(seller.id(), "RCP_seller");
        String admin = payoutAdmin(true);
        payPart(admin, sale.id(), "TRF_reversed");
        String reference = queryString("SELECT reference FROM payouts WHERE transfer_code = 'TRF_reversed'");
        when(paystack.verifyTransfer(reference)).thenReturn(transfer("reversed", "TRF_reversed", "Account frozen"));

        transferWebhook("transfer.reversed", reference);

        assertThat(queryString("SELECT status FROM payouts WHERE reference = '" + reference + "'")).isEqualTo("REVERSED");
        String ready = get("/api/admin/payouts/ready", admin, String.class).getBody();
        assertThat(ready).contains(sale.id().toString()).contains("\"partsPaid\":0").contains("\"nextPart\":1");
        verify(alerts, atLeastOnce()).payoutProblem(any());
    }

    /** A lost webhook: the sweep asks Paystack about a payout pending too long. */
    @Test
    void theSweepCatchesAPayoutWhoseWebhookWasLost() {
        String reference = pendingPayout("TRF_lost");
        execute("UPDATE payouts SET updated_at = now() - interval '1 hour' WHERE reference = '" + reference + "'");
        when(paystack.verifyTransfer(reference)).thenReturn(transfer("success", "TRF_lost", null));

        payoutSweeper.sweep();

        assertThat(queryString("SELECT status FROM payouts WHERE reference = '" + reference + "'")).isEqualTo("SUCCESS");
    }

    /** A payout whose request never reached Paystack fails after the threshold: no money moved, the part is owed again. */
    @Test
    void aPayoutPaystackNeverReceivedFailsAfterTheThreshold() {
        TransactionDto sale = verifiedSale();
        approvedPayoutAccount(seller.id(), "RCP_seller");
        when(paystack.initiateTransfer(anyLong(), anyString(), anyString(), any()))
                .thenThrow(new PaymentException.GatewayUnavailable());
        sendPayout(payoutAdmin(true), sale.id());
        String reference = queryString("SELECT reference FROM payouts WHERE transaction_id = '" + sale.id() + "'");
        when(paystack.verifyTransfer(reference)).thenReturn(new PaystackClient.Transfer(false, null, null, reference, null, null));

        payoutSweeper.sweep();
        assertThat(queryString("SELECT status FROM payouts WHERE reference = '" + reference + "'"))
                .as("too recent to judge").isEqualTo("SENDING");

        execute("UPDATE payouts SET created_at = now() - interval '1 hour' WHERE reference = '" + reference + "'");
        payoutSweeper.sweep();
        assertThat(queryString("SELECT status FROM payouts WHERE reference = '" + reference + "'")).isEqualTo("FAILED");
    }

    /**
     * The rare case: an attempt we marked failed turns out to have paid, while
     * a retry for the same part is live. The part may have been paid twice —
     * the old row is left (one live attempt per part) and flagged loudly.
     */
    @Test
    void moneyThatWentAfterAFailureIsFlaggedWhenARetryExists() {
        TransactionDto sale = verifiedSale();
        approvedPayoutAccount(seller.id(), "RCP_seller");
        String admin = payoutAdmin(true);
        when(paystack.initiateTransfer(anyLong(), anyString(), anyString(), any()))
                .thenThrow(new PaymentException.GatewayRefused("Temporary error"))
                .thenReturn(transfer("otp", "TRF_retry_live", null));
        sendPayout(admin, sale.id());
        String failed = queryString("SELECT reference FROM payouts WHERE status = 'FAILED' AND transaction_id = '" + sale.id() + "'");
        sendPayout(admin, sale.id());
        when(paystack.verifyTransfer(failed)).thenReturn(transfer("success", "TRF_ghost", null));

        transferWebhook("transfer.success", failed);

        assertThat(queryString("SELECT status FROM payouts WHERE reference = '" + failed + "'")).isEqualTo("FAILED");
        assertThat(queryString("SELECT review_reason FROM payouts WHERE reference = '" + failed + "'"))
                .contains("may have been paid twice");
        verify(alerts, atLeastOnce()).payoutProblem(any());
    }

    /** TR-3: the company's finance staff see their own payouts — and nobody else's. */
    @Test
    void aCompanySeesItsOwnPayoutsAndNoOneElses() {
        String reference = pendingPayout("TRF_mine");
        String finance = staffWithRole(seller.id(), "finance_officer", null);

        assertThat(get("/api/portal/payouts", finance, String.class).getBody()).contains(reference);

        Tenant other = verifiedTenant();
        String elsewhere = staffWithRole(other.id(), "finance_officer", null);
        assertThat(get("/api/portal/payouts", elsewhere, String.class).getBody()).isEqualTo("[]");
    }

    /**
     * The Super Admin's payout history names each payout's company, so a role
     * without the tenant directory still sees whose money it was — looked up
     * for the whole list at once, never one company per row.
     */
    @Test
    void payoutsNameTheirCompanyLookedUpInOneBatch() {
        String mine = pendingPayout("TRF_named");
        Tenant other = verifiedTenant();
        String theirs = "lv-payout-" + UUID.randomUUID().toString().replace("-", "");
        execute("INSERT INTO payouts (id, tenant_id, created_at, deleted, transaction_id, settlement_account_id, "
                + "recipient_code, amount, currency, amount_kobo, reference, status, gateway_message, initiated_by, "
                + "part_number, part_count) SELECT gen_random_uuid(), '" + other.id() + "', now(), false, "
                + "transaction_id, settlement_account_id, recipient_code, amount, currency, amount_kobo, '" + theirs
                + "', 'FAILED', 'Insufficient balance', initiated_by, part_number, part_count "
                + "FROM payouts WHERE reference = '" + mine + "'");
        String sellerName = queryString("SELECT registered_name FROM organizations WHERE id = '" + seller.id() + "'");
        String otherName = queryString("SELECT registered_name FROM organizations WHERE id = '" + other.id() + "'");
        String admin = payoutAdmin(false);
        clearInvocations(tenancyApi);

        List<PayoutDto> all = restTemplate.exchange("/api/admin/payouts", HttpMethod.GET,
                new HttpEntity<>(bearer(admin)), new ParameterizedTypeReference<List<PayoutDto>>() {
                }).getBody();

        assertThat(all).filteredOn(p -> p.reference().equals(mine)).singleElement()
                .extracting(PayoutDto::companyName).isEqualTo(sellerName);
        assertThat(all).filteredOn(p -> p.reference().equals(theirs)).singleElement()
                .extracting(PayoutDto::companyName).isEqualTo(otherName);
        verify(tenancyApi, times(1)).organizationNamesFor(any());

        String finance = staffWithRole(seller.id(), "finance_officer", null);
        List<PayoutDto> own = restTemplate.exchange("/api/portal/payouts", HttpMethod.GET,
                new HttpEntity<>(bearer(finance)), new ParameterizedTypeReference<List<PayoutDto>>() {
                }).getBody();
        assertThat(own).isNotEmpty().extracting(PayoutDto::companyName).containsOnly(sellerName);
    }

    /** A part of a verified sale, accepted by Paystack and not yet finished. */
    private String pendingPayout(String transferCode) {
        TransactionDto sale = verifiedSale();
        approvedPayoutAccount(seller.id(), "RCP_seller");
        String admin = payoutAdmin(true);
        when(paystack.initiateTransfer(anyLong(), anyString(), anyString(), any()))
                .thenReturn(transfer("otp", transferCode, null));
        when(paystack.finalizeTransfer(transferCode, "123456")).thenReturn(transfer("pending", transferCode, null));
        sendPayout(admin, sale.id());
        String payoutId = queryString("SELECT id FROM payouts WHERE transfer_code = '" + transferCode + "'");
        restTemplate.exchange("/api/admin/payouts/" + payoutId + "/otp", HttpMethod.POST,
                entity(admin, "{\"otp\":\"123456\"}"), String.class);
        assertThat(queryString("SELECT status FROM payouts WHERE id = '" + payoutId + "'")).isEqualTo("PENDING");
        return queryString("SELECT reference FROM payouts WHERE id = '" + payoutId + "'");
    }

    private ResponseEntity<String> transferWebhook(String event, String reference) {
        String body = "{\"event\":\"" + event + "\",\"data\":{\"reference\":\"" + reference + "\"}}";
        return webhook(body, PaystackSignature.sign(body.getBytes(StandardCharsets.UTF_8), paystackSecretKey));
    }

    // ------------------------------------------------------------------
    // Refunds — money owed back to a buyer who never got the plot
    // ------------------------------------------------------------------

    /** Finance rejected a paid purchase: it is owed back, in full, once. */
    @Test
    void aRejectedPaymentIsRefundedInFullAndOnlyOnce() {
        String reference = rejectedPayment();
        String admin = payoutAdmin(true);
        assertThat(get("/api/admin/refunds/due", admin, String.class).getBody())
                .contains(reference).contains("\"amount\":20000000");
        when(paystack.createRefund(eq(reference), anyLong(), any(), any()))
                .thenReturn(new PaystackClient.Refund("R1", "pending", "Refund has been queued for processing", null, null));

        ResponseEntity<String> started = startRefund(admin, reference);
        ResponseEntity<String> again = startRefund(admin, reference);

        assertThat(started.getBody()).contains("\"status\":\"pending\"");
        verify(paystack, times(1)).createRefund(eq(reference), eq(2_000_000_000L), any(), any());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("REFUND_IN_PROGRESS");
        assertThat(get("/api/admin/refunds/due", admin, String.class).getBody()).doesNotContain(reference);
    }

    @Test
    void withoutTwoFactorNoRefundIsSent() {
        String reference = rejectedPayment();

        ResponseEntity<String> refused = startRefund(payoutAdmin(false), reference);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(paystack, never()).createRefund(anyString(), anyLong(), any(), any());
        assertThat(queryString("SELECT count(*) FROM refunds r JOIN payments p ON p.id = r.payment_id "
                + "WHERE p.reference = '" + reference + "'")).isEqualTo("0");
    }

    @Test
    void aPaymentNobodyOwesBackCannotBeRefunded() {
        TransactionDto paid = paidPurchase();
        String reference = queryString("SELECT reference FROM payments WHERE transaction_id = '" + paid.id() + "'");

        ResponseEntity<String> refused = startRefund(payoutAdmin(true), reference);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(refused.getBody()).contains("REFUND_NOT_DUE");
    }

    /**
     * Decision 3: Paystack can't return a bank-transfer payment on its own.
     * The buyer gives an account in their own name; the bank supplies the
     * name and the reviewer sees whether it matches; a Super Admin sends it.
     */
    @Test
    void aBankTransferRefundGoesToTheAccountTheBuyerGives() {
        String reference = rejectedPayment();
        String admin = payoutAdmin(true);
        when(paystack.createRefund(eq(reference), anyLong(), any(), any()))
                .thenReturn(new PaystackClient.Refund("R2", "needs-attention", "Refund queued", null, null));
        when(paystack.listBanks()).thenReturn(List.of(new PaystackClient.Bank("058", "Guaranty Trust Bank", "9")));
        when(paystack.resolveAccountName("0123456789", "058")).thenReturn(Optional.of("BUYER ADA CHIOMA"));
        when(paystack.retryRefundWithAccount("R2", "0123456789", "9"))
                .thenReturn(new PaystackClient.Refund("R2", "processing", "Refund retried", null, null));
        String refundId = idOf(startRefund(admin, reference).getBody());
        verify(alerts).refundNeedsAccount(any());

        assertThat(get("/api/payments/" + reference + "/refund", buyer.token(), String.class).getBody())
                .contains("\"needsAccount\":true");
        Buyer stranger = verifiedBuyer();
        assertThat(restTemplate.exchange("/api/payments/" + reference + "/refund-account", HttpMethod.POST,
                entity(stranger.token(), "{\"bankCode\":\"058\",\"accountNumber\":\"0123456789\"}"),
                String.class).getStatusCode()).as("someone else's payment").isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> given = restTemplate.exchange("/api/payments/" + reference + "/refund-account",
                HttpMethod.POST, entity(buyer.token(), "{\"bankCode\":\"058\",\"accountNumber\":\"0123456789\"}"),
                String.class);
        assertThat(given.getStatusCode()).isEqualTo(HttpStatus.OK);

        String review = get("/api/admin/refunds/" + refundId, admin, String.class).getBody();
        assertThat(review).contains("\"accountName\":\"BUYER ADA CHIOMA\"").contains("\"accountNameMatchesBuyer\":true");
        ResponseEntity<String> sent = restTemplate.exchange("/api/admin/refunds/" + refundId + "/send-to-account",
                HttpMethod.POST, entity(admin, null), String.class);

        assertThat(sent.getBody()).contains("\"status\":\"processing\"");
        verify(paystack).retryRefundWithAccount("R2", "0123456789", "9");
    }

    /** A refund webhook prompts a check; once processed the payment is no longer owed and the buyer sees it. */
    @Test
    void aProcessedRefundClearsWhatIsOwedAndTheBuyerSeesIt() {
        String reference = rejectedPayment();
        String admin = payoutAdmin(true);
        when(paystack.createRefund(eq(reference), anyLong(), any(), any()))
                .thenReturn(new PaystackClient.Refund("R3", "pending", null, null, null));
        startRefund(admin, reference);
        when(paystack.fetchRefund("R3")).thenReturn(new PaystackClient.Refund("R3", "processed", null,
                Instant.parse("2026-10-09T15:00:00Z"), null));

        String body = "{\"event\":\"refund.processed\",\"data\":{\"status\":\"processed\","
                + "\"transaction_reference\":\"" + reference + "\",\"amount\":2000000000}}";
        assertThat(webhook(body, PaystackSignature.sign(body.getBytes(StandardCharsets.UTF_8), paystackSecretKey))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(queryString("SELECT status FROM refunds WHERE paystack_refund_id = 'R3'")).isEqualTo("PROCESSED");
        assertThat(queryString("SELECT review_reason IS NULL FROM payments WHERE reference = '" + reference + "'"))
                .isEqualTo("t");
        assertThat(get("/api/payments/" + reference + "/refund", buyer.token(), String.class).getBody())
                .contains("\"status\":\"processed\"");
    }

    /** The webhook's own word is never taken: Paystack's fetched refund decides. */
    @Test
    void aRefundWebhookPaystackDoesntBackUpChangesNothing() {
        String reference = rejectedPayment();
        when(paystack.createRefund(eq(reference), anyLong(), any(), any()))
                .thenReturn(new PaystackClient.Refund("R4", "pending", null, null, null));
        startRefund(payoutAdmin(true), reference);
        when(paystack.fetchRefund("R4")).thenReturn(new PaystackClient.Refund("R4", "pending", null, null, null));

        String body = "{\"event\":\"refund.processed\",\"data\":{\"transaction_reference\":\"" + reference + "\"}}";
        webhook(body, PaystackSignature.sign(body.getBytes(StandardCharsets.UTF_8), paystackSecretKey));

        assertThat(queryString("SELECT status FROM refunds WHERE paystack_refund_id = 'R4'")).isEqualTo("PENDING");
    }

    /** Paystack refused: nothing moved, the refund fails, and the payment is owed again — a new attempt is allowed. */
    @Test
    void aRefusedRefundCanBeTriedAgain() {
        String reference = rejectedPayment();
        String admin = payoutAdmin(true);
        when(paystack.createRefund(eq(reference), anyLong(), any(), any()))
                .thenThrow(new PaymentException.GatewayRefused("Transaction cannot be refunded at this time"))
                .thenReturn(new PaystackClient.Refund("R5", "pending", null, null, null));

        assertThat(startRefund(admin, reference).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(get("/api/admin/refunds/due", admin, String.class).getBody()).contains(reference)
                .contains("\"status\":\"failed\"");
        assertThat(startRefund(admin, reference).getBody()).contains("\"status\":\"pending\"");
        assertThat(queryString("SELECT string_agg(r.status, ',' ORDER BY r.created_at) FROM refunds r "
                + "JOIN payments p ON p.id = r.payment_id WHERE p.reference = '" + reference + "'")).isEqualTo("FAILED,PENDING");
    }

    /** A lost refund webhook: the sweep asks Paystack about a refund pending too long. */
    @Test
    void theRefundSweepCatchesALostWebhook() {
        String reference = rejectedPayment();
        when(paystack.createRefund(eq(reference), anyLong(), any(), any()))
                .thenReturn(new PaystackClient.Refund("R6", "pending", null, null, null));
        startRefund(payoutAdmin(true), reference);
        execute("UPDATE refunds SET updated_at = now() - interval '2 hours' WHERE paystack_refund_id = 'R6'");
        when(paystack.fetchRefund("R6")).thenReturn(new PaystackClient.Refund("R6", "processed", null, null, null));

        refundSweeper.sweep();

        assertThat(queryString("SELECT status FROM refunds WHERE paystack_refund_id = 'R6'")).isEqualTo("PROCESSED");
    }

    private String rejectedPayment() {
        TransactionDto transaction = paidPurchase();
        assertThat(financeAction(seller.token(), transaction.id(), "reject", "{\"reason\":\"Buyer asked to cancel\"}")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return queryString("SELECT reference FROM payments WHERE transaction_id = '" + transaction.id() + "'");
    }

    private ResponseEntity<String> startRefund(String token, String paymentReference) {
        return restTemplate.exchange("/api/admin/refunds", HttpMethod.POST,
                entity(token, "{\"paymentReference\":\"" + paymentReference + "\"}"), String.class);
    }

    private String idOf(String json) {
        return objectMapper.readTree(json).path("id").asString();
    }

    // ------------------------------------------------------------------
    // Late money — paid after the purchase was abandoned
    // ------------------------------------------------------------------

    /** The plot is still for sale: finance allocates it — sold and verified together — and it becomes payable. */
    @Test
    void lateMoneyForAPlotStillForSaleIsAllocatedAndBecomesPayable() {
        TransactionDto transaction = latePayment();
        String officer = staffWithRole(seller.id(), "finance_officer", null);
        String list = get("/api/portal/finance/late-payments", officer, String.class).getBody();
        assertThat(list).contains(transaction.id().toString()).contains("\"plotAvailable\":true")
                .contains("\"amountsMatch\":true");

        ResponseEntity<String> allocated = restTemplate.exchange("/api/portal/finance/late-payments/"
                + transaction.id() + "/allocate", HttpMethod.POST, entity(officer, null), String.class);

        assertThat(allocated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).isEqualTo("SOLD");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("VERIFIED");
        assertThat(queryString("SELECT review_reason IS NULL FROM payments WHERE transaction_id = '" + transaction.id()
                + "' AND status = 'SUCCEEDED'")).isEqualTo("t");
        assertThat(get("/api/portal/finance/late-payments", officer, String.class).getBody())
                .doesNotContain(transaction.id().toString());
        assertThat(get("/api/admin/payouts/ready", payoutAdmin(true), String.class).getBody())
                .as("now owed to the developer like any verified sale").contains(transaction.id().toString());
        assertThat(queryString("SELECT count(*) FROM allocation_letters WHERE transaction_id = '" + transaction.id() + "'"))
                .as("the letter comes with the allocation").isEqualTo("1");
        assertThat(allocated.getBody()).contains("\"allocationLetterNumber\":\"" + queryString(
                "SELECT number FROM allocation_letters WHERE transaction_id = '" + transaction.id() + "'") + "\"");
        ArgumentCaptor<SettlementAlertSender.LatePaymentAlert> sent =
                ArgumentCaptor.forClass(SettlementAlertSender.LatePaymentAlert.class);
        verify(alerts).latePaymentResolved(sent.capture());
        assertThat(sent.getValue().allocated()).isTrue();
        assertLetterNamed(sent.getValue().letterNumber(), sent.getValue().letterUrl(), transaction.id());
    }

    /** Someone else reserved the plot meanwhile: allocating changes nothing; refund is the only ending. */
    @Test
    void lateMoneyForATakenPlotCannotBeAllocatedOnlyRefunded() {
        TransactionDto transaction = latePayment();
        String officer = staffWithRole(seller.id(), "finance_officer", null);
        reserve(verifiedBuyer(), plotId);

        assertThat(get("/api/portal/finance/late-payments", officer, String.class).getBody())
                .contains("\"plotAvailable\":false");
        ResponseEntity<String> refused = restTemplate.exchange("/api/portal/finance/late-payments/"
                + transaction.id() + "/allocate", HttpMethod.POST, entity(officer, null), String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("PLOT_NO_LONGER_AVAILABLE");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'"))
                .as("nothing changed").isEqualTo("ABANDONED");
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).isEqualTo("RESERVED");

        ResponseEntity<String> refund = restTemplate.exchange("/api/portal/finance/late-payments/" + transaction.id()
                + "/refund", HttpMethod.POST, entity(officer, "{\"reason\":\"The plot was sold to someone else\"}"),
                String.class);

        assertThat(refund.getStatusCode()).isEqualTo(HttpStatus.OK);
        String reference = queryString("SELECT reference FROM payments WHERE transaction_id = '" + transaction.id()
                + "' AND status = 'SUCCEEDED'");
        assertThat(get("/api/admin/refunds/due", payoutAdmin(true), String.class).getBody()).contains(reference);
        assertThat(get("/api/portal/finance/late-payments", officer, String.class).getBody())
                .doesNotContain(transaction.id().toString());
    }

    /** Another company's finance can neither see nor act on it. */
    @Test
    void anotherCompanysFinanceCannotSeeOrResolveLateMoney() {
        TransactionDto transaction = latePayment();
        Tenant other = verifiedTenant();
        String elsewhere = staffWithRole(other.id(), "finance_officer", null);

        assertThat(get("/api/portal/finance/late-payments", elsewhere, String.class).getBody()).isEqualTo("[]");
        assertThat(restTemplate.exchange("/api/portal/finance/late-payments/" + transaction.id() + "/allocate",
                HttpMethod.POST, entity(elsewhere, null), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("ABANDONED");
    }

    /** A purchase abandoned, then paid in full after the plot had gone back on sale. */
    private TransactionDto latePayment() {
        TransactionDto transaction = outrightTransaction();
        String reference = startPayment(transaction);
        paystackSays(reference, "abandoned", 2_000_000_000L, "NGN", "The customer left");
        holdEndedMinutesAgo(transaction, 20);
        paymentSweeper.sweep();
        paystackSays(reference, "success", 2_000_000_000L, "NGN", "Approved");
        String body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"" + reference + "\"}}";
        webhook(body, PaystackSignature.sign(body.getBytes(StandardCharsets.UTF_8), paystackSecretKey));
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("ABANDONED");
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).startsWith("AVAILABLE");
        return transaction;
    }

    /** Every payments error is in the standard shape — including input that fails the request's own rules. */
    @Test
    void badInputGetsTheStandardErrorShapeWithTheFieldThatFailed() {
        TransactionDto transaction = paidPurchase();
        String officer = staffWithRole(seller.id(), "finance_officer", null);

        ResponseEntity<String> blankReason = financeAction(officer, transaction.id(), "reject", "{\"reason\":\"\"}");
        assertThat(blankReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blankReason.getBody()).contains("\"code\":\"VALIDATION_ERROR\"").contains("\"reason\":");

        ResponseEntity<String> shortAccount = restTemplate.exchange("/api/portal/settlement/account", HttpMethod.POST,
                entity(seller.token(), "{\"bankCode\":\"058\",\"accountNumber\":\"012345678\"}"), String.class);
        assertThat(shortAccount.getBody()).contains("\"code\":\"VALIDATION_ERROR\"").contains("\"accountNumber\":");

        ResponseEntity<String> notJson = financeAction(officer, transaction.id(), "reject", "{not json");
        assertThat(notJson.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(notJson.getBody()).contains("\"code\":\"MALFORMED_REQUEST\"");

        ResponseEntity<String> badId = restTemplate.exchange("/api/portal/finance/transactions/not-a-uuid/verify",
                HttpMethod.POST, entity(officer, null), String.class);
        assertThat(badId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badId.getBody()).contains("\"code\":\"INVALID_PARAMETER\"");

        // Checkout, which the buyer's purchase flow also calls, answers the same way.
        ResponseEntity<String> badReservation = restTemplate.exchange("/api/reservations", HttpMethod.POST,
                entity(buyer.token(), "{not json"), String.class);
        assertThat(badReservation.getBody()).contains("\"code\":\"MALFORMED_REQUEST\"");
        assertThat(restTemplate.exchange("/api/checkout/transactions/not-a-uuid", HttpMethod.GET,
                entity(buyer.token(), null), String.class).getBody()).contains("\"code\":\"INVALID_PARAMETER\"");
    }

    // ------------------------------------------------------------------
    // "My purchases" — a buyer finds their payment and refund from any device
    // ------------------------------------------------------------------

    @Test
    void myPurchasesAreMineOnlyNewestFirstPagedAndCarryThePaymentSummary() {
        TransactionDto unpaid = outrightTransaction();
        assertThat(unpaid.payment()).as("nothing paid yet").isNull();
        String reference = startPayment(unpaid);
        paystackSays(reference, "success", 2_000_000_000L, "NGN", "Approved");
        confirm(buyer, reference);

        String mine = get("/api/checkout/transactions/mine", buyer.token(), String.class).getBody();
        assertThat(mine).contains(unpaid.id().toString()).contains("\"reference\":\"" + reference + "\"")
                .contains("\"status\":\"succeeded\"").contains("\"underReview\":false")
                .contains("\"items\":").contains("\"hasMore\":");
        assertThat(get("/api/checkout/transactions/" + unpaid.id(), buyer.token(), String.class).getBody())
                .as("the single read carries it too").contains("\"reference\":\"" + reference + "\"");

        Buyer stranger = verifiedBuyer();
        assertThat(get("/api/checkout/transactions/mine", stranger.token(), String.class).getBody())
                .doesNotContain(unpaid.id().toString()).contains("\"total\":0");
    }

    @Test
    void myPurchasesArePagedNewestFirst() {
        Buyer fresh = verifiedBuyer();
        TransactionDto first = createTransaction(fresh, reserve(fresh, addPlot("PAGE-1", "available-dev")).id(), "outright", null);
        TransactionDto second = createTransaction(fresh, reserve(fresh, addPlot("PAGE-2", "available-dev")).id(), "outright", null);

        String page1 = get("/api/checkout/transactions/mine?limit=1", fresh.token(), String.class).getBody();
        assertThat(page1).contains(second.id().toString()).doesNotContain(first.id().toString())
                .contains("\"total\":2").contains("\"hasMore\":true").contains("\"cursor\":\"1\"");
        String page2 = get("/api/checkout/transactions/mine?limit=1&cursor=1", fresh.token(), String.class).getBody();
        assertThat(page2).contains(first.id().toString()).contains("\"hasMore\":false");
    }

    /** reviewKind tells the two kinds of review apart, and the refund's status follows. */
    @Test
    void aRejectedPurchaseSaysRefundDueAndThenShowsTheRefund() {
        String reference = rejectedPayment();
        UUID transactionId = UUID.fromString(queryString("SELECT transaction_id FROM payments WHERE reference = '"
                + reference + "'"));
        assertThat(get("/api/checkout/transactions/" + transactionId, buyer.token(), String.class).getBody())
                .contains("\"reviewKind\":\"refund_due\"").contains("\"refundStatus\":null");

        when(paystack.createRefund(eq(reference), anyLong(), any(), any()))
                .thenReturn(new PaystackClient.Refund("R9", "pending", null, null, null));
        startRefund(payoutAdmin(true), reference);

        assertThat(get("/api/checkout/transactions/" + transactionId, buyer.token(), String.class).getBody())
                .contains("\"refundStatus\":\"pending\"");
    }

    @Test
    void lateMoneySaysLatePayment() {
        TransactionDto transaction = latePayment();

        assertThat(get("/api/checkout/transactions/" + transaction.id(), buyer.token(), String.class).getBody())
                .contains("\"status\":\"abandoned\"").contains("\"underReview\":true")
                .contains("\"reviewKind\":\"late_payment\"");
    }

    /**
     * Separation of duties: a buyer who also holds a finance role at the
     * selling company (a leftover buyer role on a staff account) can't decide
     * on their own purchase — someone else must.
     */
    @Test
    void nobodyDecidesOnTheirOwnPurchase() {
        TransactionDto transaction = paidPurchase();
        String insider = alsoFinanceAtSeller(buyer);

        ResponseEntity<String> verify = financeAction(insider, transaction.id(), "verify", null);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(verify.getBody()).contains("OWN_PURCHASE");
        assertThat(financeAction(insider, transaction.id(), "reject", "{\"reason\":\"Mine\"}").getBody())
                .contains("OWN_PURCHASE");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'"))
                .isEqualTo("AWAITING_FINANCE");

        assertThat(financeAction(staffWithRole(seller.id(), "finance_officer", null), transaction.id(), "verify", null)
                .getStatusCode()).as("a colleague can").isEqualTo(HttpStatus.OK);
    }

    @Test
    void nobodyResolvesTheirOwnLatePayment() {
        TransactionDto transaction = latePayment();
        String insider = alsoFinanceAtSeller(buyer);

        assertThat(restTemplate.exchange("/api/portal/finance/late-payments/" + transaction.id() + "/allocate",
                HttpMethod.POST, entity(insider, null), String.class).getBody()).contains("OWN_PURCHASE");
        assertThat(restTemplate.exchange("/api/portal/finance/late-payments/" + transaction.id() + "/refund",
                HttpMethod.POST, entity(insider, "{\"reason\":\"Mine\"}"), String.class).getBody()).contains("OWN_PURCHASE");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("ABANDONED");
    }

    /** Gives a buyer a company-wide finance role at the seller too — the shape a hand-made test account can have. */
    private String alsoFinanceAtSeller(Buyer who) {
        execute("UPDATE users SET tenant_id = '" + seller.id() + "' WHERE id = '" + who.id() + "'");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id, scoped_branch_id) "
                + "SELECT gen_random_uuid(), now(), false, '" + who.id() + "', r.id, NULL FROM roles r "
                + "WHERE r.code = 'finance_officer'");
        return login(who.email());
    }

    private TransactionDto verifiedSale() {
        TransactionDto transaction = paidPurchase();
        assertThat(financeAction(seller.token(), transaction.id(), "verify", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        return transaction;
    }

    private void approvedPayoutAccount(UUID tenantId, String recipientCode) {
        execute("INSERT INTO settlement_accounts (id, tenant_id, created_at, deleted, bank_code, bank_name, "
                + "account_number, account_name, currency, status, recipient_code, submitted_by) "
                + "SELECT gen_random_uuid(), '" + tenantId + "', now(), false, '058', 'Guaranty Trust Bank', "
                + "'0123456789', 'SELLER LIMITED', 'NGN', 'APPROVED', '" + recipientCode + "', id FROM users LIMIT 1 "
                + "ON CONFLICT DO NOTHING");
    }

    /** A Super Admin of its own; 2FA switched on AFTER signing in, since a 2FA login needs a code. */
    private String payoutAdmin(boolean twoFactor) {
        String email = "payouts+" + UUID.randomUUID() + "@example.com";
        assertThat(restTemplate.postForEntity("/api/auth/register", new RegisterRequest(
                "Payout", "Admin", email, "+2348000000000", PASSWORD, "NG", Currency.NGN), AuthResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        execute("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('" + email + "'))");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id, scoped_branch_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id, NULL FROM users u, roles r "
                + "WHERE lower(u.email) = lower('" + email + "') AND r.code = 'super_admin'");
        String token = login(email);
        if (twoFactor) {
            execute("UPDATE users SET two_fa_enabled = true, two_fa_confirmed_at = now() WHERE lower(email) = lower('"
                    + email + "')");
        }
        return token;
    }

    /** Sends the next part and enters its code; Paystack (the stand-in) reports success. */
    private void payPart(String admin, UUID transactionId, String transferCode) {
        when(paystack.initiateTransfer(anyLong(), anyString(), anyString(), any()))
                .thenReturn(transfer("otp", transferCode, null));
        when(paystack.finalizeTransfer(transferCode, "123456")).thenReturn(transfer("success", transferCode, null));
        assertThat(sendPayout(admin, transactionId).getBody()).contains("awaiting_otp");
        String payoutId = queryString("SELECT id FROM payouts WHERE transfer_code = '" + transferCode + "'");
        assertThat(restTemplate.exchange("/api/admin/payouts/" + payoutId + "/otp", HttpMethod.POST,
                entity(admin, "{\"otp\":\"123456\"}"), String.class).getBody()).contains("\"status\":\"success\"");
    }

    private ResponseEntity<String> sendPayout(String token, UUID transactionId) {
        return restTemplate.exchange("/api/admin/payouts", HttpMethod.POST,
                entity(token, "{\"transactionId\":\"" + transactionId + "\"}"), String.class);
    }

    private static PaystackClient.Transfer transfer(String status, String transferCode, String message) {
        return new PaystackClient.Transfer(true, status, transferCode, null, message, null);
    }

    private TransactionDto paidPurchase() {
        TransactionDto transaction = outrightTransaction();
        String reference = startPayment(transaction);
        paystackSays(reference, "success", 2_000_000_000L, "NGN", "Approved");
        assertThat(confirm(buyer, reference).getBody()).contains("\"status\":\"succeeded\"");
        return transaction;
    }

    private ResponseEntity<String> financeAction(String token, UUID transactionId, String action, String json) {
        return restTemplate.exchange("/api/portal/finance/transactions/" + transactionId + "/" + action,
                HttpMethod.POST, entity(token, json), String.class);
    }

    private String staffWithRole(UUID owningTenantId, String roleCode, UUID branchId) {
        String email = "staff+" + UUID.randomUUID() + "@example.com";
        assertThat(restTemplate.postForEntity("/api/auth/register", new RegisterRequest(
                "Finance", "Staff", email, "+2348000000000", PASSWORD, "NG", Currency.NGN), AuthResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        execute("UPDATE users SET tenant_id = '" + owningTenantId + "' WHERE lower(email) = lower('" + email + "')");
        execute("DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('" + email + "'))");
        execute("INSERT INTO user_roles (id, created_at, deleted, user_id, role_id, scoped_branch_id) "
                + "SELECT gen_random_uuid(), now(), false, u.id, r.id, "
                + (branchId == null ? "NULL" : "'" + branchId + "'") + " FROM users u, roles r "
                + "WHERE lower(u.email) = lower('" + email + "') AND r.code = '" + roleCode + "'");
        return login(email);
    }

    private void holdEndedMinutesAgo(TransactionDto transaction, int minutes) {
        execute("UPDATE reservations SET expires_at = now() - interval '" + minutes + " minutes' WHERE id = '"
                + transaction.reservationId() + "'");
    }

    private ResponseEntity<String> webhook(String body, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (signature != null) {
            headers.set("x-paystack-signature", signature);
        }
        return restTemplate.exchange("/api/payments/webhook/paystack", HttpMethod.POST,
                new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), headers), String.class);
    }

    private String startPayment(TransactionDto transaction) {
        paystackReturnsALink();
        pay(buyer, transaction.id());
        return queryString("SELECT reference FROM payments WHERE transaction_id = '" + transaction.id() + "'");
    }

    private void paystackSays(String reference, String status, long amountKobo, String currency, String gatewayResponse) {
        when(paystack.verify(reference)).thenReturn(new PaystackClient.Verification(true, status, reference, amountKobo,
                currency, gatewayResponse, "success".equals(status) ? Instant.now() : null, "bank_transfer",
                null, null, "{}"));
    }

    private ResponseEntity<String> confirm(Buyer who, String reference) {
        return restTemplate.exchange("/api/payments/" + reference + "/verify", HttpMethod.POST,
                entity(who.token(), null), String.class);
    }

    private TransactionDto outrightTransaction() {
        return createTransaction(buyer, reserve(buyer, plotId).id(), "outright", null);
    }

    /** doAnswer, not when(): safe to call again, since when() would invoke the answer already stubbed. */
    private void paystackReturnsALink() {
        doAnswer(call -> {
            PaystackClient.InitializeRequest request = call.getArgument(0);
            return new PaystackClient.InitializeResult("https://checkout.paystack.com/test-link", "test-link",
                    request.reference());
        }).when(paystack).initialize(any());
    }

    private ResponseEntity<String> pay(Buyer who, UUID transactionId) {
        return restTemplate.exchange("/api/transactions/" + transactionId + "/payments", HttpMethod.POST,
                entity(who.token(), null), String.class);
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

    /**
     * The double-sale bug: a transaction used to leave its reservation
     * ACTIVE, so 45 minutes later the sweeper treated the hold as abandoned
     * and put the plot back on the market while its purchase was pending.
     */
    @Test
    void aHoldWithAPurchaseInProgressIsNeverSweptBackIntoThePool() {
        ReservationDto reservation = reserve(buyer, plotId);
        createTransaction(buyer, reservation.id(), "outright", null);

        execute("UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE id = '"
                + reservation.id() + "'");
        sweeper.sweep();

        assertThat(plotStatus(plotId))
                .as("a plot whose purchase is pending must stay held — releasing it is a double sale")
                .isEqualTo("RESERVED");
        assertThat(reservationStatus(reservation.id())).isEqualTo("ACTIVE");
        assertThat(attemptReserve(verifiedBuyer(), plotId).getStatusCode())
                .as("and a second buyer cannot take it")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    /** The same hole through the other door: the buyer cancelling the hold. */
    @Test
    void aBuyerCannotCancelAHoldWithAPurchaseInProgress() {
        ReservationDto reservation = reserve(buyer, plotId);
        createTransaction(buyer, reservation.id(), "outright", null);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/reservations/" + reservation.id(), HttpMethod.DELETE,
                new HttpEntity<>(bearer(buyer.token())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("PURCHASE_IN_PROGRESS");
        assertThat(plotStatus(plotId)).isEqualTo("RESERVED");
        assertThat(reservationStatus(reservation.id())).isEqualTo("ACTIVE");
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

    // --- instalment plans, phase 3a: the deposit (changeset 101) ---

    /**
     * The deposit is charged, verified by finance like any payment, and holds
     * the plot on the plan: ON_PLAN (not sold), the purchase on_plan, an active
     * plan in the portfolio and a provisional letter — no final letter, no payout.
     */
    @Test
    void aVerifiedDepositHoldsThePlotOnThePlanAndIssuesTheProvisionalLetter() {
        TransactionDto purchase = paidDeposit();
        String payment = "FROM payments WHERE transaction_id = '" + purchase.id() + "'";
        assertThat(queryString("SELECT purpose || '/' || amount_kobo " + payment)).isEqualTo("DEPOSIT/660000000");

        String officer = staffWithRole(seller.id(), "finance_officer", null);
        assertThat(get("/api/portal/finance/transactions", officer, String.class).getBody())
                .contains("\"paymentFor\":\"deposit\"", "\"planName\":\"12-month plan\"", "\"expectedAmount\":6600000",
                        "\"amountsMatch\":true");
        ResponseEntity<String> verified = financeAction(officer, purchase.id(), "verify", null);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody()).as("finance is told the plan is running, not that the plot is sold")
                .contains("\"status\":\"on_plan\"").containsPattern("\"allocationLetterNumber\":\"HT-PAL-");

        assertThat(plotStatus(plotId)).as("held on the plan, not sold").isEqualTo("ON_PLAN");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + purchase.id() + "'")).isEqualTo("ON_PLAN");
        assertThat(reservationStatus(purchase.reservationId())).isEqualTo("CONVERTED");
        assertThat(queryString("SELECT status FROM owned_plots WHERE transaction_id = '" + purchase.id() + "'"))
                .isEqualTo("INSTALLMENT_ACTIVE");

        String letters = "FROM allocation_letters WHERE transaction_id = '" + purchase.id() + "'";
        assertThat(queryString("SELECT string_agg(kind, ',') " + letters)).as("no final letter yet").isEqualTo("PROVISIONAL");
        assertThat(queryString("SELECT number " + letters)).matches("HT-PAL-\\d{4}-\\d{6}");
        String offerNumber = queryString("SELECT number FROM offer_letters WHERE transaction_id = '" + purchase.id() + "'");
        assertThat(queryString("SELECT clauses[array_length(clauses, 1)] " + letters))
                .startsWith("That this allocation is provisional").contains(offerNumber);
        assertThat(documents(buyer).items()).extracting(DocumentDto::type).contains("provisional_allocation_letter")
                .doesNotContain("allocation_letter");

        OwnedPlotDto onPlan = portfolio(buyer).items().getFirst();
        assertThat(onPlan.status()).isEqualTo("installment_active");
        assertThat(onPlan.plan()).isEqualTo("installment");
        assertThat(onPlan.totalPrice()).as("what the plan costs").isEqualByComparingTo("22000000");
        assertThat(onPlan.paidAmount()).as("the deposit").isEqualByComparingTo("6600000");
        assertThat(onPlan.installmentMonths()).isEqualTo(12);
        assertThat(onPlan.installmentsPaid()).isZero();
        assertThat(onPlan.nextDueAmount()).isEqualByComparingTo("1283333.33");
        assertThat(onPlan.nextDueDate()).isEqualTo(LocalDate.now(ZoneId.of("Africa/Lagos")).plusMonths(1));

        assertThat(queryString("SELECT count(*) FROM transactions WHERE status = 'VERIFIED' AND id = '"
                + purchase.id() + "'")).as("not a verified sale, so nothing is owed for payout").isEqualTo("0");
        ResponseEntity<String> again = restTemplate.exchange("/api/reservations", HttpMethod.POST,
                entity(verifiedBuyer().token(), new CreateReservationRequest(plotId)), String.class);
        assertThat(again.getBody()).as("nobody else can reserve it").contains("PLOT_NOT_AVAILABLE");
    }

    // --- instalment plans, phase 3b: the instalments (changeset 102) ---

    /**
     * Decisions 5 and 7: one instalment at a time, each verified by finance;
     * the last one sells the plot, completes the plan and issues the final
     * letter. Nothing is paid out to the developer yet (escrow is 3c).
     */
    @Test
    void instalmentsArePaidOneAtATimeAndTheLastSellsThePlot() {
        TransactionDto purchase = onPlanPurchase(SHORT_PLAN);
        String officer = staffWithRole(seller.id(), "finance_officer", null);

        String first = payPlan(purchase, false);
        assertThat(queryString("SELECT purpose || '/' || instalment_sequence || '/' || amount FROM payments "
                + "WHERE reference = '" + first + "'")).isEqualTo("INSTALMENT/1/7700000.00");
        paystackSays(first, "success", 770_000_000L, "NGN", "Approved");
        assertThat(confirm(buyer, first).getBody()).contains("\"status\":\"succeeded\"");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + purchase.id() + "'"))
                .as("an instalment doesn't move the purchase").isEqualTo("ON_PLAN");
        paystackReturnsALink();
        assertThat(pay(buyer, purchase.id()).getBody()).as("the next only once this one is verified")
                .contains("INSTALMENT_AWAITING_VERIFICATION");
        assertThat(get("/api/portal/finance/transactions", officer, String.class).getBody())
                .contains("\"paymentFor\":\"instalment\"", "\"instalmentSequence\":1", "\"instalmentCount\":2",
                        "\"expectedAmount\":7700000", "\"amountsMatch\":true");

        assertThat(financeAction(officer, purchase.id(), "verify", null).getBody()).contains("\"status\":\"on_plan\"");
        assertThat(plotStatus(plotId)).isEqualTo("ON_PLAN");
        OwnedPlotDto midway = portfolio(buyer).items().getFirst();
        assertThat(midway.installmentsPaid()).isEqualTo(1);
        assertThat(midway.paidAmount()).as("deposit and one instalment").isEqualByComparingTo("14300000");
        assertThat(midway.nextDueAmount()).isEqualByComparingTo("7700000");
        assertThat(midway.payments()).extracting(p -> p.type() + "/" + p.status())
                .containsExactly("deposit/confirmed", "instalment/confirmed");

        String second = payPlan(purchase, false);
        paystackSays(second, "success", 770_000_000L, "NGN", "Approved");
        assertThat(confirm(buyer, second).getBody()).contains("\"status\":\"succeeded\"");
        assertThat(financeAction(officer, purchase.id(), "verify", null).getBody()).contains("\"status\":\"verified\"");

        assertThat(plotStatus(plotId)).isEqualTo("SOLD");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + purchase.id() + "'")).isEqualTo("VERIFIED");
        assertThat(queryString("SELECT status FROM owned_plots WHERE transaction_id = '" + purchase.id() + "'"))
                .isEqualTo("COMPLETED");
        assertThat(queryString("SELECT string_agg(kind, ',' ORDER BY kind) FROM allocation_letters WHERE transaction_id = '"
                + purchase.id() + "'")).isEqualTo("ALLOCATION,PROVISIONAL");
        OwnedPlotDto owned = portfolio(buyer).items().getFirst();
        assertThat(owned.status()).isEqualTo("completed");
        assertThat(owned.paidAmount()).isEqualByComparingTo("22000000");
        assertThat(owned.installmentsPaid()).isEqualTo(2);
        assertThat(owned.nextDueDate()).isNull();
        assertThat(pay(buyer, purchase.id()).getBody()).contains("TRANSACTION_NOT_PAYABLE");
        assertThat(get("/api/admin/payouts/ready", payoutAdmin(true), String.class).getBody())
                .as("owed from escrow (3c): the deposit's share first, nothing paid out yet")
                .contains(purchase.id().toString(), "\"tranche\":\"share\"", "\"amountOwed\":22000000");
    }

    // --- instalment plans, phase 3c: escrow payouts (changeset 103) ---

    /**
     * The estate's refund terms (20% of the land price) are copied onto the
     * purchase. The developer is paid that ₦4M from the deposit as soon as
     * finance verifies it; the rest is held, and released when the plan
     * completes — ₦18M, over the cap, so in two parts.
     */
    @Test
    void escrowPaysTheDevelopersShareAsItIsVerifiedAndReleasesTheRestAtCompletion() {
        TransactionDto purchase = onPlanPurchase(SHORT_PLAN);
        assertThat(queryString("SELECT escrow_deduction_pct || '/' || escrow_applies_to FROM transactions WHERE id = '"
                + purchase.id() + "'")).as("the refund terms offered, fixed on the purchase").isEqualTo("20.00/TOTAL_PRICE");
        approvedPayoutAccount(seller.id(), "RCP_seller");
        String admin = payoutAdmin(true);
        String officer = staffWithRole(seller.id(), "finance_officer", null);

        String ready = get("/api/admin/payouts/ready", admin, String.class).getBody();
        assertThat(ready).contains(purchase.id().toString(), "\"tranche\":\"share\"",
                "\"trancheLabel\":\"Developer's share of the deposit\"", "\"trancheAmount\":4000000",
                "\"heldInEscrow\":2600000", "\"blockers\":[]");
        payPart(admin, purchase.id(), "TRF_share");
        verify(paystack).initiateTransfer(eq(400_000_000L), eq("RCP_seller"), anyString(), any());
        assertThat(get("/api/admin/payouts/ready", admin, String.class).getBody())
                .as("nothing more is owed while the plan runs").doesNotContain(purchase.id().toString());
        ResponseEntity<String> held = sendPayout(admin, purchase.id());
        assertThat(held.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(held.getBody()).contains("HELD_IN_ESCROW");

        for (int i = 0; i < 2; i++) {
            String instalment = payPlan(purchase, false);
            paystackSays(instalment, "success", 770_000_000L, "NGN", "Approved");
            confirm(buyer, instalment);
            assertThat(financeAction(officer, purchase.id(), "verify", null).getStatusCode()).isEqualTo(HttpStatus.OK);
            if (i == 0) {
                assertThat(get("/api/admin/payouts/ready", admin, String.class).getBody())
                        .as("the ₦4M is already paid: the instalment is held").doesNotContain(purchase.id().toString());
            }
        }

        assertThat(get("/api/admin/payouts/ready", admin, String.class).getBody())
                .contains(purchase.id().toString(), "\"tranche\":\"release\"", "\"trancheAmount\":18000000",
                        "\"amountOwed\":18000000", "\"partCount\":2");
        payPart(admin, purchase.id(), "TRF_release1");
        payPart(admin, purchase.id(), "TRF_release2");
        verify(paystack, times(2)).initiateTransfer(eq(900_000_000L), eq("RCP_seller"), anyString(), any());

        assertThat(queryString("SELECT string_agg(split_part(tranche, ':', 1) || '/' || part_number || '/' || amount_kobo, "
                + "',' ORDER BY created_at) FROM payouts WHERE status = 'SUCCESS' AND transaction_id = '" + purchase.id()
                + "'")).as("everything the buyer paid, and no more").isEqualTo(
                        "SHARE/1/400000000,RELEASE/1/900000000,RELEASE/2/900000000");
        ResponseEntity<String> again = sendPayout(admin, purchase.id());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("SALE_ALREADY_PAID");
    }

    /** A plan payment finance hasn't verified owes the developer nothing yet. */
    @Test
    void anUnverifiedPlanPaymentIsNotPaidOut() {
        TransactionDto purchase = paidDeposit();
        approvedPayoutAccount(seller.id(), "RCP_seller");

        assertThat(get("/api/admin/payouts/ready", payoutAdmin(true), String.class).getBody())
                .doesNotContain(purchase.id().toString());
        ResponseEntity<String> refused = sendPayout(payoutAdmin(true), purchase.id());
        assertThat(refused.getBody()).contains("SALE_NOT_PAYABLE");
        verify(paystack, never()).initiateTransfer(anyLong(), anyString(), anyString(), any());
    }

    /** Decision 7: everything still owed in one payment completes the plan. */
    @Test
    void payingOffTheBalanceCompletesThePlan() {
        TransactionDto purchase = onPlanPurchase(TWELVE_MONTH_PLAN);
        String balance = payPlan(purchase, true);
        assertThat(queryString("SELECT purpose || '/' || amount FROM payments WHERE reference = '" + balance + "'"))
                .as("₦22,000,000 less the ₦6,600,000 deposit").isEqualTo("BALANCE/15400000.00");
        paystackSays(balance, "success", 1_540_000_000L, "NGN", "Approved");
        assertThat(confirm(buyer, balance).getBody()).contains("\"status\":\"succeeded\"");
        String officer = staffWithRole(seller.id(), "finance_officer", null);
        assertThat(get("/api/portal/finance/transactions", officer, String.class).getBody())
                .contains("\"paymentFor\":\"balance\"", "\"expectedAmount\":15400000");

        assertThat(financeAction(officer, purchase.id(), "verify", null).getBody()).contains("\"status\":\"verified\"");
        assertThat(plotStatus(plotId)).isEqualTo("SOLD");
        assertThat(portfolio(buyer).items().getFirst().installmentsPaid()).isEqualTo(12);
    }

    /** A rejected instalment is refunded on its own; the plan carries on and it is owed again. */
    @Test
    void aRejectedInstalmentIsRefundedAndThePlanCarriesOn() {
        TransactionDto purchase = onPlanPurchase(SHORT_PLAN);
        String first = payPlan(purchase, false);
        paystackSays(first, "success", 770_000_000L, "NGN", "Approved");
        confirm(buyer, first);
        String officer = staffWithRole(seller.id(), "finance_officer", null);

        assertThat(financeAction(officer, purchase.id(), "reject", "{\"reason\":\"Chargeback\"}").getBody())
                .contains("\"status\":\"on_plan\"");
        assertThat(queryString("SELECT (refund_requested_at IS NOT NULL) || '/' || (finance_verified_at IS NULL) "
                + "FROM payments WHERE reference = '" + first + "'")).isEqualTo("true/true");
        assertThat(plotStatus(plotId)).isEqualTo("ON_PLAN");
        String again = payPlan(purchase, false);
        assertThat(queryString("SELECT instalment_sequence FROM payments WHERE reference = '" + again + "'"))
                .as("the same instalment is owed again").isEqualTo("1");
    }

    /** Phase 3a: a deposit that arrives after the purchase was abandoned can only be refunded. */
    @Test
    void aLateDepositCanOnlyBeRefunded() {
        TransactionDto purchase = instalmentPurchase();
        paystackReturnsALink();
        assertThat(pay(buyer, purchase.id()).getStatusCode().is2xxSuccessful()).isTrue();
        String reference = queryString("SELECT reference FROM payments WHERE transaction_id = '" + purchase.id() + "'");
        paystackSays(reference, "abandoned", 660_000_000L, "NGN", "The customer left");
        holdEndedMinutesAgo(purchase, 20);
        paymentSweeper.sweep();
        paystackSays(reference, "success", 660_000_000L, "NGN", "Approved");
        String body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"" + reference + "\"}}";
        webhook(body, PaystackSignature.sign(body.getBytes(StandardCharsets.UTF_8), paystackSecretKey));
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + purchase.id() + "'")).isEqualTo("ABANDONED");

        String officer = staffWithRole(seller.id(), "finance_officer", null);
        ResponseEntity<String> refused = restTemplate.exchange("/api/portal/finance/late-payments/" + purchase.id()
                + "/allocate", HttpMethod.POST, entity(officer, null), String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("LATE_DEPOSIT_NOT_SUPPORTED");
        assertThat(plotStatus(plotId)).startsWith("AVAILABLE");
    }

    // --- instalment plans, phase 4: arrears, penalties and reminders (changeset 104) ---

    /**
     * An instalment 40 days late on an estate with a 5%-at-one-month step: the
     * buyer and the developer see it in arrears, paying it charges the penalty
     * as its own line (fixed when the payment starts), finance checks that
     * total, and — declared not refundable — the penalty goes to the developer
     * in full. The penalty is the purchase's own copy: redeclaring the estate's
     * terms afterwards changes nothing for this buyer.
     */
    @Test
    void aLateInstalmentIsInArrearsAndPayingItChargesTheDeclaredPenalty() {
        declareDefaultTerms("[{\"monthsLate\":1,\"penaltyPct\":5.00}]", false);
        TransactionDto purchase = onPlanPurchase(SHORT_PLAN);
        assertThat(queryString("SELECT penalties_refundable || '/' || (SELECT string_agg(months_late || ':' || "
                + "penalty_pct, ',') FROM transaction_penalty_steps WHERE transaction_id = t.id) FROM transactions t "
                + "WHERE id = '" + purchase.id() + "'")).as("copied when the purchase opened").isEqualTo("false/1:5.00");
        declareDefaultTerms("[{\"monthsLate\":1,\"penaltyPct\":50.00}]", true);
        execute("UPDATE transaction_instalments SET due_date = current_date - 40 WHERE transaction_id = '"
                + purchase.id() + "' AND sequence = 1");
        String officer = staffWithRole(seller.id(), "finance_officer", null);

        TransactionDto late = get("/api/checkout/transactions/" + purchase.id(), buyer.token(), TransactionDto.class)
                .getBody();
        assertThat(late.standing().status()).isEqualTo("in_arrears");
        assertThat(late.standing().arrears()).satisfies(a -> {
            assertThat(a.instalmentsLate()).isEqualTo(1);
            assertThat(a.amountOverdue()).isEqualByComparingTo("7700000");
            assertThat(a.penaltyAmount()).as("5% of the instalment, not 50%").isEqualByComparingTo("385000");
            assertThat(a.totalDue()).isEqualByComparingTo("8085000");
        });
        assertThat(late.standing().instalments()).extracting(PlanStanding.InstalmentStatus::status)
                .containsExactly("in_arrears", "upcoming");
        OwnedPlotDto owned = portfolio(buyer).items().getFirst();
        assertThat(owned.status()).isEqualTo("in_arrears");
        assertThat(owned.arrears().penaltyAmount()).isEqualByComparingTo("385000");
        assertThat(owned.arrears().penaltyReason())
                .isEqualTo("Instalment 1 is 1 month late, so the declared 5% penalty applies to it.");
        assertThat(get("/api/portal/finance/plans?status=late", officer, String.class).getBody())
                .contains(purchase.id().toString(), "\"status\":\"in_arrears\"", "\"penaltyAmount\":385000");
        assertThat(get("/api/portal/finance/plans?status=on_track", officer, String.class).getBody())
                .doesNotContain(purchase.id().toString());

        String reference = payPlan(purchase, false);
        assertThat(queryString("SELECT amount || '/' || penalty_amount FROM payments WHERE reference = '" + reference
                + "'")).isEqualTo("8085000.00/385000.00");
        assertThat(queryString("SELECT pp.instalment_sequence || '/' || pp.months_late || '/' || pp.penalty_pct || '/' "
                + "|| pp.amount FROM payment_penalties pp JOIN payments p ON p.id = pp.payment_id WHERE p.reference = '" + reference
                + "'")).isEqualTo("1/1/5.00/385000.00");
        paystackSays(reference, "success", 808_500_000L, "NGN", "Approved");
        assertThat(confirm(buyer, reference).getBody()).contains("\"status\":\"succeeded\"", "\"penaltyAmount\":385000");
        assertThat(get("/api/checkout/transactions/" + purchase.id(), buyer.token(), TransactionDto.class).getBody()
                .standing().instalments().getFirst().status()).as("paid, finance not yet: never late")
                .isEqualTo("awaiting_verification");
        assertThat(get("/api/portal/finance/transactions", officer, String.class).getBody())
                .contains("\"expectedAmount\":8085000", "\"penaltyAmount\":385000", "\"amountsMatch\":true",
                        "\"monthsLate\":1");
        assertThat(financeAction(officer, purchase.id(), "verify", null).getBody()).contains("\"status\":\"on_plan\"");

        OwnedPlotDto after = portfolio(buyer).items().getFirst();
        assertThat(after.status()).isEqualTo("installment_active");
        assertThat(after.arrears()).isNull();
        assertThat(after.paidAmount()).as("the penalty doesn't count towards the plan").isEqualByComparingTo("14300000");
        assertThat(after.payments().getLast().penaltyAmount()).isEqualByComparingTo("385000");
        approvedPayoutAccount(seller.id(), "RCP_seller");
        assertThat(get("/api/admin/payouts/ready", payoutAdmin(true), String.class).getBody())
                .as("the deposit's ₦4M share, plus the non-refundable penalty in full; the instalment itself is held")
                .contains(purchase.id().toString(), "\"amountOwed\":4385000");
    }

    /** Declaring penalty steps means saying whether they're refunded; with none, the question doesn't arise. */
    @Test
    void penaltyStepsNeedARefundAnswer() {
        ResponseEntity<String> refused = restTemplate.exchange("/api/portal/estates/" + estateId + "/default-terms",
                HttpMethod.PUT, entity(seller.token(), defaultTermsJson("[{\"monthsLate\":1,\"penaltyPct\":5.00}]",
                        null)), String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("penaltiesRefundable");
        declareDefaultTerms("[]", null);
    }

    /** AR-3: each reminder once per instalment and stage; nothing while finance is verifying a payment. */
    @Test
    void remindersGoOncePerInstalmentAndStage() {
        TransactionDto purchase = onPlanPurchase(SHORT_PLAN);
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
        execute("UPDATE transaction_instalments SET due_date = DATE '" + today.plusDays(7) + "' WHERE transaction_id = '"
                + purchase.id() + "' AND sequence = 1");

        instalmentReminders.run(today);
        instalmentReminders.run(today);
        assertThat(remindersFor(purchase)).as("the second run sends nothing new").containsExactly("SEVEN_DAYS_BEFORE");

        instalmentReminders.run(today.plusDays(8));
        instalmentReminders.run(today.plusDays(15));
        assertThat(remindersFor(purchase)).containsExactly("SEVEN_DAYS_BEFORE", "OVERDUE", "IN_ARREARS");
        ArgumentCaptor<SettlementAlertSender.InstalmentReminderAlert> sent =
                ArgumentCaptor.forClass(SettlementAlertSender.InstalmentReminderAlert.class);
        verify(alerts, atLeastOnce()).instalmentReminder(sent.capture());
        assertThat(sent.getAllValues()).filteredOn(a -> a.purchaseUrl().contains(purchase.id().toString()))
                .extracting(SettlementAlertSender.InstalmentReminderAlert::stage)
                .containsExactly("SEVEN_DAYS_BEFORE", "OVERDUE", "IN_ARREARS");
        assertThat(queryString("SELECT count(*) FROM instalment_reminders WHERE transaction_id = '" + purchase.id()
                + "'")).isEqualTo("3");

        String paid = payPlan(purchase, false);
        paystackSays(paid, "success", 770_000_000L, "NGN", "Approved");
        confirm(buyer, paid);
        execute("UPDATE transaction_instalments SET due_date = DATE '" + today.plusDays(1) + "' WHERE transaction_id = '"
                + purchase.id() + "' AND sequence = 2");
        instalmentReminders.run(today);
        assertThat(remindersFor(purchase)).as("instalment 2 has its own reminders").contains("ONE_DAY_BEFORE");
        instalmentReminders.run(today.plusDays(30));
        assertThat(queryString("SELECT count(*) FROM instalment_reminders WHERE transaction_id = '" + purchase.id()
                + "' AND instalment_sequence = 1")).as("awaiting finance: never reminded as late").isEqualTo("3");
    }

    // --- instalment plans, phase 5a: withdrawal (changeset 106) ---

    /**
     * 20% of what is paid, the short plan: the deposit (₦6.6M) is verified, the
     * first instalment (₦7.7M) is confirmed by Paystack but finance hasn't
     * verified it. Withdrawing returns 80% of both at once; the developer keeps
     * ₦1.32M of the deposit (paid out as usual) and ₦1.54M of the instalment,
     * held until their finance verifies it. Nobody approves the withdrawal.
     */
    @Test
    void aBuyerWithdrawsOnTheDeclaredTermsAndTheDevelopersShareWaitsForFinance() {
        declareRefundTerms("{\"deductionPct\":20.00,\"processingDays\":90,\"appliesTo\":\"amount_paid\"}");
        String before = plotStatus(plotId);
        TransactionDto purchase = onPlanPurchase(SHORT_PLAN);
        String deposit = queryString("SELECT reference FROM payments WHERE transaction_id = '" + purchase.id()
                + "' AND purpose = 'DEPOSIT'");
        String first = payPlan(purchase, false);
        paystackSays(first, "success", 770_000_000L, "NGN", "Approved");
        confirm(buyer, first);
        String withdrawal = "/api/transactions/" + purchase.id() + "/withdrawal";

        String quote = get(withdrawal, buyer.token(), String.class).getBody();
        assertThat(quote).contains("\"status\":\"on_plan\"", "\"paid\":14300000", "\"refund\":11440000",
                "\"developerKeeps\":2860000", "\"appliesTo\":\"amount_paid\"", "\"awaitingFinance\":true");
        assertThat(plotStatus(plotId)).as("a quote changes nothing").isEqualTo("ON_PLAN");
        assertThat(get(withdrawal, verifiedBuyer().token(), String.class).getStatusCode())
                .as("someone else's purchase").isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> stale = restTemplate.exchange(withdrawal, HttpMethod.POST,
                entity(buyer.token(), "{\"refund\":11000000}"), String.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).contains("WITHDRAWAL_QUOTE_CHANGED");
        assertThat(plotStatus(plotId)).isEqualTo("ON_PLAN");

        // The developer's Executive Directors are emailed; only active accounts receive mail.
        execute("UPDATE users SET status = 'ACTIVE' WHERE tenant_id = '" + seller.id() + "'");
        ResponseEntity<String> done = restTemplate.exchange(withdrawal, HttpMethod.POST,
                entity(buyer.token(), "{\"refund\":11440000.00,\"reason\":\"Moving abroad\"}"), String.class);
        assertThat(done.getStatusCode()).as(done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody()).contains("\"status\":\"withdrawn\"", "\"refund\":11440000");

        assertThat(queryString("SELECT status || '/' || withdrawal_reason FROM transactions WHERE id = '"
                + purchase.id() + "'")).isEqualTo("WITHDRAWN/Moving abroad");
        assertThat(plotStatus(plotId)).as("back on sale as it was").isEqualTo(before);
        assertThat(queryString("SELECT status FROM owned_plots WHERE transaction_id = '" + purchase.id() + "'"))
                .isEqualTo("WITHDRAWN");
        assertThat(queryString("SELECT string_agg(purpose || '/' || refund_amount, ',' ORDER BY created_at) "
                + "FROM payments WHERE transaction_id = '" + purchase.id() + "' AND refund_requested_at IS NOT NULL"))
                .isEqualTo("DEPOSIT/5280000.00,INSTALMENT/6160000.00");
        assertThat(documents(buyer).items()).filteredOn(d -> "provisional_allocation_letter".equals(d.type()))
                .singleElement().satisfies(d -> assertThat(d.status()).isEqualTo("withdrawn"));
        assertThat(portfolio(buyer).items().getFirst().status()).isEqualTo("withdrawn");
        verify(alerts, atLeastOnce()).planWithdrawn(argThat(a -> !a.toDeveloper()));
        verify(alerts, atLeastOnce()).planWithdrawn(argThat(a -> a.toDeveloper() && "1540000.00".equals(a.awaitingFinance())));

        String admin = payoutAdmin(true);
        assertThat(get("/api/admin/refunds/due", admin, String.class).getBody())
                .contains(deposit, "\"amount\":5280000", first, "\"amount\":6160000");
        approvedPayoutAccount(seller.id(), "RCP_seller");
        assertThat(readyFor(admin, purchase)).contains("\"trancheAmount\":1320000", "\"heldInEscrow\":1540000");

        String officer = staffWithRole(seller.id(), "finance_officer", null);
        assertThat(get("/api/portal/finance/transactions", officer, String.class).getBody())
                .contains(first, "\"planWithdrawn\":true");
        assertThat(financeAction(officer, purchase.id(), "verify", null).getBody()).contains("\"status\":\"withdrawn\"");
        assertThat(readyFor(admin, purchase))
                .as("the instalment's share is now the developer's too; nothing is released")
                .contains("\"amountOwed\":2860000", "\"heldInEscrow\":0").doesNotContain("\"tranche\":\"release\"");

        ResponseEntity<String> again = restTemplate.exchange(withdrawal, HttpMethod.POST,
                entity(buyer.token(), "{\"refund\":11440000}"), String.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("PLAN_NOT_WITHDRAWABLE");
        assertThat(pay(buyer, purchase.id()).getBody()).contains("TRANSACTION_NOT_PAYABLE");
        assertThat(get(withdrawal, buyer.token(), String.class).getBody())
                .as("the settled record").contains("\"status\":\"withdrawn\"", "\"refund\":11440000");
    }

    /**
     * After a withdrawal, finance rejects the payment that was waiting for
     * them: the developer's share of it goes back to the buyer too — a second
     * refund for that payment, for exactly what is left. A resend after a lost
     * reply asks Paystack first, so a part is never paid twice.
     */
    @Test
    void financeRejectingAPaymentAfterAWithdrawalReturnsTheDevelopersShareToo() {
        declareRefundTerms("{\"deductionPct\":20.00,\"processingDays\":90,\"appliesTo\":\"amount_paid\"}");
        TransactionDto purchase = onPlanPurchase(SHORT_PLAN);
        String first = payPlan(purchase, false);
        paystackSays(first, "success", 770_000_000L, "NGN", "Approved");
        confirm(buyer, first);
        assertThat(restTemplate.exchange("/api/transactions/" + purchase.id() + "/withdrawal", HttpMethod.POST,
                entity(buyer.token(), "{\"refund\":11440000}"), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String admin = payoutAdmin(true);

        when(paystack.createRefund(eq(first), anyLong(), any(), any()))
                .thenThrow(new PaymentException.GatewayUnavailable());
        assertThat(startRefund(admin, first).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        when(paystack.findRefundByMerchantNote(anyString(), any()))
                .thenReturn(Optional.of(new PaystackClient.Refund("R9", "processed", null, null, null)));
        assertThat(startRefund(admin, first).getBody()).contains("\"status\":\"processed\"", "\"amount\":6160000");
        verify(paystack, times(1)).createRefund(eq(first), eq(616_000_000L), any(), any());
        assertThat(get("/api/admin/refunds/due", admin, String.class).getBody()).doesNotContain(first);

        String officer = staffWithRole(seller.id(), "finance_officer", null);
        assertThat(financeAction(officer, purchase.id(), "reject", "{\"reason\":\"Not our account\"}").getBody())
                .contains("\"status\":\"withdrawn\"");
        assertThat(get("/api/admin/refunds/due", admin, String.class).getBody())
                .as("only the developer's share is left to return").contains(first, "\"amount\":1540000");
        when(paystack.createRefund(eq(first), eq(154_000_000L), any(), any()))
                .thenReturn(new PaystackClient.Refund("R10", "pending", null, null, null));
        assertThat(startRefund(admin, first).getBody()).contains("\"amount\":1540000");
        assertThat(get("/api/transactions/" + purchase.id() + "/withdrawal", buyer.token(), String.class).getBody())
                .contains("\"refund\":12980000", "\"developerKeeps\":1320000");
        assertThat(readyFor(admin, purchase)).as("the rejected share is no longer held for the developer")
                .contains("\"heldInEscrow\":0");
    }

    /**
     * The buyer's part of a withdrawn payment is on its way when finance
     * rejects that payment. When that part completes, the payment is still
     * owed the developer's share, so it stays flagged: it must not drop out
     * of the refunds due or reappear in finance's queue to be verified.
     */
    @Test
    void aPartRefundCompletingKeepsAPaymentFlaggedWhileMoreIsOwed() {
        declareRefundTerms("{\"deductionPct\":20.00,\"processingDays\":90,\"appliesTo\":\"amount_paid\"}");
        TransactionDto purchase = onPlanPurchase(SHORT_PLAN);
        String first = payPlan(purchase, false);
        paystackSays(first, "success", 770_000_000L, "NGN", "Approved");
        confirm(buyer, first);
        assertThat(restTemplate.exchange("/api/transactions/" + purchase.id() + "/withdrawal", HttpMethod.POST,
                entity(buyer.token(), "{\"refund\":11440000}"), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String admin = payoutAdmin(true);
        when(paystack.createRefund(eq(first), anyLong(), any(), any()))
                .thenReturn(new PaystackClient.Refund("R11", "pending", null, null, null));
        assertThat(startRefund(admin, first).getBody()).contains("\"amount\":6160000");
        String officer = staffWithRole(seller.id(), "finance_officer", null);
        assertThat(financeAction(officer, purchase.id(), "reject", "{\"reason\":\"Not our account\"}").getBody())
                .contains("\"status\":\"withdrawn\"");

        when(paystack.fetchRefund("R11")).thenReturn(new PaystackClient.Refund("R11", "processed", null,
                Instant.parse("2026-10-23T09:00:00Z"), null));
        String body = "{\"event\":\"refund.processed\",\"data\":{\"status\":\"processed\","
                + "\"transaction_reference\":\"" + first + "\",\"amount\":616000000}}";
        assertThat(webhook(body, PaystackSignature.sign(body.getBytes(StandardCharsets.UTF_8), paystackSecretKey))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(queryString("SELECT status FROM refunds WHERE paystack_refund_id = 'R11'")).isEqualTo("PROCESSED");
        assertThat(queryString("SELECT review_reason IS NOT NULL FROM payments WHERE reference = '" + first + "'"))
                .as("the developer's share is still owed back").isEqualTo("t");
        assertThat(get("/api/portal/finance/transactions", officer, String.class).getBody())
                .as("a rejected payment never comes back to be verified").doesNotContain(first);
        assertThat(get("/api/admin/refunds/due", admin, String.class).getBody()).contains(first, "\"amount\":1540000");
    }

    // --- instalment plans, phase 5b: revocation (changeset 107) ---

    /**
     * The developer revokes a plan the buyer stopped paying: only once an
     * instalment is unpaid as long as the purchase's own terms say (3 months
     * here), only by the Executive Director, only after the notice runs out.
     * Then it ends exactly as a withdrawal: plot back on sale, the buyer's
     * part of the deposit owed back, the developer's share theirs.
     */
    @Test
    void aDeveloperRevokesAPlanOnlyOnceTheTermsAreMetAndTheNoticeHasRunOut() {
        declareRefundTerms("{\"deductionPct\":20.00,\"processingDays\":90,\"appliesTo\":\"amount_paid\"}");
        declareDefaultTerms("[]", null);
        String before = plotStatus(plotId);
        TransactionDto purchase = onPlanPurchase(SHORT_PLAN);
        assertThat(queryString("SELECT revocation_after_months || '/' || revocation_notice_days FROM transactions "
                + "WHERE id = '" + purchase.id() + "'")).as("copied when the purchase opened").isEqualTo("3/30");
        String director = seller.token();
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));

        assertThat(revocation(director, purchase, "", "{\"reason\":\"Behind\"}").getBody())
                .as("nothing is late").contains("REVOCATION_NOT_DUE", "Nothing is overdue now");
        execute("UPDATE transaction_instalments SET due_date = current_date - 40 WHERE transaction_id = '"
                + purchase.id() + "' AND sequence = 1");
        ResponseEntity<String> tooSoon = revocation(director, purchase, "", "{\"reason\":\"Behind\"}");
        assertThat(tooSoon.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tooSoon.getBody()).as("in arrears, but not 3 months").contains("REVOCATION_NOT_DUE",
                "reaches that on " + today.minusDays(40).plusMonths(3));

        execute("UPDATE transaction_instalments SET due_date = current_date - 100 WHERE transaction_id = '"
                + purchase.id() + "' AND sequence = 1");
        String officer = staffWithRole(seller.id(), "finance_officer", null);
        assertThat(revocation(officer, purchase, "", "{\"reason\":\"Behind\"}").getStatusCode())
                .as("finance can't revoke").isEqualTo(HttpStatus.FORBIDDEN);
        UUID branch = UUID.fromString(queryString("SELECT id FROM branches WHERE organization_id = '" + seller.id()
                + "' LIMIT 1"));
        ResponseEntity<String> narrowed = revocation(staffWithRole(seller.id(), "executive_director", branch),
                purchase, "", "{\"reason\":\"Behind\"}");
        assertThat(narrowed.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(narrowed.getBody()).contains("REVOCATION_REQUIRES_COMPANY_WIDE_SCOPE");
        assertThat(get("/api/portal/plans/" + purchase.id() + "/revocation", director, String.class).getBody())
                .contains("\"revocableNow\":true", "\"afterMonthsUnpaid\":3", "\"noticeDays\":30",
                        "\"refund\":5280000", "\"developerKeeps\":1320000");

        ResponseEntity<String> given = revocation(director, purchase, "",
                "{\"reason\":\"Instalment 1 unpaid since the summer.\"}");
        assertThat(given.getStatusCode()).as(given.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(given.getBody()).contains("\"status\":\"notice\"", "\"deadline\":\"" + today.plusDays(30) + "\"");
        assertThat(plotStatus(plotId)).as("notice changes nothing yet").isEqualTo("ON_PLAN");
        TransactionDto told = get("/api/checkout/transactions/" + purchase.id(), buyer.token(), TransactionDto.class)
                .getBody();
        assertThat(told.standing().revocation().notice().deadline()).isEqualTo(today.plusDays(30));
        assertThat(told.schedule().lateTerms().revocation().afterMonths()).isEqualTo(3);
        verify(alerts, atLeastOnce()).planRevocation(argThat(a -> a.stage() == SettlementAlertSender.RevocationStage.NOTICE
                && "5280000.00".equals(a.refund()) && "7700000.00".equals(a.totalDue())));

        assertThat(revocation(director, purchase, "", "{\"reason\":\"Again\"}").getBody())
                .contains("REVOCATION_ALREADY_NOTIFIED");
        assertThat(revocation(director, purchase, "/confirm", null).getBody())
                .as("the buyer still has time").contains("REVOCATION_NOTICE_RUNNING");

        execute("UPDATE plan_revocations SET deadline = current_date - 1 WHERE transaction_id = '" + purchase.id() + "'");
        ResponseEntity<String> revoked = revocation(director, purchase, "/confirm", null);
        assertThat(revoked.getStatusCode()).as(revoked.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode settled = objectMapper.readTree(revoked.getBody());
        assertThat(settled.path("status").asString()).as("the response reads the purchase as it now is")
                .isEqualTo("revoked");
        assertThat(settled.path("revocableNow").asBoolean()).isFalse();
        assertThat(settled.path("settlement").path("refund").decimalValue()).isEqualByComparingTo("5280000");

        assertThat(queryString("SELECT status || '/' || revocation_reason FROM transactions WHERE id = '"
                + purchase.id() + "'")).isEqualTo("REVOKED/Instalment 1 unpaid since the summer.");
        assertThat(plotStatus(plotId)).as("back on sale as it was").isEqualTo(before);
        assertThat(queryString("SELECT status FROM owned_plots WHERE transaction_id = '" + purchase.id() + "'"))
                .isEqualTo("REVOKED");
        assertThat(queryString("SELECT status FROM plan_revocations WHERE transaction_id = '" + purchase.id() + "'"))
                .isEqualTo("REVOKED");
        assertThat(queryString("SELECT string_agg(purpose || '/' || refund_amount, ',') FROM payments "
                + "WHERE transaction_id = '" + purchase.id() + "' AND refund_requested_at IS NOT NULL"))
                .as("exactly a withdrawal's figures").isEqualTo("DEPOSIT/5280000.00");
        assertThat(documents(buyer).items()).filteredOn(d -> "provisional_allocation_letter".equals(d.type()))
                .singleElement().satisfies(d -> assertThat(d.status()).isEqualTo("revoked"));
        assertThat(portfolio(buyer).items().getFirst().status()).isEqualTo("revoked");
        verify(alerts, atLeastOnce()).planRevocation(argThat(a -> a.stage() == SettlementAlertSender.RevocationStage.REVOKED));
        assertThat(get("/api/transactions/" + purchase.id() + "/withdrawal", buyer.token(), String.class).getBody())
                .contains("\"status\":\"revoked\"", "\"refund\":5280000");

        String admin = payoutAdmin(true);
        assertThat(get("/api/admin/refunds/due", admin, String.class).getBody()).contains("\"amount\":5280000");
        approvedPayoutAccount(seller.id(), "RCP_seller");
        assertThat(readyFor(admin, purchase)).as("the deposit's share is still the developer's; nothing is released")
                .contains("\"trancheAmount\":1320000").doesNotContain("\"tranche\":\"release\"");
        assertThat(revocation(director, purchase, "/confirm", null).getBody()).contains("PLAN_NOT_REVOCABLE");
        assertThat(pay(buyer, purchase.id()).getBody()).contains("TRANSACTION_NOT_PAYABLE");
    }

    /**
     * Paying ends the notice — but only once finance verifies it. While the
     * payment waits for finance the revocation can't be confirmed; once
     * verified, the notice is cleared and the plan carries on.
     */
    @Test
    void aBuyerWhoCatchesUpEndsTheNoticeOnceFinanceVerifies() {
        declareDefaultTerms("[]", null);
        TransactionDto purchase = onPlanPurchase(SHORT_PLAN);
        execute("UPDATE transaction_instalments SET due_date = current_date - 100 WHERE transaction_id = '"
                + purchase.id() + "' AND sequence = 1");
        String director = seller.token();
        assertThat(revocation(director, purchase, "", "{\"reason\":\"Behind\"}").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        String first = payPlan(purchase, false);
        paystackSays(first, "success", 770_000_000L, "NGN", "Approved");
        confirm(buyer, first);
        execute("UPDATE plan_revocations SET deadline = current_date - 1 WHERE transaction_id = '" + purchase.id() + "'");
        assertThat(revocation(director, purchase, "/confirm", null).getBody())
                .as("a payment waiting for finance counts as paid")
                .contains("REVOCATION_NOT_DUE", "waiting for your finance team");
        assertThat(queryString("SELECT status FROM plan_revocations WHERE transaction_id = '" + purchase.id() + "'"))
                .as("not cleared until verified").isEqualTo("NOTICE");

        String officer = staffWithRole(seller.id(), "finance_officer", null);
        assertThat(financeAction(officer, purchase.id(), "verify", null).getBody()).contains("\"status\":\"on_plan\"");
        assertThat(queryString("SELECT status || '/' || (closed_by IS NULL) FROM plan_revocations WHERE transaction_id = '"
                + purchase.id() + "'")).isEqualTo("CLEARED/true");
        verify(alerts, atLeastOnce()).planRevocation(argThat(a -> a.stage() == SettlementAlertSender.RevocationStage.CLEARED));
        assertThat(revocation(director, purchase, "/confirm", null).getBody()).contains("NO_OPEN_REVOCATION");
        assertThat(plotStatus(plotId)).isEqualTo("ON_PLAN");
    }

    /** The developer can withdraw a notice; a buyer who withdraws while one runs overtakes it. */
    @Test
    void aNoticeCanBeCancelledOrOvertakenByTheBuyersWithdrawal() {
        declareDefaultTerms("[]", null);
        TransactionDto purchase = onPlanPurchase(SHORT_PLAN);
        execute("UPDATE transaction_instalments SET due_date = current_date - 100 WHERE transaction_id = '"
                + purchase.id() + "' AND sequence = 1");
        String director = seller.token();
        revocation(director, purchase, "", "{\"reason\":\"Behind\"}");
        ResponseEntity<String> cancelled = revocation(director, purchase, "/cancel", "{\"note\":\"Agreed a date.\"}");
        assertThat(cancelled.getStatusCode()).as(cancelled.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody()).contains("\"status\":\"cancelled\"", "Agreed a date.");
        verify(alerts, atLeastOnce()).planRevocation(argThat(a -> a.stage() == SettlementAlertSender.RevocationStage.CANCELLED
                && "Agreed a date.".equals(a.note())));
        assertThat(revocation(director, purchase, "/cancel", null).getBody()).contains("NO_OPEN_REVOCATION");

        assertThat(revocation(director, purchase, "", "{\"reason\":\"Still behind\"}").getStatusCode())
                .as("a fresh notice after a cancelled one").isEqualTo(HttpStatus.OK);
        String quote = get("/api/transactions/" + purchase.id() + "/withdrawal", buyer.token(), String.class).getBody();
        Matcher refund = Pattern.compile("\"refund\":([0-9.]+)").matcher(quote);
        assertThat(refund.find()).isTrue();
        assertThat(restTemplate.exchange("/api/transactions/" + purchase.id() + "/withdrawal", HttpMethod.POST,
                entity(buyer.token(), "{\"refund\":" + refund.group(1) + "}"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT string_agg(status, ',' ORDER BY created_at) FROM plan_revocations "
                + "WHERE transaction_id = '" + purchase.id() + "'")).isEqualTo("CANCELLED,WITHDRAWN");
    }

    // --- instalment plans, phase 5c: restructuring (changeset 108) ---

    /**
     * Instalment 1 is 100 days late (5% reached, ₦385,000) and a notice of
     * revocation is running. The buyer asks; finance proposes 4 monthly
     * instalments of what's owed — ₦15.4M, unchanged — with the penalty carried
     * onto the first; the buyer accepts the letter. The new schedule is in
     * force (version 2, version 1 kept), nothing is late, the notice clears,
     * and paying the first charges the instalment plus the carried penalty.
     */
    @Test
    void aBuyerRestructuresWhatTheyOweAndThePenaltyAlreadyReachedIsKept() {
        declareDefaultTerms("[{\"monthsLate\":1,\"penaltyPct\":5.00}]", false);
        TransactionDto purchase = onPlanPurchase(SHORT_PLAN);
        execute("UPDATE transaction_instalments SET due_date = current_date - 100 WHERE transaction_id = '"
                + purchase.id() + "' AND sequence = 1");
        assertThat(revocation(seller.token(), purchase, "", "{\"reason\":\"Behind\"}").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String route = "/api/transactions/" + purchase.id() + "/restructure";
        String officer = staffWithRole(seller.id(), "finance_officer", null);
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
        // The developer's Executive Directors are emailed; only active accounts receive mail.
        execute("UPDATE users SET status = 'ACTIVE' WHERE tenant_id = '" + seller.id() + "'");

        ResponseEntity<String> asked = restTemplate.exchange(route, HttpMethod.POST,
                entity(buyer.token(), "{\"reason\":\"Lost my job\",\"preferredInstalments\":4}"), String.class);
        assertThat(asked.getStatusCode()).as(asked.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(asked.getBody()).contains("\"status\":\"requested\"");
        assertThat(restTemplate.exchange(route, HttpMethod.POST, entity(buyer.token(), "{\"reason\":\"Again\"}"),
                String.class).getBody()).contains("RESTRUCTURE_ALREADY_OPEN");
        assertThat(get("/api/portal/finance/restructures", officer, String.class).getBody())
                .contains(purchase.id().toString(), "Lost my job");
        verify(alerts, atLeastOnce()).planRestructure(argThat(a -> a.toDeveloper()
                && a.stage() == SettlementAlertSender.RestructureStage.REQUESTED));

        String portal = "/api/portal/plans/" + purchase.id() + "/restructure";
        String terms = "{\"instalmentCount\":4,\"frequency\":\"monthly\",\"firstDueDate\":\"" + today.plusDays(30) + "\"}";
        assertThat(restTemplate.exchange(portal + "/preview", HttpMethod.POST, entity(officer,
                "{\"instalmentCount\":4,\"frequency\":\"monthly\",\"firstDueDate\":\"" + today.plusMonths(4) + "\"}"),
                String.class).getBody()).contains("INVALID_RESTRUCTURE_TERMS");
        String preview = restTemplate.exchange(portal + "/preview", HttpMethod.POST, entity(officer, terms),
                String.class).getBody();
        assertThat(preview).contains("\"balance\":15400000", "\"carriedPenalty\":385000", "\"paidInstalments\":0",
                "\"amount\":3850000");

        ResponseEntity<String> proposed = restTemplate.exchange(portal + "/propose", HttpMethod.POST,
                entity(officer, terms), String.class);
        assertThat(proposed.getStatusCode()).as(proposed.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode open = objectMapper.readTree(proposed.getBody()).path("open");
        assertThat(open.path("status").asString()).isEqualTo("proposed");
        assertThat(open.path("proposal").path("letterNumber").asString()).startsWith("HT-RST-");
        assertThat(open.path("proposal").path("expiresOn").asString()).isEqualTo(today.plusDays(14).toString());
        UUID restructureId = UUID.fromString(open.path("id").asString());
        assertThat(documents(buyer).items()).filteredOn(d -> "restructure_letter".equals(d.type()))
                .singleElement().satisfies(d -> assertThat(d.status()).isEqualTo("issued"));
        assertThat(plotStatus(plotId)).as("nothing changes until the buyer accepts").isEqualTo("ON_PLAN");

        assertThat(restTemplate.exchange(route + "/accept", HttpMethod.POST,
                entity(buyer.token(), "{\"restructureId\":\"" + UUID.randomUUID() + "\"}"), String.class).getBody())
                .contains("RESTRUCTURE_NOT_FOUND");
        ResponseEntity<String> accepted = restTemplate.exchange(route + "/accept", HttpMethod.POST,
                entity(buyer.token(), "{\"restructureId\":\"" + restructureId + "\"}"), String.class);
        assertThat(accepted.getStatusCode()).as(accepted.getBody()).isEqualTo(HttpStatus.OK);

        assertThat(queryString("SELECT schedule_version || '/' || (SELECT count(*) FROM transaction_instalments i "
                + "WHERE i.transaction_id = t.id) FROM transactions t WHERE id = '" + purchase.id() + "'"))
                .as("version 2 in force; version 1's two rows kept").isEqualTo("2/6");
        TransactionDto after = get("/api/checkout/transactions/" + purchase.id(), buyer.token(), TransactionDto.class)
                .getBody();
        assertThat(after.schedule().version()).isEqualTo(2);
        assertThat(after.schedule().instalments()).extracting(PlanSchedule.Instalment::sequence)
                .containsExactly(1, 2, 3, 4);
        assertThat(after.schedule().instalments().getFirst().carriedPenalty()).isEqualByComparingTo("385000");
        assertThat(after.standing().status()).as("lateness restarts on the new dates").isEqualTo("on_track");
        assertThat(queryString("SELECT status FROM plan_revocations WHERE transaction_id = '" + purchase.id() + "'"))
                .as("nothing is late, so the notice ends").isEqualTo("CLEARED");
        assertThat(documents(buyer).items()).filteredOn(d -> "restructure_letter".equals(d.type()))
                .singleElement().satisfies(d -> assertThat(d.status()).isEqualTo("accepted"));
        verify(alerts, atLeastOnce()).planRestructure(argThat(a -> !a.toDeveloper()
                && a.stage() == SettlementAlertSender.RestructureStage.ACCEPTED));

        String first = payPlan(purchase, false);
        assertThat(queryString("SELECT amount || '/' || penalty_amount || '/' || instalment_sequence FROM payments "
                + "WHERE reference = '" + first + "'")).as("the instalment plus the penalty carried onto it")
                .isEqualTo("4235000.00/385000.00/1");
    }

    /**
     * A proposal lapses: after its 14 days (the daily job), and at once when a
     * payment changes what is owed. Withdrawing one keeps the buyer's request
     * open for a fresh proposal; a rejection needs a reason the buyer reads.
     */
    @Test
    void aProposalLapsesOrIsWithdrawnAndTheBuyersRequestIsAnswered() {
        TransactionDto purchase = onPlanPurchase(SHORT_PLAN);
        String route = "/api/transactions/" + purchase.id() + "/restructure";
        String portal = "/api/portal/plans/" + purchase.id() + "/restructure";
        String officer = staffWithRole(seller.id(), "finance_officer", null);
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
        String terms = "{\"instalmentCount\":3,\"frequency\":\"monthly\",\"firstDueDate\":\"" + today.plusDays(20) + "\"}";
        String ask = "{\"reason\":\"Please\"}";

        restTemplate.exchange(route, HttpMethod.POST, entity(buyer.token(), ask), String.class);
        UUID first = proposedId(restTemplate.exchange(portal + "/propose", HttpMethod.POST, entity(officer, terms),
                String.class));
        execute("UPDATE plan_restructures SET expires_on = current_date - 1 WHERE id = '" + first + "'");
        assertThat(restTemplate.exchange(route + "/accept", HttpMethod.POST,
                entity(buyer.token(), "{\"restructureId\":\"" + first + "\"}"), String.class).getBody())
                .contains("RESTRUCTURE_LAPSED");
        instalmentReminders.run(today);
        assertThat(queryString("SELECT status FROM plan_restructures WHERE id = '" + first + "'")).isEqualTo("LAPSED");
        verify(alerts, atLeastOnce()).planRestructure(argThat(a -> a.stage() == SettlementAlertSender.RestructureStage.LAPSED));

        restTemplate.exchange(route, HttpMethod.POST, entity(buyer.token(), ask), String.class);
        UUID second = proposedId(restTemplate.exchange(portal + "/propose", HttpMethod.POST, entity(officer, terms),
                String.class));
        ResponseEntity<String> withdrawn = restTemplate.exchange(portal + "/withdraw", HttpMethod.POST,
                entity(officer, "{\"note\":\"Better terms coming\"}"), String.class);
        assertThat(withdrawn.getStatusCode()).as(withdrawn.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(withdrawn.getBody()).path("open").path("status").asString())
                .as("the buyer's request carries on").isEqualTo("requested");
        assertThat(queryString("SELECT status FROM plan_restructures WHERE id = '" + second + "'")).isEqualTo("WITHDRAWN");

        UUID third = proposedId(restTemplate.exchange(portal + "/propose", HttpMethod.POST, entity(officer, terms),
                String.class));
        String paid = payPlan(purchase, false);
        paystackSays(paid, "success", 770_000_000L, "NGN", "Approved");
        confirm(buyer, paid);
        assertThat(restTemplate.exchange(route + "/accept", HttpMethod.POST,
                entity(buyer.token(), "{\"restructureId\":\"" + third + "\"}"), String.class).getBody())
                .as("a payment waiting for finance must be settled first").contains("PAYMENT_AWAITING_FINANCE");
        assertThat(financeAction(officer, purchase.id(), "verify", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT status FROM plan_restructures WHERE id = '" + third + "'"))
                .as("what is owed changed under it").isEqualTo("LAPSED");

        restTemplate.exchange(route, HttpMethod.POST, entity(buyer.token(), ask), String.class);
        assertThat(restTemplate.exchange(portal + "/reject", HttpMethod.POST, entity(officer, "{}"), String.class)
                .getStatusCode()).as("a reason is required").isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> rejected = restTemplate.exchange(portal + "/reject", HttpMethod.POST,
                entity(officer, "{\"reason\":\"12 months is the most we offer\"}"), String.class);
        assertThat(rejected.getBody()).contains("\"status\":\"rejected\"", "12 months is the most we offer");
        assertThat(queryString("SELECT schedule_version FROM transactions WHERE id = '" + purchase.id() + "'"))
                .as("nothing was ever accepted").isEqualTo("1");
    }

    // --- penalty waivers (changeset 109) ---

    /**
     * Instalment 1 is 40 days late: 5% (₦385,000). Finance waives ₦285,000 —
     * the buyer now sees ₦100,000 owed. At 100 days the 10% step (₦770,000)
     * is owed less the ₦285,000 waived: paying charges ₦485,000. Once paid,
     * the penalty can't be waived; nor can more than is owed, nor one that
     * doesn't exist.
     */
    @Test
    void aDeveloperWaivesPartOfAPenaltyAndAHigherStepIsOwedBeyondIt() {
        declareDefaultTerms("[{\"monthsLate\":1,\"penaltyPct\":5.00},{\"monthsLate\":3,\"penaltyPct\":10.00}]", false);
        TransactionDto purchase = onPlanPurchase(SHORT_PLAN);
        execute("UPDATE transaction_instalments SET due_date = current_date - 40 WHERE transaction_id = '"
                + purchase.id() + "' AND sequence = 1");
        String route = "/api/portal/plans/" + purchase.id() + "/penalty-waivers";
        String officer = staffWithRole(seller.id(), "finance_officer", null);

        assertThat(get(route, officer, String.class).getBody())
                .contains("\"instalmentSequence\":1", "\"reached\":385000", "\"outstanding\":385000");
        ResponseEntity<String> tooMuch = restTemplate.exchange(route, HttpMethod.POST, entity(officer,
                "{\"instalmentSequence\":1,\"amount\":400000,\"reason\":\"Hospital\"}"), String.class);
        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooMuch.getBody()).contains("WAIVER_TOO_LARGE");
        assertThat(restTemplate.exchange(route, HttpMethod.POST, entity(officer,
                "{\"instalmentSequence\":2,\"amount\":1000,\"reason\":\"Hospital\"}"), String.class).getBody())
                .as("instalment 2 isn't late").contains("PENALTY_NOT_WAIVABLE");

        ResponseEntity<String> waived = restTemplate.exchange(route, HttpMethod.POST, entity(officer,
                "{\"instalmentSequence\":1,\"amount\":285000,\"reason\":\"The buyer was in hospital.\"}"),
                String.class);
        assertThat(waived.getStatusCode()).as(waived.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(waived.getBody()).contains("\"waived\":285000", "\"outstanding\":100000",
                "The buyer was in hospital.");
        verify(alerts, atLeastOnce()).penaltyWaived(argThat(a -> "285000.00".equals(a.waived())
                && "100000.00".equals(a.stillOwed())));
        PlanStanding.InstalmentStatus first = get("/api/checkout/transactions/" + purchase.id(), buyer.token(),
                TransactionDto.class).getBody().standing().instalments().getFirst();
        assertThat(first.penalty().amount()).as("what the buyer sees is net of the waiver").isEqualByComparingTo("100000");
        assertThat(first.penaltyWaived()).isEqualByComparingTo("285000");
        assertThat(portfolio(buyer).items().getFirst().arrears().penaltyAmount()).isEqualByComparingTo("100000");

        execute("UPDATE transaction_instalments SET due_date = current_date - 100 WHERE transaction_id = '"
                + purchase.id() + "' AND sequence = 1");
        assertThat(get(route, officer, String.class).getBody()).as("10% now, less what was waived")
                .contains("\"reached\":770000", "\"outstanding\":485000");
        String reference = payPlan(purchase, false);
        assertThat(queryString("SELECT amount || '/' || penalty_amount FROM payments WHERE reference = '" + reference
                + "'")).isEqualTo("8185000.00/485000.00");
        paystackSays(reference, "success", 818_500_000L, "NGN", "Approved");
        confirm(buyer, reference);
        assertThat(restTemplate.exchange(route, HttpMethod.POST, entity(officer,
                "{\"instalmentSequence\":1,\"amount\":1000,\"reason\":\"Late\"}"), String.class).getBody())
                .as("paid with the payment now waiting for finance").contains("PENALTY_NOT_WAIVABLE", "already paid");
    }

    private UUID proposedId(ResponseEntity<String> proposed) {
        assertThat(proposed.getStatusCode()).as(proposed.getBody()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(objectMapper.readTree(proposed.getBody()).path("open").path("id").asString());
    }

    private ResponseEntity<String> revocation(String token, TransactionDto purchase, String action, String body) {
        return restTemplate.exchange("/api/portal/plans/" + purchase.id() + "/revocation" + action, HttpMethod.POST,
                entity(token, body), String.class);
    }

    /** This purchase's row on the payouts ready list, as JSON — other tests' sales share the list. */
    private String readyFor(String admin, TransactionDto purchase) {
        for (JsonNode row : objectMapper.readTree(get("/api/admin/payouts/ready", admin, String.class).getBody())) {
            if (purchase.id().toString().equals(row.path("transactionId").asString())) {
                return row.toString();
            }
        }
        throw new AssertionError("Purchase " + purchase.id() + " is not on the ready list");
    }

    /** Refund terms the plan purchase copies when it opens; the letter approval is renewed, as a disclosure change lapses it. */
    private void declareRefundTerms(String json) {
        ResponseEntity<String> response = restTemplate.exchange("/api/portal/estates/" + estateId + "/refund-terms",
                HttpMethod.PUT, entity(seller.token(), json), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        execute(LetterApprovalFixture.approvedLetterSql(estateId));
    }

    private List<String> remindersFor(TransactionDto purchase) {
        String stages = queryString("SELECT string_agg(stage, ',' ORDER BY created_at) FROM instalment_reminders "
                + "WHERE transaction_id = '" + purchase.id() + "'");
        return stages == null ? List.of() : List.of(stages.split(","));
    }

    private void declareDefaultTerms(String tiersJson, Boolean refundable) {
        ResponseEntity<String> response = restTemplate.exchange("/api/portal/estates/" + estateId + "/default-terms",
                HttpMethod.PUT, entity(seller.token(), defaultTermsJson(tiersJson, refundable)), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        // A disclosure change lapses the letter approval (and the listing); approve it again.
        execute(LetterApprovalFixture.approvedLetterSql(estateId));
    }

    private static String defaultTermsJson(String tiersJson, Boolean refundable) {
        return """
                {"revocationAfterMonths":3,"revocationNoticeDays":30,"transferRequiresConsent":true,
                 "penaltyTiers":%s%s}
                """.formatted(tiersJson, refundable == null ? "" : ",\"penaltiesRefundable\":" + refundable);
    }

    /** IC-1: an instalment purchase names one of the estate's declared plans; the buyer never picks a month count. */
    @Test
    void anInstalmentPurchaseMustNameAPlanTheEstateOffersNow() {
        UUID planId = declareTwelveMonthPlan();
        ReservationDto held = reserve(buyer, plotId);

        assertThat(openRaw(held.id(), "installment", null, null).getBody()).as("no plan named")
                .contains("INVALID_PAYMENT_PLAN");
        assertThat(openRaw(held.id(), "installment", 12, planId).getBody()).as("a month count from the buyer")
                .contains("INVALID_PAYMENT_PLAN");
        assertThat(openRaw(held.id(), "outright", null, planId).getBody()).as("a plan on an outright purchase")
                .contains("INVALID_PAYMENT_PLAN");
        ResponseEntity<String> unknown = openRaw(held.id(), "installment", null, UUID.randomUUID());
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(unknown.getBody()).contains("PLAN_NOT_OFFERED");
        assertThat(queryString("SELECT count(*) FROM transactions WHERE reservation_id = '" + held.id() + "'"))
                .isEqualTo("0");
    }

    /**
     * IC-1 + PL-3: the schedule is worked out from the agreed price when the
     * purchase opens, due dates from the offer date, and fixed: redeclaring the
     * estate's plans afterwards changes nothing. The offer states all of it.
     */
    @Test
    void anInstalmentPurchaseFixesItsScheduleAndTheOfferStatesIt() {
        UUID planId = declareTwelveMonthPlan();
        TransactionDto purchase = openOnPlan(buyer, reserve(buyer, plotId).id(), planId);

        PlanSchedule schedule = purchase.schedule();
        assertThat(purchase.installmentMonths()).as("from the plan").isEqualTo(12);
        assertThat(schedule.planName()).isEqualTo("12-month plan");
        assertThat(schedule.planTotal()).as("₦20M + 10%").isEqualByComparingTo("22000000");
        assertThat(schedule.premium()).isEqualByComparingTo("2000000");
        assertThat(schedule.deposit()).as("30% of the plan total").isEqualByComparingTo("6600000");
        assertThat(schedule.instalments()).hasSize(12);
        assertThat(schedule.instalments().getFirst().amount()).isEqualByComparingTo("1283333.33");
        assertThat(schedule.instalments().getLast().amount()).as("the leftover kobo")
                .isEqualByComparingTo("1283333.37");
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
        assertThat(schedule.instalments()).extracting(PlanSchedule.Instalment::dueDate)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 12).mapToObj(today::plusMonths).toList());

        declarePlans("[]");
        assertThat(get("/api/checkout/transactions/" + purchase.id(), buyer.token(), TransactionDto.class).getBody()
                .schedule()).as("redeclaring the estate's plans doesn't touch this purchase")
                .usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(schedule);

        String offer = "FROM offer_letters WHERE id = '" + purchase.offer().id() + "'";
        assertThat(queryString("SELECT array_to_string(price_clauses, '|') " + offer))
                .contains("That the price of the plot, paid in full, is ₦20,000,000.00",
                        "That you have chosen the 12-month plan: ₦22,000,000.00 in all (₦2,000,000.00 more than "
                                + "paying in full), made up of a deposit of ₦6,600,000.00",
                        "12 monthly instalments of ₦1,283,333.33 (the last ₦1,283,333.37)",
                        // A rule ("on the 24th of each month, from …") or, for a month-end start, every date.
                        "That the instalments fall due on ", DocumentFormats.date(today.plusMonths(1)),
                        DocumentFormats.date(today.plusMonths(12)),
                        "your total commitment on this plan is ₦22,000,000.00",
                        "is in arrears; no late-payment penalty is declared");
        assertThat(acceptOffer(buyer, purchase.offer().id()).getStatusCode()).isEqualTo(HttpStatus.OK);
        paystackReturnsALink();
        assertThat(pay(buyer, purchase.id()).getStatusCode().is2xxSuccessful()).as("the deposit is payable").isTrue();
        assertThat(queryString("SELECT amount FROM payments WHERE transaction_id = '" + purchase.id() + "'"))
                .as("the deposit, not the full price").startsWith("6600000.");
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

    /** Opens the purchase and accepts its offer letter, as a buyer must before paying. */
    private TransactionDto createTransaction(Buyer who, UUID reservationId, String plan, Integer months) {
        TransactionDto transaction = openPurchase(who, reservationId, plan, months);
        assertThat(acceptOffer(who, transaction.offer().id()).getStatusCode()).isEqualTo(HttpStatus.OK);
        return transaction;
    }

    private TransactionDto openPurchase(Buyer who, UUID reservationId, String plan, Integer months) {
        ResponseEntity<TransactionDto> response = restTemplate.exchange(
                "/api/checkout/transactions", HttpMethod.POST,
                entity(who.token(), body(reservationId, plan, months)), TransactionDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<String> acceptOffer(Buyer who, UUID offerId) {
        return restTemplate.exchange("/api/documents/offer-letters/" + offerId + "/accept", HttpMethod.POST,
                entity(who.token(), null), String.class);
    }

    /** 30% deposit, 12 monthly, 10% premium on this class's estate; returns the plan's id. */
    private UUID declareTwelveMonthPlan() {
        String body = declarePlans("""
                [{"name":"12-month plan","depositPct":30,"instalmentCount":12,"frequency":"monthly","premiumPct":10}]
                """);
        Matcher id = Pattern.compile("\"plans\":\\[\\{\"id\":\"([0-9a-f-]{36})\"").matcher(body);
        assertThat(id.find()).as(body).isTrue();
        return UUID.fromString(id.group(1));
    }

    private String declarePlans(String plansJson) {
        ResponseEntity<String> response = restTemplate.exchange("/api/portal/estates/" + estateId + "/payment-plans",
                HttpMethod.PUT, entity(seller.token(), "{\"plans\":" + plansJson + "}"), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** 30% deposit, 12 monthly instalments, 10% premium: ₦20M → ₦22M, ₦6.6M deposit. */
    private static final String TWELVE_MONTH_PLAN = """
            [{"name":"12-month plan","depositPct":30,"instalmentCount":12,"frequency":"monthly","premiumPct":10}]
            """;

    /** 30% deposit, 2 monthly instalments, 10% premium: ₦20M → ₦22M, ₦6.6M deposit, 2 × ₦7.7M. */
    private static final String SHORT_PLAN = """
            [{"name":"Short plan","depositPct":30,"instalmentCount":2,"frequency":"monthly","premiumPct":10}]
            """;

    /** Declared, opened, offer accepted, deposit paid and verified: the plan is running. */
    private TransactionDto onPlanPurchase(String plansJson) {
        Matcher id = Pattern.compile("\"plans\":\\[\\{\"id\":\"([0-9a-f-]{36})\"").matcher(declarePlans(plansJson));
        assertThat(id.find()).isTrue();
        TransactionDto purchase = openOnPlan(buyer, reserve(buyer, plotId).id(), UUID.fromString(id.group(1)));
        assertThat(acceptOffer(buyer, purchase.offer().id()).getStatusCode()).isEqualTo(HttpStatus.OK);
        String deposit = startPayment(purchase);
        paystackSays(deposit, "success", PaystackAmounts.toKobo(purchase.schedule().deposit(), Currency.NGN), "NGN",
                "Approved");
        assertThat(confirm(buyer, deposit).getBody()).contains("\"status\":\"succeeded\"");
        assertThat(financeAction(staffWithRole(seller.id(), "finance_officer", null), purchase.id(), "verify", null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return purchase;
    }

    /** Starts the next instalment (or the balance) and returns its reference. */
    private String payPlan(TransactionDto purchase, boolean balance) {
        paystackReturnsALink();
        ResponseEntity<String> started = restTemplate.exchange("/api/transactions/" + purchase.id() + "/payments"
                + (balance ? "?balance=true" : ""), HttpMethod.POST, entity(buyer.token(), null), String.class);
        assertThat(started.getStatusCode()).as(started.getBody()).isEqualTo(HttpStatus.CREATED);
        Matcher ref = Pattern.compile("\"reference\":\"(LV-PAY-[0-9A-F]+)\"").matcher(started.getBody());
        assertThat(ref.find()).isTrue();
        return ref.group(1);
    }

    /** The 12-month plan's deposit, ₦6,600,000 on a ₦20M plot, paid and confirmed by Paystack. */
    private TransactionDto paidDeposit() {
        TransactionDto purchase = instalmentPurchase();
        String reference = startPayment(purchase);
        paystackSays(reference, "success", 660_000_000L, "NGN", "Approved");
        assertThat(confirm(buyer, reference).getBody()).contains("\"status\":\"succeeded\"");
        return purchase;
    }

    /** Opened on the 12-month plan with its offer accepted. */
    private TransactionDto instalmentPurchase() {
        TransactionDto purchase = openOnPlan(buyer, reserve(buyer, plotId).id(), declareTwelveMonthPlan());
        assertThat(acceptOffer(buyer, purchase.offer().id()).getStatusCode()).isEqualTo(HttpStatus.OK);
        return purchase;
    }

    private TransactionDto openOnPlan(Buyer who, UUID reservationId, UUID planId) {
        ResponseEntity<TransactionDto> response = restTemplate.exchange("/api/checkout/transactions", HttpMethod.POST,
                entity(who.token(), """
                        {"reservationId":"%s","intent":"development","plan":"installment","planId":"%s"}
                        """.formatted(reservationId, planId)), TransactionDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<String> openRaw(UUID reservationId, String plan, Integer months, UUID planId) {
        return restTemplate.exchange("/api/checkout/transactions", HttpMethod.POST, entity(buyer.token(), """
                {"reservationId":"%s","intent":"development","plan":"%s"%s%s}
                """.formatted(reservationId, plan, months == null ? "" : ",\"installmentMonths\":" + months,
                planId == null ? "" : ",\"planId\":\"" + planId + "\"")), String.class);
    }

    private static String body(UUID reservationId, String plan, Integer months) {
        return """
                {"reservationId":"%s","intent":"development","plan":"%s"%s}
                """.formatted(reservationId, plan,
                months == null ? "" : ",\"installmentMonths\":" + months);
    }

    // --- portfolio (changeset 088) ---

    /** Paid and waiting: shown, no equity. Verified: owned, in the same step as the plot being sold. */
    @Test
    void aPaidPurchaseIsPendingInThePortfolioAndBecomesOwnedWhenFinanceVerifies() {
        TransactionDto transaction = paidPurchase();

        OwnedPlotDto pending = portfolio(buyer).items().getFirst();
        assertThat(portfolio(buyer).total()).isOne();
        assertThat(pending.status()).isEqualTo("pending_verification");
        assertThat(pending.id()).isEqualTo(transaction.id());
        assertThat(pending.paidAmount()).as("equity waits for finance").isEqualByComparingTo("0");
        assertThat(pending.acquiredDate()).isNull();
        assertThat(pending.payments()).singleElement().satisfies(p -> {
            assertThat(p.status()).isEqualTo("pending_verification");
            assertThat(p.amount()).isEqualByComparingTo(TIER_PRICE);
        });
        PortfolioSummaryDto before = get("/api/portfolio/summary", buyer.token(), PortfolioSummaryDto.class).getBody();
        assertThat(before.awaitingVerification()).isOne();
        assertThat(before.byCurrency()).as("not owned yet").isEmpty();

        financeAction(staffWithRole(seller.id(), "finance_officer", null), transaction.id(), "verify", null);

        OwnedPlotDto owned = portfolio(buyer).items().getFirst();
        assertThat(portfolio(buyer).total()).as("the purchase became the owned plot, not a second row").isOne();
        assertThat(owned.status()).isEqualTo("completed");
        assertThat(owned.id()).isNotEqualTo(transaction.id());
        assertThat(owned.transactionId()).isEqualTo(transaction.id());
        assertThat(owned.paidAmount()).isEqualByComparingTo(TIER_PRICE);
        assertThat(owned.totalPrice()).isEqualByComparingTo(TIER_PRICE);
        assertThat(owned.basePrice()).isEqualByComparingTo(TIER_PRICE);
        assertThat(owned.acquiredDate()).isNotNull();
        assertThat(owned.estateId()).isEqualTo(estateId);
        assertThat(owned.plotId()).isEqualTo(plotId);
        assertThat(owned.plotLabel()).as("read through the buyer's own definer function").isNotBlank();
        assertThat(owned.estate()).isNotBlank();
        assertThat(owned.plan()).isEqualTo("outright");
        assertThat(owned.payments()).singleElement().satisfies(p -> assertThat(p.status()).isEqualTo("confirmed"));
        assertThat(owned.titleVerified()).as("no title declared").isFalse();

        assertThat(get("/api/portfolio/plots/" + transaction.id(), buyer.token(), OwnedPlotDto.class).getBody().id())
                .as("the pending id keeps working").isEqualTo(owned.id());
        PortfolioSummaryDto after = get("/api/portfolio/summary", buyer.token(), PortfolioSummaryDto.class).getBody();
        assertThat(after.awaitingVerification()).isZero();
        assertThat(after.byCurrency()).singleElement().satisfies(t -> {
            assertThat(t.currency()).isEqualTo("NGN");
            assertThat(t.count()).isOne();
            assertThat(t.totalValue()).isEqualByComparingTo(TIER_PRICE);
            assertThat(t.totalPaid()).isEqualByComparingTo(TIER_PRICE);
        });
    }

    /** FV-3: if ownership can't be recorded, the plot isn't sold either. */
    @Test
    void ifTheOwnedRecordCannotBeWrittenTheSaleRollsBack() {
        TransactionDto transaction = paidPurchase();
        // Someone already owns this plot: the one-owner index refuses the new record.
        execute("INSERT INTO owned_plots (id, created_at, deleted, buyer_user_id, plot_id, estate_id, seller_tenant_id, "
                + "status, plan, intent, base_price, total_price, currency, acquired_at) VALUES (gen_random_uuid(), now(), "
                + "false, '" + registerBuyer().id() + "', '" + plotId + "', '" + estateId + "', '" + seller.id()
                + "', 'COMPLETED', 'OUTRIGHT', 'DEVELOPMENT', 1, 1, 'NGN', now())");

        ResponseEntity<String> refused = financeAction(staffWithRole(seller.id(), "finance_officer", null),
                transaction.id(), "verify", null);

        assertThat(refused.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).isEqualTo("RESERVED");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'"))
                .isEqualTo("AWAITING_FINANCE");
    }

    @Test
    void lateMoneyAllocatedByFinanceBecomesAnOwnedPlotToo() {
        TransactionDto transaction = latePayment();
        assertThat(portfolio(buyer).total()).as("abandoned, under review: not the buyer's").isZero();

        assertThat(restTemplate.exchange("/api/portal/finance/late-payments/" + transaction.id() + "/allocate",
                HttpMethod.POST, entity(staffWithRole(seller.id(), "finance_officer", null), null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(portfolio(buyer).items()).singleElement().satisfies(p -> {
            assertThat(p.status()).isEqualTo("completed");
            assertThat(p.transactionId()).isEqualTo(transaction.id());
        });
    }

    @Test
    void anUnpaidHoldARejectedPurchaseAndAnotherBuyersPlotsAreNotInMyPortfolio() {
        TransactionDto transaction = outrightTransaction();
        assertThat(portfolio(buyer).total()).as("a hold isn't ownership").isZero();
        String reference = startPayment(transaction);
        paystackSays(reference, "success", 2_000_000_000L, "NGN", "Approved");
        assertThat(confirm(buyer, reference).getBody()).contains("\"status\":\"succeeded\"");

        Buyer other = verifiedBuyer();
        String officer = staffWithRole(seller.id(), "finance_officer", null);
        financeAction(officer, transaction.id(), "verify", null);
        UUID ownedId = portfolio(buyer).items().getFirst().id();

        assertThat(portfolio(other).total()).isZero();
        ResponseEntity<String> theirs = get("/api/portfolio/plots/" + ownedId, other.token(), String.class);
        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(theirs.getBody()).contains("OWNED_PLOT_NOT_FOUND");
        assertThat(get("/api/portfolio/plots?sort=cheapest", buyer.token(), String.class).getBody())
                .contains("INVALID_PARAMETER");
    }

    /** PF-2: a retired record stays in the ledger and never counts in the totals. */
    @Test
    void transferredAndSupersededRecordsAreListedButNeverCounted() {
        TransactionDto transaction = paidPurchase();
        financeAction(staffWithRole(seller.id(), "finance_officer", null), transaction.id(), "verify", null);
        execute("INSERT INTO owned_plots (id, created_at, deleted, buyer_user_id, plot_id, estate_id, seller_tenant_id, "
                + "status, plan, intent, base_price, total_price, currency, acquired_at) VALUES (gen_random_uuid(), now(), "
                + "false, '" + buyer.id() + "', '" + plotId + "', '" + estateId + "', '" + seller.id()
                + "', 'SUPERSEDED', 'OUTRIGHT', 'DEVELOPMENT', 5000000, 5000000, 'NGN', now() - interval '1 year')");

        assertThat(portfolio(buyer).items()).extracting(OwnedPlotDto::status).containsExactly("completed", "superseded");
        assertThat(get("/api/portfolio/summary", buyer.token(), PortfolioSummaryDto.class).getBody().byCurrency())
                .singleElement().satisfies(t -> {
                    assertThat(t.count()).isOne();
                    assertThat(t.totalValue()).as("the superseded equity isn't counted twice").isEqualByComparingTo(TIER_PRICE);
                });
    }

    @Test
    void theTitleStatusIsLiveAndOnlyAVerifiedTitleReadsVerified() {
        TransactionDto transaction = paidPurchase();
        financeAction(staffWithRole(seller.id(), "finance_officer", null), transaction.id(), "verify", null);
        execute(EstateTitleFixture.verifiedTitleSql(estateId, "FCT/TEST/1", "note"));

        OwnedPlotDto owned = portfolio(buyer).items().getFirst();
        assertThat(owned.titleType()).isEqualTo("C of O");
        assertThat(owned.titleStatus()).isEqualTo("verified");
        assertThat(owned.titleVerified()).isTrue();

        execute("UPDATE estate_titles SET status = 'PENDING', decided_at = NULL WHERE estate_id = '" + estateId + "'");
        assertThat(portfolio(buyer).items().getFirst().titleVerified()).isFalse();
    }

    // --- documents: receipts (changeset 089) ---

    /** RC-1/2/4: confirmed money gets exactly one receipt, downloadable and reachable from the purchase. */
    @Test
    void aConfirmedPaymentGetsOneReceiptTheBuyerCanDownload() throws Exception {
        TransactionDto transaction = paidPurchase();
        String reference = queryString("SELECT reference FROM payments WHERE transaction_id = '" + transaction.id() + "'");
        assertThat(confirm(buyer, reference).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT count(*) FROM receipts WHERE payment_reference = '" + reference + "'"))
                .as("a second confirmation issues nothing new").isEqualTo("1");

        PageResponse<DocumentDto> documents = documents(buyer);
        assertThat(documents.items()).extracting(DocumentDto::type).as("newest first: the receipt, then the offer")
                .containsExactly("receipt", "offer_letter");
        DocumentDto receipt = documents.items().getFirst();
        assertThat(receipt.type()).isEqualTo("receipt");
        assertThat(receipt.number()).matches("HT-RCT-\\d{4}-\\d{6}");
        assertThat(receipt.status()).isEqualTo("valid");
        assertThat(receipt.transactionId()).isEqualTo(transaction.id());
        assertThat(receipt.plotId()).isEqualTo(plotId);
        assertThat(receipt.qrCode()).matches("[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}");
        assertThat(receipt.verifyUrl()).endsWith("/verify/" + receipt.qrCode().replace("-", ""));

        ResponseEntity<byte[]> pdf = get(receipt.pdfUrl(), buyer.token(), byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(pdf.getHeaders().getContentDisposition().getFilename()).isEqualTo("HomeTrue-" + receipt.number() + ".pdf");
        try (PDDocument document = Loader.loadPDF(pdf.getBody())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains(receipt.number(), "₦20,000,000.00", "Twenty Million Naira Only", "Ada Buyer",
                    reference, receipt.qrCode());
        }
        ResponseEntity<byte[]> jpeg = get(receipt.jpegUrl(), buyer.token(), byte[].class);
        assertThat(jpeg.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(jpeg.getBody()).startsWith((byte) 0xFF, (byte) 0xD8);

        assertThat(get("/api/checkout/transactions/mine", buyer.token(), String.class).getBody())
                .contains("\"receiptId\":\"" + receipt.id() + "\"", "\"receiptNumber\":\"" + receipt.number() + "\"");
        assertThat(portfolio(buyer).items().getFirst().payments().getFirst().receiptId())
                .isEqualTo(receipt.id().toString());
        assertThat(restTemplate.exchange("/api/portfolio/plots/" + transaction.id() + "/documents", HttpMethod.GET,
                new HttpEntity<>(bearer(buyer.token())), new ParameterizedTypeReference<List<DocumentDto>>() {
                }).getBody()).extracting(DocumentDto::id).containsExactly(receipt.id(), transaction.offer().id());
    }

    @Test
    void anotherBuyerCannotReachMyReceipt() {
        TransactionDto transaction = paidPurchase();
        DocumentDto receipt = documents(buyer).items().getFirst();
        Buyer other = verifiedBuyer();

        assertThat(documents(other).total()).isZero();
        for (String path : List.of(receipt.pdfUrl(), receipt.jpegUrl(), "/api/documents/receipts/" + receipt.id())) {
            ResponseEntity<String> theirs = get(path, other.token(), String.class);
            assertThat(theirs.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
        }
        // A download asks for the file type only (Swagger, a browser fetch); the refusal must still be a 404
        // in the standard JSON shape, never an empty 500 from content negotiation.
        for (var download : List.of(
                Map.entry(receipt.pdfUrl(), MediaType.APPLICATION_PDF),
                Map.entry(receipt.jpegUrl(), MediaType.IMAGE_JPEG))) {
            HttpHeaders headers = bearer(other.token());
            headers.setAccept(List.of(download.getValue()));
            ResponseEntity<String> theirs = restTemplate.exchange(download.getKey(), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            assertThat(theirs.getStatusCode()).as(download.getKey()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(theirs.getBody()).as(download.getKey()).contains("DOCUMENT_NOT_FOUND");
        }
        assertThat(get("/api/portfolio/plots/" + transaction.id() + "/documents", other.token(), String.class)
                .getBody()).contains("OWNED_PLOT_NOT_FOUND");
    }

    @Test
    void aFailedOrMismatchedPaymentGetsNoReceipt() {
        TransactionDto transaction = outrightTransaction();
        String reference = startPayment(transaction);
        paystackSays(reference, "success", 1_000L, "NGN", "Approved");
        assertThat(confirm(buyer, reference).getBody()).contains("\"status\":\"mismatched\"");

        assertThat(queryString("SELECT count(*) FROM receipts WHERE payment_reference = '" + reference + "'"))
                .isEqualTo("0");
        assertThat(documents(buyer).items()).extracting(DocumentDto::type).as("only the offer")
                .containsExactly("offer_letter");
    }

    /** DV-2: anyone may check a code; nothing about money or the person beyond initials. */
    @Test
    void theVerifyPageIsPublicAndShowsNoAmountOrName() {
        paidPurchase();
        DocumentDto receipt = documents(buyer).items().getFirst();

        ResponseEntity<String> anonymous = restTemplate.getForEntity(
                "/api/documents/verify/" + receipt.qrCode().toLowerCase(), String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(anonymous.getBody()).contains("\"status\":\"valid\"", receipt.number(), "\"holderInitials\":\"A. B.\"",
                "\"issuedBy\":\"HomeTrue\"");
        assertThat(anonymous.getBody()).doesNotContain("20000000", "20,000,000", "Ada", buyer.email(),
                buyer.id().toString(), "+234");

        for (String code : List.of("0000-0000-0000-0000", "not-a-code")) {
            ResponseEntity<String> unknown = restTemplate.getForEntity("/api/documents/verify/" + code, String.class);
            assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(unknown.getBody()).contains("NOT_A_HOMETRUE_DOCUMENT");
        }
        assertThat(restTemplate.getForEntity("/api/documents", String.class).getStatusCode())
                .as("only verify is public").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** RC-5: a refund never changes the receipt; the verify page and the vault say it was returned. */
    @Test
    void aRefundedPaymentsReceiptReadsRefunded() {
        TransactionDto transaction = paidPurchase();
        DocumentDto receipt = documents(buyer).items().getFirst();
        execute("INSERT INTO refunds (id, created_at, deleted, payment_id, transaction_id, buyer_user_id, seller_tenant_id, "
                + "amount, currency, amount_kobo, status, reason, initiated_by, refunded_at) SELECT gen_random_uuid(), now(), "
                + "false, p.id, p.transaction_id, p.buyer_user_id, p.seller_tenant_id, p.amount, 'NGN', p.amount_kobo, "
                + "'PROCESSED', 'test', p.buyer_user_id, now() FROM payments p WHERE p.transaction_id = '"
                + transaction.id() + "'");

        assertThat(restTemplate.getForEntity("/api/documents/verify/" + receipt.qrCode(), String.class).getBody())
                .contains("\"status\":\"refunded\"");
        DocumentDto after = documents(buyer).items().getFirst();
        assertThat(after.status()).as("the money did arrive").isEqualTo("valid");
        assertThat(after.refundedAt()).isNotNull();
        assertThat(after.number()).isEqualTo(receipt.number());
    }

    @Test
    void theDatabaseRefusesToChangeOrDeleteAReceipt() {
        paidPurchase();
        UUID receiptId = documents(buyer).items().getFirst().id();
        assertThat(appRoleCan("UPDATE receipts SET amount = 1 WHERE id = '" + receiptId + "'")).isFalse();
        assertThat(appRoleCan("DELETE FROM receipts WHERE id = '" + receiptId + "'")).isFalse();
    }

    // --- documents: the allocation letter (changeset 095) ---

    /** Accounts from before the E.164 rule keep their number: they sign in, buy, and it prints as stored. */
    @Test
    void aBuyerWithAnOldFormatPhoneCanStillSignInBuyAndHaveItPrinted() {
        execute("UPDATE users SET phone = '0803 000 0000' WHERE id = '" + buyer.id() + "'");
        buyer = loginAgain(buyer);
        assertThat(buyer.token()).isNotBlank();

        TransactionDto transaction = paidPurchase();
        String officer = staffWithRole(seller.id(), "finance_officer", null);
        assertThat(financeAction(officer, transaction.id(), "verify", null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(queryString("SELECT array_to_string(buyer_lines, '|') FROM allocation_letters WHERE transaction_id = '"
                + transaction.id() + "'")).isEqualTo("Ada Buyer|" + LAGOS_LINES + "|0803 000 0000");
    }

    // --- documents: the offer letter (changeset 098) ---

    /** OL: "Proceed to buy" issues the offer; paying waits for the buyer to accept that exact letter. */
    @Test
    void openingAPurchaseIssuesTheOfferAndPaymentWaitsForItsAcceptance() {
        TransactionDto transaction = openPurchase(buyer, reserve(buyer, plotId).id(), "outright", null);
        OfferSummary offer = transaction.offer();
        assertThat(offer.number()).matches("HT-OFR-\\d{4}-\\d{6}");
        assertThat(offer.acceptedAt()).isNull();

        String row = "FROM offer_letters WHERE id = '" + offer.id() + "'";
        assertThat(queryString("SELECT transaction_id " + row)).isEqualTo(transaction.id().toString());
        assertThat(queryString("SELECT total_price = " + transaction.totalPrice() + " " + row)).isEqualTo("t");
        assertThat(queryString("SELECT array_to_string(price_clauses, '|') " + row))
                .startsWith("That the price of the plot is ₦20,000,000.00 (Twenty Million Naira Only).");
        assertThat(queryString("SELECT array_to_string(clauses, '|') " + row))
                .contains("The plot is for residential use only.")
                .endsWith(DisclosureClauses.PAY_ONLY_THROUGH_HOMETRUE);
        assertThat(queryString("SELECT array_to_string(buyer_lines, '|') " + row))
                .isEqualTo("Ada Buyer|" + LAGOS_LINES + "|+2348000000000");
        assertThat(queryString("SELECT signatory_name || '/' || opening " + row))
                .startsWith("Fixture Signatory/Following your application of ").contains("is pleased to offer you");

        DocumentDto listed = documents(buyer).items().stream()
                .filter(d -> d.type().equals("offer_letter")).findFirst().orElseThrow();
        assertThat(listed.status()).isEqualTo("issued");
        assertThat(listed.pdfUrl()).isEqualTo("/api/documents/offer-letters/" + offer.id() + "/pdf");

        paystackReturnsALink();
        ResponseEntity<String> refused = pay(buyer, transaction.id());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("OFFER_NOT_ACCEPTED");
        assertThat(queryString("SELECT count(*) FROM payments WHERE transaction_id = '" + transaction.id() + "'"))
                .isEqualTo("0");

        assertThat(acceptOffer(verifiedBuyer(), offer.id()).getStatusCode()).as("another buyer's offer")
                .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> accepted = acceptOffer(buyer, offer.id());
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accepted.getBody()).contains("\"status\":\"accepted\"", "\"acceptedAt\":\"");
        String acceptedAt = queryString("SELECT accepted_at FROM offer_letter_acceptances WHERE offer_letter_id = '"
                + offer.id() + "'");
        assertThat(acceptOffer(buyer, offer.id()).getStatusCode()).as("accepting again").isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT count(*) || '/' || max(accepted_at) FROM offer_letter_acceptances "
                + "WHERE offer_letter_id = '" + offer.id() + "'")).isEqualTo("1/" + acceptedAt);
        assertThat(queryString("SELECT buyer_user_id FROM offer_letter_acceptances WHERE offer_letter_id = '"
                + offer.id() + "'")).isEqualTo(buyer.id().toString());
        assertThat(queryString("SELECT count(*) FROM audit_log_entries WHERE action = 'offer_letter.accepted' "
                + "AND target_id = '" + offer.id() + "'")).isEqualTo("1");
        assertThat(get("/api/checkout/transactions/" + transaction.id(), buyer.token(), String.class).getBody())
                .contains("\"acceptedAt\":\"");

        assertThat(pay(buyer, transaction.id()).getStatusCode().is2xxSuccessful()).as("paying, once accepted").isTrue();

        String code = queryString("SELECT verification_code " + row);
        assertThat(restTemplate.getForEntity("/api/documents/verify/" + code, String.class).getBody())
                .contains("\"documentType\":\"offer_letter\"", "\"status\":\"accepted\"", "\"holderInitials\":\"A. B.\"")
                .doesNotContain("Ada", "20000000");
        assertThat(appRoleCan("UPDATE offer_letters SET buyer_name = 'x' WHERE id = '" + offer.id() + "'")).isFalse();
        assertThat(appRoleCan("DELETE FROM offer_letter_acceptances WHERE offer_letter_id = '" + offer.id() + "'"))
                .isFalse();
    }

    /** No approved, current letter: no offer can be issued, so nothing is opened and the hold stays. */
    @Test
    void noPurchaseIsOpenedWhileTheEstatesLetterIsNotApproved() {
        ReservationDto reservation = reserve(buyer, plotId);
        withdrawLetterApproval();

        ResponseEntity<String> refused = restTemplate.exchange("/api/checkout/transactions", HttpMethod.POST,
                entity(buyer.token(), body(reservation.id(), "outright", null)), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("LETTER_NOT_APPROVED");
        assertThat(queryString("SELECT count(*) FROM transactions WHERE reservation_id = '" + reservation.id() + "'"))
                .isEqualTo("0");
        assertThat(queryString("SELECT count(*) FROM offer_letters WHERE buyer_user_id = '" + buyer.id() + "'"))
                .isEqualTo("0");
        assertThat(reservationStatus(reservation.id())).isEqualTo("ACTIVE");
    }

    /** Decided with the user: the offer fixes the terms; the allocation letter copies them, not today's. */
    @Test
    void theAllocationLetterKeepsTheTermsOfTheAcceptedOffer() {
        TransactionDto transaction = outrightTransaction();
        execute(LetterApprovalFixture.approvedLetterSql(estateId, "Only bungalows may be built."));

        String reference = startPayment(transaction);
        paystackSays(reference, "success", 2_000_000_000L, "NGN", "Approved");
        assertThat(confirm(buyer, reference).getBody()).contains("\"status\":\"succeeded\"");
        assertThat(financeAction(seller.token(), transaction.id(), "verify", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        String letter = "FROM allocation_letters WHERE transaction_id = '" + transaction.id() + "'";
        assertThat(queryString("SELECT array_to_string(clauses, '|') " + letter))
                .contains("The plot is for residential use only.").doesNotContain("bungalows");
        assertThat(queryString("SELECT offer_letter_id " + letter)).isEqualTo(transaction.offer().id().toString());
        assertThat(queryString("SELECT terms_version " + letter)).isEqualTo(queryString(
                "SELECT terms_version FROM offer_letters WHERE id = '" + transaction.offer().id() + "'"));
    }

    /** An offer gives no right to the plot: when the purchase is abandoned it lapses and can't be accepted. */
    @Test
    void anAbandonedPurchasesOfferLapses() {
        TransactionDto transaction = openPurchase(buyer, reserve(buyer, plotId).id(), "outright", null);
        holdEndedMinutesAgo(transaction, 20);
        paymentSweeper.sweep();

        assertThat(plotStatus(plotId)).isEqualTo("AVAILABLE_DEV");
        ResponseEntity<String> late = acceptOffer(buyer, transaction.offer().id());
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody()).contains("OFFER_NOT_OPEN");
        assertThat(documents(buyer).items().stream().filter(d -> d.type().equals("offer_letter")).findFirst()
                .orElseThrow().status()).isEqualTo("lapsed");
    }

    // --- the buyer's address (changeset 097) ---

    @Test
    void aBuyerWithoutAnAddressCannotOpenAPurchase() {
        execute("UPDATE users SET address_line1 = NULL, address_line2 = NULL, address_city = NULL, "
                + "address_state = NULL, address_postal_code = NULL, address_country = NULL WHERE id = '"
                + buyer.id() + "'");
        ReservationDto reservation = reserve(buyer, plotId);

        ResponseEntity<String> refused = restTemplate.exchange("/api/checkout/transactions", HttpMethod.POST,
                entity(buyer.token(), body(reservation.id(), "outright", null)), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("ADDRESS_REQUIRED");
        assertThat(queryString("SELECT count(*) FROM transactions WHERE reservation_id = '" + reservation.id() + "'"))
                .isEqualTo("0");
        assertThat(reservationStatus(reservation.id())).as("the hold is untouched").isEqualTo("ACTIVE");
    }

    @Test
    void theAddressIsCheckedStoredInItsCanonicalFormAndNeverAudited() {
        Buyer newBuyer = registerBuyer();
        assertThat(get("/api/me/address", newBuyer.token(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> badState = putAddress(newBuyer, """
                {"line1":"1 Marina","city":"Lagos","state":"Lagoss","country":"NG"}""");
        assertThat(badState.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badState.getBody()).contains("UNKNOWN_STATE", "\"state\"");
        assertThat(putAddress(newBuyer, """
                {"line1":"1 Marina","city":"Lagos","state":"Lagos","country":"XX"}""").getBody())
                .contains("UNKNOWN_COUNTRY");
        assertThat(putAddress(newBuyer, """
                {"line1":"1 Marina","state":"Lagos","country":"NG"}""").getBody())
                .contains("VALIDATION_ERROR", "\"city\"");

        ResponseEntity<String> saved = putAddress(newBuyer, """
                {"line1":" 1 Marina ","line2":"","city":"Lagos Island","state":"lagos state","country":"ng"}""");
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).contains("\"line1\":\"1 Marina\"", "\"line2\":null", "\"state\":\"Lagos\"",
                "\"country\":\"NG\"", "\"countryName\":\"Nigeria\"");
        assertThat(putAddress(newBuyer, """
                {"line1":"1 Marina","city":"Lagos Island","state":"Lagos","country":"NG"}""").getStatusCode())
                .as("the same address again").isEqualTo(HttpStatus.OK);

        ResponseEntity<String> abroad = putAddress(newBuyer, """
                {"line1":"10 Downing Street","city":"London","state":"Greater London","postalCode":"SW1A 2AA",\
                "country":"GB"}""");
        assertThat(abroad.getBody()).as("free text outside Nigeria")
                .contains("\"state\":\"Greater London\"", "\"countryName\":\"United Kingdom\"");

        ResponseEntity<String> signIn = restTemplate.postForEntity("/api/auth/login",
                new LoginRequest(newBuyer.email(), PASSWORD), String.class);
        assertThat(signIn.getBody()).contains("\"address\":{", "10 Downing Street");

        String audit = "FROM audit_log_entries WHERE action = 'user.address_updated' AND target_id = '"
                + newBuyer.id() + "'";
        assertThat(queryString("SELECT count(*) " + audit)).as("added, changed; the repeat records nothing")
                .isEqualTo("2");
        assertThat(queryString("SELECT string_agg(detail, '|') " + audit))
                .doesNotContain("Marina", "Downing", "London");
    }

    @Test
    void thePurchaseKeepsTheAddressItWasOpenedWithAndTheLetterPrintsIt() {
        TransactionDto transaction = outrightTransaction();
        assertThat(putAddress(buyer, """
                {"line1":"5 New Road","city":"Abuja","state":"FCT","country":"NG"}""").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(get("/api/checkout/transactions/" + transaction.id(), buyer.token(), String.class).getBody())
                .as("the copy taken when it was opened").contains("12 Adeola Odeku Street").doesNotContain("New Road");

        String reference = startPayment(transaction);
        paystackSays(reference, "success", 2_000_000_000L, "NGN", "Approved");
        assertThat(confirm(buyer, reference).getBody()).contains("\"status\":\"succeeded\"");
        assertThat(financeAction(seller.token(), transaction.id(), "verify", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(queryString("SELECT array_to_string(buyer_lines, '|') FROM allocation_letters WHERE transaction_id = '"
                + transaction.id() + "'")).isEqualTo("Ada Buyer|" + LAGOS_LINES + "|+2348000000000");
    }

    /** AL: finance's verify issues the buyer's letter in the same step, from the approved setup. */
    @Test
    void verifyingIssuesTheBuyersAllocationLetter() {
        TransactionDto transaction = paidPurchase();
        String officer = staffWithRole(seller.id(), "finance_officer", null);
        assertThat(get("/api/portal/finance/transactions", officer, String.class).getBody())
                .contains("\"letterApproved\":true");

        ResponseEntity<String> decision = financeAction(officer, transaction.id(), "verify", null);
        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);

        DocumentDto letter = documents(buyer).items().stream()
                .filter(d -> d.type().equals("allocation_letter")).findFirst().orElseThrow();
        assertThat(letter.number()).matches("HT-ALC-\\d{4}-\\d{6}");
        assertThat(decision.getBody()).as("finance is told which letter was issued")
                .contains("\"allocationLetterId\":\"" + letter.id() + "\"")
                .contains("\"allocationLetterNumber\":\"" + letter.number() + "\"");
        assertThat(letter.transactionId()).isEqualTo(transaction.id());
        assertThat(letter.plotId()).isEqualTo(plotId);
        assertThat(letter.pdfUrl()).isEqualTo("/api/documents/allocation-letters/" + letter.id() + "/pdf");
        assertThat(documents(buyer).total()).as("the offer, the receipt and the letter").isEqualTo(3);
        assertThat(get("/api/documents/allocation-letters/" + letter.id(), buyer.token(), String.class).getBody())
                .contains(letter.number());

        String row = "FROM allocation_letters WHERE id = '" + letter.id() + "'";
        String plotNumber = queryString("SELECT plot_number FROM plots WHERE id = '" + plotId + "'");
        String estateName = queryString("SELECT name FROM estates WHERE id = '" + estateId + "'");
        assertThat(queryString("SELECT opening " + row)).contains("Plot " + plotNumber, estateName)
                .doesNotContain("{");
        assertThat(queryString("SELECT array_to_string(clauses, '|') " + row))
                .as("the approved conditions, then HomeTrue's").contains("The plot is for residential use only.")
                .endsWith(DisclosureClauses.PAY_ONLY_THROUGH_HOMETRUE);
        assertThat(queryString("SELECT buyer_name || '/' || signatory_name || '/' || template_source " + row))
                .isEqualTo("Ada Buyer/Fixture Signatory/default");
        assertThat(queryString("SELECT issued_by IS NOT NULL AND approval_id IS NOT NULL " + row)).isEqualTo("t");
        assertThat(queryString("SELECT actor_user_id IS NOT NULL FROM audit_log_entries WHERE action = "
                + "'allocation_letter.issued' AND target_id = '" + letter.id() + "'")).as("finance issued it").isEqualTo("t");
        assertThat(restTemplate.exchange("/api/portfolio/plots/" + transaction.id() + "/documents", HttpMethod.GET,
                new HttpEntity<>(bearer(buyer.token())), new ParameterizedTypeReference<List<DocumentDto>>() {
                }).getBody()).extracting(DocumentDto::type).containsExactly("allocation_letter", "receipt", "offer_letter");

        ResponseEntity<String> verified = restTemplate.getForEntity("/api/documents/verify/" + letter.qrCode(), String.class);
        assertThat(verified.getBody()).contains("\"documentType\":\"allocation_letter\"", letter.number(),
                "\"holderInitials\":\"A. B.\"", "\"status\":\"valid\"").doesNotContain("Ada", "20000000");

        Buyer other = verifiedBuyer();
        for (String path : List.of("/api/documents/allocation-letters/" + letter.id(), letter.pdfUrl(), letter.jpegUrl())) {
            assertThat(get(path, other.token(), String.class).getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(appRoleCan("UPDATE allocation_letters SET buyer_name = 'x' WHERE id = '" + letter.id() + "'")).isFalse();
        assertThat(appRoleCan("DELETE FROM allocation_letters WHERE id = '" + letter.id() + "'")).isFalse();
    }

    /** Decided with the user: no approved letter, no allocation — refused before anything is written. */
    @Test
    void aSaleIsNotAllocatedWhileTheEstatesLetterIsNotApproved() {
        TransactionDto transaction = paidPurchase();
        withdrawLetterApproval();
        String officer = staffWithRole(seller.id(), "finance_officer", null);
        assertThat(get("/api/portal/finance/transactions", officer, String.class).getBody())
                .contains("\"letterApproved\":false");

        ResponseEntity<String> refused = financeAction(officer, transaction.id(), "verify", null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("LETTER_NOT_APPROVED");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("AWAITING_FINANCE");
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).isEqualTo("RESERVED");
        assertThat(queryString("SELECT count(*) FROM allocation_letters WHERE transaction_id = '" + transaction.id() + "'")).isEqualTo("0");
        assertThat(queryString("SELECT count(*) FROM owned_plots WHERE transaction_id = '" + transaction.id() + "'")).isEqualTo("0");

        execute(LetterApprovalFixture.approvedLetterSql(estateId));
        assertThat(financeAction(officer, transaction.id(), "verify", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryString("SELECT count(*) FROM allocation_letters WHERE transaction_id = '" + transaction.id() + "'")).isEqualTo("1");
    }

    @Test
    void lateMoneyIsNotAllocatedWhileTheEstatesLetterIsNotApproved() {
        TransactionDto transaction = latePayment();
        withdrawLetterApproval();
        String officer = staffWithRole(seller.id(), "finance_officer", null);
        assertThat(get("/api/portal/finance/late-payments", officer, String.class).getBody())
                .contains("\"letterApproved\":false");

        ResponseEntity<String> refused = restTemplate.exchange("/api/portal/finance/late-payments/"
                + transaction.id() + "/allocate", HttpMethod.POST, entity(officer, null), String.class);

        assertThat(refused.getBody()).contains("LETTER_NOT_APPROVED");
        assertThat(queryString("SELECT status FROM transactions WHERE id = '" + transaction.id() + "'")).isEqualTo("ABANDONED");
        assertThat(queryString("SELECT status FROM plots WHERE id = '" + plotId + "'")).isEqualTo("AVAILABLE_DEV");
    }

    /**
     * The marketplace (SQL, changeset 095) and the portal (Java) decide
     * "approved" separately; they must agree, including when a change lapses it.
     */
    @Test
    void theMarketplaceAndThePortalAgreeWhetherTheLetterIsApproved() {
        assertThat(letterApprovedBothWays()).containsExactly(true, true);

        assertThat(restTemplate.exchange("/api/portal/estates/" + estateId + "/fees", HttpMethod.PUT,
                entity(seller.token(), "{\"fees\":[{\"feeType\":\"application\",\"amount\":10000,\"currency\":\"NGN\","
                        + "\"isFixed\":true,\"dueTrigger\":\"at_application\",\"refundable\":false,"
                        + "\"isMandatory\":true}]}"), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(letterApprovedBothWays()).as("a fee change lapses it").containsExactly(false, false);
        assertThat(get("/api/portal/estates/" + estateId + "/letter-approval", seller.token(), String.class).getBody())
                .contains("\"changedSinceApproval\":[\"fees\"]");
        assertThat(restTemplate.exchange("/api/reservations", HttpMethod.POST,
                entity(buyer.token(), "{\"plotId\":\"" + plotId + "\"}"), String.class).getBody())
                .as("off the market, so nothing can be reserved").contains("PLOT_NOT_AVAILABLE");

        execute(LetterApprovalFixture.approvedLetterSql(estateId));
        assertThat(letterApprovedBothWays()).containsExactly(true, true);
    }

    /** [the marketplace's view, the portal's letter-approval read]. */
    private List<Boolean> letterApprovedBothWays() {
        boolean sql = "t".equals(queryString(
                "SELECT letter_approved FROM marketplace_estate_eligibility WHERE estate_id = '" + estateId + "'"));
        String portal = get("/api/portal/estates/" + estateId + "/letter-approval", seller.token(), String.class).getBody();
        assertThat(portal).contains("\"approved\":");
        return List.of(sql, portal.contains("\"approved\":true"));
    }

    private void withdrawLetterApproval() {
        execute("INSERT INTO estate_letter_approvals (id, created_at, tenant_id, branch_id, estate_id, action, actor_user_id) "
                + "SELECT gen_random_uuid(), clock_timestamp(), tenant_id, branch_id, id, 'WITHDRAWN', "
                + "(SELECT id FROM users ORDER BY created_at LIMIT 1) FROM estates WHERE id = '" + estateId + "'");
    }

    private boolean appRoleCan(String sql) {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE " + APP_ROLE);
            statement.execute(sql);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private PageResponse<DocumentDto> documents(Buyer who) {
        ResponseEntity<PageResponse<DocumentDto>> response = restTemplate.exchange("/api/documents",
                HttpMethod.GET, new HttpEntity<>(bearer(who.token())), new ParameterizedTypeReference<>() {
                });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private PageResponse<OwnedPlotDto> portfolio(Buyer who) {
        ResponseEntity<PageResponse<OwnedPlotDto>> response = restTemplate.exchange("/api/portfolio/plots",
                HttpMethod.GET, new HttpEntity<>(bearer(who.token())), new ParameterizedTypeReference<>() {
                });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private Buyer verifiedBuyer() {
        Buyer newBuyer = registerBuyer();
        postJson(newBuyer, "/api/kyc",
                "{\"ninNumber\":\"12345678901\",\"ninFile\":{\"fileName\":\"nin.pdf\"}}", String.class);
        postJson(adminToken(), "/api/admin/kyc/" + newBuyer.id() + "/decision",
                "{\"decision\":\"approved\"}", String.class);
        assertThat(putAddress(newBuyer, LAGOS_ADDRESS).getStatusCode()).isEqualTo(HttpStatus.OK);
        return loginAgain(newBuyer);
    }

    private ResponseEntity<String> putAddress(Buyer who, String json) {
        return restTemplate.exchange("/api/me/address", HttpMethod.PUT, entity(who.token(), json), String.class);
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

        execute(EstatePhotoFixture.coverPhotoSql(estate.id()));
        execute(LetterApprovalFixture.approvedLetterSql(estate.id()));
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
