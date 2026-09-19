package com.techcomfort.landvaultbackend.identity;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.ForgotPasswordRequest;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.RefreshRequest;
import com.techcomfort.landvaultbackend.identity.dto.RefreshResponse;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.identity.dto.ResetPasswordRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Password reset end to end. The code is read back out of
 * {@code LoggingOtpDeliveryService}'s own log line rather than from a
 * test-only delivery double — that implementation is the real default, and
 * reading it the way a developer would is the point of it existing (no
 * vendor account, no cost).
 * <p>
 * {@code otp_codes} is not RLS-policied (see AGENTS.md), so this can use
 * {@code @ServiceConnection} like {@code AuthenticationIT} rather than the
 * split superuser/app-role wiring the tenancy ITs need.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class PasswordResetIT {

    private static final String OLD_PASSWORD = "correct horse battery staple";
    private static final String NEW_PASSWORD = "an entirely different passphrase";
    private static final String LOGGING_DELIVERY_LOGGER =
            "com.techcomfort.landvaultbackend.identity.internal.service.LoggingOtpDeliveryService";
    private static final Pattern CODE_IN_LOG = Pattern.compile("code=(\\d{6})");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "integration-test-signing-secret-of-at-least-32-bytes");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private ListAppender<ILoggingEvent> deliveryLog;

    @BeforeEach
    void captureDeliveryLog() {
        deliveryLog = new ListAppender<>();
        deliveryLog.start();
        ((Logger) LoggerFactory.getLogger(LOGGING_DELIVERY_LOGGER)).addAppender(deliveryLog);
    }

    @AfterEach
    void releaseDeliveryLog() {
        ((Logger) LoggerFactory.getLogger(LOGGING_DELIVERY_LOGGER)).detachAppender(deliveryLog);
        deliveryLog.stop();
    }

    // --- happy path ---

    @Test
    void requestResetThenLogInWithTheNewPasswordWhileTheOldOneStopsWorking() {
        String email = register();

        ResponseEntity<String> requested = requestCode(email);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.OK);

        String code = onlyDeliveredCode();
        ResponseEntity<String> reset = resetPassword(email, code, NEW_PASSWORD);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(email, OLD_PASSWORD).getStatusCode())
                .as("the old password must stop working")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theCodeIsNeverReturnedInAnyResponseBody() {
        String email = register();
        ResponseEntity<String> requested = requestCode(email);

        String code = onlyDeliveredCode();
        assertThat(requested.getBody())
                .as("the code travels by delivery channel only, never back to the caller")
                .doesNotContain(code);
    }

    // --- enumeration protection ---

    @Test
    void unknownEmailReturnsAByteIdenticalResponseToAKnownOne() {
        String known = register();
        String unknown = "nobody-" + UUID.randomUUID() + "@example.com";

        ResponseEntity<String> knownResponse = requestCode(known);
        ResponseEntity<String> unknownResponse = requestCode(unknown);

        assertThat(unknownResponse.getStatusCode()).isEqualTo(knownResponse.getStatusCode());
        assertThat(unknownResponse.getBody()).isEqualTo(knownResponse.getBody());
        assertThat(deliveredCodes())
                .as("exactly one code — the unknown address must generate nothing")
                .hasSize(1);
        assertThat(countOtpCodes(unknown)).isZero();
    }

    @Test
    void resettingWithAnUnknownEmailFailsTheSameWayAsABadCode() {
        String known = register();
        requestCode(known);

        ResponseEntity<String> badCode = resetPassword(known, "000000", NEW_PASSWORD);
        ResponseEntity<String> unknownEmail = resetPassword(
                "nobody-" + UUID.randomUUID() + "@example.com", "000000", NEW_PASSWORD);

        assertThat(unknownEmail.getStatusCode()).isEqualTo(badCode.getStatusCode());
        assertThat(unknownEmail.getBody()).isEqualTo(badCode.getBody());
    }

    // --- code lifecycle ---

    @Test
    void anExpiredCodeIsRejected() {
        String email = register();
        requestCode(email);
        String code = onlyDeliveredCode();

        expireCodesFor(email);

        ResponseEntity<String> reset = resetPassword(email, code, NEW_PASSWORD);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reset.getBody()).contains("INVALID_OR_EXPIRED_CODE");
        assertThat(login(email, OLD_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anAlreadyUsedCodeCannotBeUsedAgainInsideItsExpiryWindow() {
        String email = register();
        requestCode(email);
        String code = onlyDeliveredCode();

        assertThat(resetPassword(email, code, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Same code, still well inside its 10-minute window.
        ResponseEntity<String> replay = resetPassword(email, code, "yet another password");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(login(email, NEW_PASSWORD).getStatusCode())
                .as("the replay must not have changed the password again")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void requestingASecondCodeInvalidatesTheFirst() {
        String email = register();
        requestCode(email);
        requestCode(email);

        List<String> codes = deliveredCodes();
        assertThat(codes).hasSize(2);
        String first = codes.get(0);
        String second = codes.get(1);

        assertThat(resetPassword(email, first, NEW_PASSWORD).getStatusCode())
                .as("the superseded code must no longer work")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resetPassword(email, second, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * PR-4. Also the regression guard for the {@code noRollbackFor} on
     * {@code resetPassword}: without it every increment would roll back with
     * the thrown exception, the counter would sit at zero forever, and the
     * sixth attempt below would succeed.
     */
    @Test
    void fiveWrongAttemptsInvalidateTheCodeEvenForTheCorrectOne() {
        String email = register();
        requestCode(email);
        String code = onlyDeliveredCode();

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(resetPassword(email, wrongCodeOtherThan(code), NEW_PASSWORD).getStatusCode())
                    .as("wrong attempt %d", attempt)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        assertThat(attemptCountFor(email)).isEqualTo(5);
        assertThat(resetPassword(email, code, NEW_PASSWORD).getStatusCode())
                .as("the correct code must fail once the attempt limit is exhausted")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(login(email, OLD_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- rate limiting ---

    @Test
    void theFourthRequestInsideTheWindowGeneratesAndSendsNothing() {
        String email = register();

        ResponseEntity<String> first = requestCode(email);
        requestCode(email);
        requestCode(email);
        ResponseEntity<String> fourth = requestCode(email);

        assertThat(fourth.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(fourth.getBody())
                .as("a rate-limited caller must not be able to tell")
                .isEqualTo(first.getBody());
        assertThat(deliveredCodes()).hasSize(3);
        assertThat(countOtpCodes(email)).isEqualTo(3);
    }

    // --- session invalidation and audit ---

    @Test
    void resetRevokesExistingRefreshTokens() {
        String email = register();
        AuthResponse session = login(email, OLD_PASSWORD).getBody();
        assertThat(session).isNotNull();

        requestCode(email);
        assertThat(resetPassword(email, onlyDeliveredCode(), NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<RefreshResponse> refreshed = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshRequest(session.refreshToken()), RefreshResponse.class);
        assertThat(refreshed.getStatusCode())
                .as("a refresh token issued before the reset must no longer work")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anAuditEntryIsWrittenOnSuccessfulReset() {
        String email = register();
        requestCode(email);
        assertThat(resetPassword(email, onlyDeliveredCode(), NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID userId = userIdOf(email);
        assertThat(countRows("audit_log_entries",
                "action = 'auth.password_reset_completed' AND target_id = '" + userId + "'"))
                .isEqualTo(1);
    }

    @Test
    void noCodeIsEverStoredInPlaintext() {
        String email = register();
        requestCode(email);
        String code = onlyDeliveredCode();

        assertThat(countRows("otp_codes", "code_hash = '" + code + "'"))
                .as("the column holds a hash, never the code")
                .isZero();
        assertThat(countRows("otp_codes", "user_id = '" + userIdOf(email) + "' AND code_hash IS NOT NULL"))
                .isEqualTo(1);
    }

    // --- helpers ---

    private String register() {
        String email = "reset+" + UUID.randomUUID() + "@example.com";
        RegisterRequest request = new RegisterRequest(
                "Ada", "Lovelace", email, "+2348000000000", OLD_PASSWORD, "NG", Currency.NGN);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return email;
    }

    private ResponseEntity<String> requestCode(String email) {
        return restTemplate.postForEntity("/api/auth/forgot-password", new ForgotPasswordRequest(email), String.class);
    }

    private ResponseEntity<String> resetPassword(String email, String code, String newPassword) {
        return restTemplate.postForEntity(
                "/api/auth/reset-password", new ResetPasswordRequest(email, code, newPassword), String.class);
    }

    private ResponseEntity<AuthResponse> login(String email, String password) {
        return restTemplate.postForEntity("/api/auth/login", new LoginRequest(email, password), AuthResponse.class);
    }

    private List<String> deliveredCodes() {
        return deliveryLog.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .map(CODE_IN_LOG::matcher)
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1))
                .toList();
    }

    private String onlyDeliveredCode() {
        List<String> codes = deliveredCodes();
        assertThat(codes).hasSize(1);
        return codes.getFirst();
    }

    private static String wrongCodeOtherThan(String code) {
        return code.equals("000000") ? "999999" : "000000";
    }

    // --- raw JDBC, for state the API deliberately never exposes ---

    private static Connection connection() {
        try {
            return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void expireCodesFor(String email) {
        execute("UPDATE otp_codes SET expires_at = now() - interval '1 minute' "
                + "WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('" + email + "'))");
    }

    private static int attemptCountFor(String email) {
        return (int) singleLong("SELECT attempt_count FROM otp_codes "
                + "WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower('" + email + "')) "
                + "ORDER BY created_at DESC LIMIT 1");
    }

    private static long countOtpCodes(String email) {
        return singleLong("SELECT count(*) FROM otp_codes "
                + "WHERE user_id IN (SELECT id FROM users WHERE lower(email) = lower('" + email + "'))");
    }

    private static UUID userIdOf(String email) {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT id FROM users WHERE lower(email) = lower('" + email + "')")) {
            resultSet.next();
            return UUID.fromString(resultSet.getString(1));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long countRows(String table, String whereClause) {
        return singleLong("SELECT count(*) FROM " + table + " WHERE " + whereClause);
    }

    private static long singleLong(String sql) {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void execute(String sql) {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
