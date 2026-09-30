package com.techcomfort.landvaultbackend.identity;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.ChangePasswordRequest;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RefreshResponse;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.identity.internal.service.RefreshTokenCleanupJob;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refresh token as an {@code HttpOnly} cookie: issuing, reading,
 * rotation's grace window, the Origin check, logout and cleanup. See
 * AGENTS.md. {@code refresh_tokens} carries no RLS policy, so a superuser
 * connection hides nothing here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class RefreshTokenCookieIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    private static final String PASSWORD = "correct horse battery staple";
    private static final String ALLOWED_ORIGIN = "http://localhost:8443";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "integration-test-signing-secret-of-at-least-32-bytes");
        registry.add("app.cors.allowed-origins", () -> ALLOWED_ORIGIN);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RefreshTokenCleanupJob cleanupJob;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    // --- issuing ---

    @Test
    void loginSetsAnHttpOnlyStrictCookieAndPutsNoRefreshTokenInTheBody() {
        String email = register();
        ResponseEntity<String> login = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), String.class);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(RefreshCookies.header(login)).contains(
                "HttpOnly", "SameSite=Strict", "Path=/api/auth", "Secure",
                "Max-Age=" + Duration.ofDays(30).toSeconds());
        // Asserted on the raw JSON: a field leaking through any serializer
        // would pass a DTO-shaped check and fail this one.
        assertThat(login.getBody())
                .contains("\"token\"")
                .doesNotContain("refreshToken")
                .doesNotContain(RefreshCookies.of(login));
    }

    @Test
    void registrationSetsTheSameCookie() {
        ResponseEntity<String> registered = restTemplate.postForEntity("/api/auth/register",
                newBuyer("reg+" + UUID.randomUUID() + "@example.com"), String.class);

        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(RefreshCookies.header(registered)).contains("HttpOnly", "SameSite=Strict", "Path=/api/auth");
        assertThat(registered.getBody()).doesNotContain("refreshToken");
    }

    @Test
    void theIssuingBrowsersUserAgentIsRecordedAndTruncatedToTheColumn() {
        String email = register();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 " + "x".repeat(400));

        ResponseEntity<AuthResponse> login = restTemplate.postForEntity(
                "/api/auth/login", new HttpEntity<>(new LoginRequest(email, PASSWORD), headers), AuthResponse.class);

        assertThat(login.getStatusCode()).as("an overlong User-Agent must not fail a login").isEqualTo(HttpStatus.OK);
        String stored = jdbcTemplate.queryForObject(
                "SELECT user_agent FROM refresh_tokens WHERE token_hash = ?", String.class,
                RefreshCookies.hash(RefreshCookies.of(login)));
        assertThat(stored).startsWith("Mozilla/5.0").hasSize(255);
    }

    // --- refreshing ---

    @Test
    void refreshWithNoBodyReturnsANewAccessTokenAndANewCookie() {
        String first = loginCookie(register());

        ResponseEntity<String> refreshed = refresh(first);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody()).contains("\"token\"").doesNotContain("refreshToken");
        assertThat(RefreshCookies.of(refreshed)).isNotEqualTo(first);
        assertThat(refresh(RefreshCookies.of(refreshed)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** One call restores a whole session on page load: the same user login returns. */
    @Test
    void refreshReturnsTheCurrentUserAlongsideTheToken() {
        String email = register();
        String token = loginCookie(email);

        ResponseEntity<RefreshResponse> refreshed = restTemplate.postForEntity(
                "/api/auth/refresh", RefreshCookies.presenting(token), RefreshResponse.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody().token()).isNotBlank();
        assertThat(refreshed.getBody().user().email()).isEqualToIgnoringCase(email);
        assertThat(refreshed.getBody().user().name()).isEqualTo("Ada L");
        assertThat(refreshed.getBody().user().role()).isEqualTo("client");
        assertThat(refreshed.getBody().user().permissions()).contains("client.dashboard.view");
    }

    /** Suspended after signing in: the existing session can no longer be renewed, and the token is untouched. */
    @Test
    void aSuspendedAccountCannotRenewAnExistingSession() {
        String email = register();
        String token = loginCookie(email);
        setStatus(email, "SUSPENDED");

        ResponseEntity<String> refused = refresh(token);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).contains("ACCOUNT_SUSPENDED");
        setStatus(email, "ACTIVE");
        assertThat(refresh(token).getStatusCode())
                .as("refused before any write: the same token still works once reinstated")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aMissingCookieAndABadCookieAreDistinct401s() {
        ResponseEntity<String> missing = restTemplate.postForEntity("/api/auth/refresh", null, String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missing.getBody()).contains("REFRESH_TOKEN_MISSING");

        ResponseEntity<String> bad = refresh("never-issued");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(bad.getBody()).contains("INVALID_REFRESH_TOKEN");
    }

    // --- the grace window ---

    /** A second tab presenting the token another tab just rotated. */
    @Test
    void aJustRotatedTokenPresentedAgainGetsAWorkingTokenAndRevokesNothing() {
        String original = loginCookie(register());
        String winner = RefreshCookies.of(refresh(original));

        ResponseEntity<String> secondTab = refresh(original);

        assertThat(secondTab.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh(RefreshCookies.of(secondTab)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh(winner).getStatusCode())
                .as("the winning tab's token survives: no family revoke")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void outsideTheGraceWindowReuseIsStillTheftAndRevokesEverySession() {
        String original = loginCookie(register());
        String winner = RefreshCookies.of(refresh(original));
        backdateRotation(original);

        assertThat(refresh(original).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(winner).getStatusCode())
                .as("theft detection took the live token with it")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** The headline bug: two tabs whose access tokens expire together, sending the same cookie at once. */
    @Test
    void twoTabsRefreshingTheSameCookieAtOnceBothStaySignedIn() throws Exception {
        String shared = loginCookie(register());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<String>>> results = List.of(
                    pool.submit(() -> { start.await(); return refresh(shared); }),
                    pool.submit(() -> { start.await(); return refresh(shared); }));
            start.countDown();

            for (Future<ResponseEntity<String>> result : results) {
                ResponseEntity<String> response = result.get();
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(refresh(RefreshCookies.of(response)).getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Logout revokes the successor, so the old token no longer qualifies for grace. */
    @Test
    void replayingARotatedTokenRightAfterLogoutGetsNoGrace() {
        String original = loginCookie(register());
        String current = RefreshCookies.of(refresh(original));

        assertThat(logout(current).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(refresh(original).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void replayingARotatedTokenRightAfterAPasswordChangeGetsNoGrace() {
        String email = register();
        ResponseEntity<AuthResponse> login = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class);
        String original = RefreshCookies.of(login);
        refresh(original);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.getBody().token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(restTemplate.exchange("/api/auth/change-password", HttpMethod.POST,
                new HttpEntity<>(new ChangePasswordRequest(PASSWORD, "a completely different passphrase"), headers),
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(refresh(original).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** The device that changed the password stays signed in; every other session ends. */
    @Test
    void changingThePasswordKeepsThisDeviceSignedInAndEndsTheOthers() {
        String email = register();
        String otherDevice = loginCookie(email);
        ResponseEntity<AuthResponse> login = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class);
        String thisDevice = RefreshCookies.of(login);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.getBody().token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> changed = restTemplate.exchange("/api/auth/change-password", HttpMethod.POST,
                new HttpEntity<>(new ChangePasswordRequest(PASSWORD, "a completely different passphrase"), headers),
                String.class);

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(RefreshCookies.header(changed)).contains("HttpOnly", "SameSite=Strict", "Path=/api/auth");
        String reissued = RefreshCookies.of(changed);
        assertThat(reissued).isNotEqualTo(thisDevice);
        // Realistic order: the other device (a phone still holding its old
        // cookie) refreshes first, and only then does this device.
        assertThat(refresh(otherDevice).getStatusCode()).as("other sessions end").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(reissued).getStatusCode()).as("this device stays signed in").isEqualTo(HttpStatus.OK);
    }

    // --- the Origin check ---

    @Test
    void aRefreshFromAForeignOriginIsRefusedAndRotatesNothing() {
        String token = loginCookie(register());

        ResponseEntity<String> foreign = restTemplate.exchange(
                "/api/auth/refresh", HttpMethod.POST, withOrigin(token, "https://blog.example.com"), String.class);
        ResponseEntity<String> nullOrigin = restTemplate.exchange(
                "/api/auth/refresh", HttpMethod.POST, withOrigin(token, "null"), String.class);

        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(nullOrigin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refresh(token).getStatusCode())
                .as("the refused requests left the token untouched")
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * The case only OriginGuard catches. Spring's CORS processor already
     * refuses a foreign origin (the tests above would pass without the
     * guard), but it treats the server's own origin as same-origin and lets
     * it through. Not on the allowed list, so the guard refuses it.
     */
    @Test
    void aSameOriginRequestThatIsNotOnTheAllowedListIsRefusedByTheOriginCheckItself() {
        String token = loginCookie(register());

        ResponseEntity<String> response = restTemplate.exchange("/api/auth/refresh", HttpMethod.POST,
                withOrigin(token, "http://localhost:" + port), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("ORIGIN_NOT_ALLOWED");
    }

    @Test
    void aRefreshFromTheAllowedOriginIsAccepted() {
        String token = loginCookie(register());

        assertThat(restTemplate.exchange("/api/auth/refresh", HttpMethod.POST,
                withOrigin(token, ALLOWED_ORIGIN), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aLogoutFromAForeignOriginIsRefused() {
        String token = loginCookie(register());

        assertThat(restTemplate.exchange("/api/auth/logout", HttpMethod.POST,
                withOrigin(token, "https://blog.example.com"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refresh(token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- logout ---

    @Test
    void logoutEndsOneSessionAndLeavesTheOtherDeviceSignedIn() {
        String email = register();
        String laptop = loginCookie(email);
        String phone = loginCookie(email);

        ResponseEntity<String> loggedOut = logout(laptop);

        assertThat(loggedOut.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // Cleared with the same attributes it was set with, or the browser keeps it.
        assertThat(RefreshCookies.header(loggedOut))
                .startsWith(RefreshCookies.NAME + "=;")
                .contains("Max-Age=0", "HttpOnly", "SameSite=Strict", "Path=/api/auth", "Secure");
        // Laptop first on purpose: a logged-out token presented again is
        // refused, but it was ended, not rotated past, so it is not a theft
        // signal and must not take the phone down with it.
        assertThat(refresh(laptop).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(phone).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void logoutWithNoSessionStillSucceedsAndClearsTheCookie() {
        ResponseEntity<String> noCookie = restTemplate.postForEntity("/api/auth/logout", null, String.class);
        ResponseEntity<String> unknown = logout("never-issued");

        assertThat(noCookie.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(RefreshCookies.header(noCookie)).contains("Max-Age=0");
    }

    // --- cleanup ---

    /**
     * Deletes long-expired rows, keeps recently expired ones, and never
     * deletes a row a surviving row still points at — including the case a
     * shortened TTL creates, where a successor expires before its predecessor.
     */
    @Test
    void cleanupDeletesLongExpiredRowsWithoutBreakingAReplacedByChain() {
        UUID userId = userIdOf(register());
        Instant now = Instant.now();

        UUID oldSuccessor = insertToken(userId, now.minus(Duration.ofDays(35)), null);
        UUID oldPredecessor = insertToken(userId, now.minus(Duration.ofDays(40)), oldSuccessor);
        UUID recentlyExpired = insertToken(userId, now.minus(Duration.ofDays(10)), null);
        UUID shortLivedSuccessor = insertToken(userId, now.minus(Duration.ofDays(40)), null);
        UUID liveKeeper = insertToken(userId, now.plus(Duration.ofDays(5)), shortLivedSuccessor);

        cleanupJob.deleteExpired();

        assertThat(exists(oldPredecessor)).isFalse();
        assertThat(exists(oldSuccessor)).isFalse();
        assertThat(exists(recentlyExpired)).as("kept for investigation").isTrue();
        assertThat(exists(shortLivedSuccessor)).as("a live row still points at it").isTrue();
        assertThat(exists(liveKeeper)).isTrue();
    }

    // --- helpers ---

    private RegisterRequest newBuyer(String email) {
        return new RegisterRequest("Ada", "L", email, "+2348000000000", PASSWORD, "NG", Currency.NGN);
    }

    private String register() {
        String email = "rt+" + UUID.randomUUID() + "@example.com";
        assertThat(restTemplate.postForEntity("/api/auth/register", newBuyer(email), String.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        return email;
    }

    private String loginCookie(String email) {
        return RefreshCookies.of(restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), String.class));
    }

    private ResponseEntity<String> refresh(String token) {
        return restTemplate.postForEntity("/api/auth/refresh", RefreshCookies.presenting(token), String.class);
    }

    private ResponseEntity<String> logout(String token) {
        return restTemplate.postForEntity("/api/auth/logout", RefreshCookies.presenting(token), String.class);
    }

    private static HttpEntity<Void> withOrigin(String token, String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(RefreshCookies.presenting(token).getHeaders());
        headers.set(HttpHeaders.ORIGIN, origin);
        return new HttpEntity<>(headers);
    }

    private void backdateRotation(String rawToken) {
        jdbcTemplate.update("UPDATE refresh_tokens SET revoked_at = revoked_at - interval '1 hour' WHERE token_hash = ?",
                RefreshCookies.hash(rawToken));
    }

    private void setStatus(String email, String status) {
        jdbcTemplate.update("UPDATE users SET status = ? WHERE lower(email) = lower(?)", status, email);
    }

    private UUID userIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE lower(email) = lower(?)", UUID.class, email);
    }

    private UUID insertToken(UUID userId, Instant expiresAt, UUID replacedBy) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO refresh_tokens (id, created_at, deleted, user_id, token_hash, expires_at, "
                        + "revoked_at, replaced_by) VALUES (?, now(), false, ?, ?, ?, ?, ?)",
                id, userId, UUID.randomUUID().toString(), Timestamp.from(expiresAt),
                replacedBy == null ? null : Timestamp.from(expiresAt), replacedBy);
        return id;
    }

    private boolean exists(UUID id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM refresh_tokens WHERE id = ?)", Boolean.class, id));
    }
}
