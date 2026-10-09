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
import com.techcomfort.landvaultbackend.payments.internal.exceptions.PaymentException;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackClient;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.techcomfort.landvaultbackend.payments.internal.paystack.PaystackSignature;
import org.springframework.beans.factory.annotation.Value;
import com.techcomfort.landvaultbackend.payments.internal.service.PaymentSweeper;
import com.techcomfort.landvaultbackend.payments.internal.service.PayoutRecorder;
import com.techcomfort.landvaultbackend.payments.internal.service.PayoutSweeper;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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

    /** Payout alerts, checked rather than sent. */
    @MockitoBean
    private SettlementAlertSender alerts;

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
    void installmentsAndOtherCurrenciesAreRefusedBeforePaystackIsCalled() {
        ReservationDto reservation = reserve(buyer, plotId);
        TransactionDto installment = createTransaction(buyer, reservation.id(), "installment", 12);
        assertThat(pay(buyer, installment.id()).getBody()).contains("PAYMENT_PLAN_NOT_SUPPORTED");

        execute("UPDATE transactions SET plan = 'OUTRIGHT', installment_months = NULL, currency = 'USD' WHERE id = '"
                + installment.id() + "'");
        assertThat(pay(buyer, installment.id()).getBody()).contains("CURRENCY_NOT_SUPPORTED");
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

    private void paystackReturnsALink() {
        when(paystack.initialize(any())).thenAnswer(call -> {
            PaystackClient.InitializeRequest request = call.getArgument(0);
            return new PaystackClient.InitializeResult("https://checkout.paystack.com/test-link", "test-link",
                    request.reference());
        });
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
