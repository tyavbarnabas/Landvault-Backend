package com.techcomfort.landvaultbackend.identity;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.MeResponse;
import com.techcomfort.landvaultbackend.identity.dto.RecoveryCodesResponse;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import com.techcomfort.landvaultbackend.identity.dto.TwoFaCodeRequest;
import com.techcomfort.landvaultbackend.identity.dto.TwoFaSetupResponse;
import com.techcomfort.landvaultbackend.identity.dto.TwoFaVerifyRequest;
import com.techcomfort.landvaultbackend.identity.dto.TwoFactorChallengeResponse;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.time.SystemTimeProvider;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two-factor authentication end to end. Codes are generated with the same
 * library the server verifies with, which is what an authenticator app would
 * be doing.
 * <p>
 * None of the tables involved (users, recovery_codes, two_fa_challenges) are
 * RLS-policied, so this can use {@code @ServiceConnection} like
 * {@code AuthenticationIT} rather than the split-role wiring the tenancy ITs
 * need.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class TwoFactorIT {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String ADMIN_EMAIL = "admin+" + UUID.randomUUID() + "@example.com";
    private static final int PERIOD_SECONDS = 30;

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

    private final DefaultCodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
    private final SystemTimeProvider timeProvider = new SystemTimeProvider();

    // --- setup / confirm split (TF-1, TF-2) ---

    @Test
    void setupAloneDoesNotEnableTwoFactorAndLoginStillReturnsTokens() {
        String email = register();
        String token = loginExpectingTokens(email).token();

        TwoFaSetupResponse setup = setup(token);
        assertThat(setup.secret()).isNotBlank();
        assertThat(setup.otpAuthUri()).startsWith("otpauth://totp/");

        assertThat(twoFaEnabled(email))
                .as("a secret exists but the pairing is unproven — enabling here would lock the user out")
                .isFalse();
        assertThat(loginExpectingTokens(email).token()).isNotBlank();
    }

    @Test
    void confirmWithAnInvalidCodeLeavesTwoFactorDisabled() {
        String email = register();
        String token = loginExpectingTokens(email).token();
        setup(token);

        ResponseEntity<String> response = postRaw(token, "/api/auth/2fa/confirm", new TwoFaCodeRequest("000000"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("INVALID_TWO_FACTOR_CODE");
        assertThat(twoFaEnabled(email)).isFalse();
    }

    @Test
    void confirmWithAValidCodeEnablesTwoFactorAndReturnsRecoveryCodes() throws Exception {
        String email = register();
        String token = loginExpectingTokens(email).token();
        TwoFaSetupResponse setup = setup(token);

        RecoveryCodesResponse codes = confirm(token, currentCode(setup.secret()));

        assertThat(codes.recoveryCodes()).hasSize(10).doesNotHaveDuplicates();
        assertThat(twoFaEnabled(email)).isTrue();
    }

    // --- two-step login (TF-3) ---

    @Test
    void loginWithTwoFactorEnabledReturnsAChallengeAndNoTokens() throws Exception {
        String email = register();
        enableTwoFactor(email);

        ResponseEntity<String> raw = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody())
                .as("no credential of any kind may be issued before the second factor")
                .contains("twoFactorRequired")
                .doesNotContain("\"token\"")
                .doesNotContain("refreshToken");
    }

    @Test
    void theChallengeTokenAloneCannotCallAProtectedEndpoint() throws Exception {
        String email = register();
        enableTwoFactor(email);

        TwoFactorChallengeResponse challenge = loginExpectingChallenge(email);
        ResponseEntity<String> me = restTemplate.exchange(
                "/api/me", HttpMethod.GET, new HttpEntity<>(bearer(challenge.challengeToken())), String.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void verifyWithAValidCodeReturnsWorkingTokens() throws Exception {
        String email = register();
        String secret = enableTwoFactor(email);

        TwoFactorChallengeResponse challenge = loginExpectingChallenge(email);
        ResponseEntity<AuthResponse> verified = restTemplate.postForEntity(
                "/api/auth/2fa/verify",
                new TwoFaVerifyRequest(challenge.challengeToken(), currentCode(secret)), AuthResponse.class);

        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody()).isNotNull();
        assertThat(verified.getBody().user().twoFAEnabled()).isTrue();
        assertThat(verified.getBody().user().recoveryCodesRemaining()).isEqualTo(10);

        ResponseEntity<MeResponse> me = restTemplate.exchange(
                "/api/me", HttpMethod.GET, new HttpEntity<>(bearer(verified.getBody().token())), MeResponse.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aChallengeCannotBeExchangedTwice() throws Exception {
        String email = register();
        String secret = enableTwoFactor(email);

        TwoFactorChallengeResponse challenge = loginExpectingChallenge(email);
        assertThat(verifyRaw(challenge.challengeToken(), currentCode(secret)).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> replay = verifyRaw(challenge.challengeToken(), currentCode(secret));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replay.getBody()).contains("INVALID_TWO_FACTOR_CHALLENGE");
    }

    // --- recovery codes (TF-5) ---

    @Test
    void aRecoveryCodeWorksOnceAndThenFails() throws Exception {
        String email = register();
        String secret = enableTwoFactor(email);
        String recoveryCode = recoveryCodesFor(email, secret).getFirst();

        TwoFactorChallengeResponse first = loginExpectingChallenge(email);
        assertThat(verifyRaw(first.challengeToken(), recoveryCode).getStatusCode()).isEqualTo(HttpStatus.OK);

        TwoFactorChallengeResponse second = loginExpectingChallenge(email);
        ResponseEntity<String> reuse = verifyRaw(second.challengeToken(), recoveryCode);
        assertThat(reuse.getStatusCode())
                .as("a recovery code is single use")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void usingARecoveryCodeWritesAnAuditEntry() throws Exception {
        String email = register();
        String secret = enableTwoFactor(email);
        String recoveryCode = recoveryCodesFor(email, secret).getFirst();

        TwoFactorChallengeResponse challenge = loginExpectingChallenge(email);
        verifyRaw(challenge.challengeToken(), recoveryCode);

        assertThat(countRows("audit_log_entries",
                "action = 'auth.two_factor_recovery_code_used' AND target_id = '" + userIdOf(email) + "'"))
                .isEqualTo(1);
    }

    @Test
    void regeneratingInvalidatesTheOldCodes() throws Exception {
        String email = register();
        String secret = enableTwoFactor(email);
        String oldCode = recoveryCodesFor(email, secret).getFirst();

        String token = verifiedSessionToken(email, secret);
        ResponseEntity<RecoveryCodesResponse> regenerated = restTemplate.exchange(
                "/api/auth/2fa/recovery-codes/regenerate", HttpMethod.POST,
                entity(token, new TwoFaCodeRequest(currentCode(secret))), RecoveryCodesResponse.class);

        assertThat(regenerated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(regenerated.getBody().recoveryCodes()).hasSize(10).doesNotContain(oldCode);

        TwoFactorChallengeResponse challenge = loginExpectingChallenge(email);
        assertThat(verifyRaw(challenge.challengeToken(), oldCode).getStatusCode())
                .as("codes from the superseded set must stop working")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- disable (TF-6) ---

    @Test
    void disableRequiresACodeAndASessionAloneIsRejected() throws Exception {
        String email = register();
        String secret = enableTwoFactor(email);
        String token = verifiedSessionToken(email, secret);

        ResponseEntity<String> withoutValidCode = postRaw(token, "/api/auth/2fa/disable", new TwoFaCodeRequest("000000"));
        assertThat(withoutValidCode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(twoFaEnabled(email))
                .as("a hijacked session must not be able to strip 2FA")
                .isTrue();

        ResponseEntity<String> withCode = postRaw(token, "/api/auth/2fa/disable", new TwoFaCodeRequest(currentCode(secret)));
        assertThat(withCode.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(twoFaEnabled(email)).isFalse();
        assertThat(singleLong("SELECT count(*) FROM recovery_codes WHERE user_id = '" + userIdOf(email) + "'"))
                .isZero();
        assertThat(loginExpectingTokens(email).token()).isNotBlank();
    }

    // --- mandatory for platform staff (TF-8) ---

    @Test
    void platformStaffCannotDisableTwoFactor() throws Exception {
        String secret = enableTwoFactorForBootstrappedAdmin();
        String token = verifiedSessionToken(ADMIN_EMAIL, secret);

        ResponseEntity<String> response = postRaw(token, "/api/auth/2fa/disable", new TwoFaCodeRequest(currentCode(secret)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("TWO_FACTOR_MANDATORY");
        assertThat(twoFaEnabled(ADMIN_EMAIL)).isTrue();
    }

    @Test
    void platformStaffWithoutTwoFactorAreFlaggedButNotBlockedFromLoggingIn() {
        // The bootstrapped admin has not set 2FA up in this test's own flow;
        // blocking login would strand them, since setup needs a session.
        AuthResponse login = loginExpectingTokens(ADMIN_EMAIL);

        assertThat(login.token()).isNotBlank();
        if (!login.user().twoFAEnabled()) {
            assertThat(login.user().mustSetUpTwoFa()).isTrue();
        }
    }

    // --- throttling (TF-9) ---

    @Test
    void fiveFailedVerificationsLockOutEvenACorrectCode() throws Exception {
        String email = register();
        String secret = enableTwoFactor(email);

        for (int attempt = 1; attempt <= 5; attempt++) {
            TwoFactorChallengeResponse challenge = loginExpectingChallenge(email);
            assertThat(verifyRaw(challenge.challengeToken(), "000000").getStatusCode())
                    .as("wrong attempt %d", attempt)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        assertThat(singleLong("SELECT count(*) FROM users WHERE id = '" + userIdOf(email)
                + "' AND two_fa_locked_until > now()"))
                .as("the lockout must actually persist — the noRollbackFor trap")
                .isEqualTo(1);

        TwoFactorChallengeResponse challenge = loginExpectingChallenge(email);
        ResponseEntity<String> withCorrectCode = verifyRaw(challenge.challengeToken(), currentCode(secret));
        assertThat(withCorrectCode.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(withCorrectCode.getBody()).contains("TWO_FACTOR_LOCKED_OUT");
    }

    // --- encryption at rest (TF-7) ---

    @Test
    void theStoredSecretIsNotReadablePlaintext() throws Exception {
        String email = register();
        String secret = enableTwoFactor(email);

        String stored = singleString("SELECT two_fa_secret FROM users WHERE id = '" + userIdOf(email) + "'");

        assertThat(stored).isNotNull().isNotEqualTo(secret).doesNotContain(secret);
    }

    // --- no regression for everyone else ---

    @Test
    void aUserWithoutTwoFactorSeesNoChange() {
        String email = register();

        AuthResponse login = loginExpectingTokens(email);

        assertThat(login.token()).isNotBlank();
        assertThat(login.refreshToken()).isNotBlank();
        assertThat(login.user().twoFAEnabled()).isFalse();
        assertThat(login.user().recoveryCodesRemaining()).isZero();
        assertThat(restTemplate.exchange("/api/me", HttpMethod.GET,
                new HttpEntity<>(bearer(login.token())), MeResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void twoFactorManagementEndpointsRequireAuthentication() {
        assertThat(restTemplate.postForEntity("/api/auth/2fa/setup", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.postForEntity("/api/auth/2fa/confirm", new TwoFaCodeRequest("000000"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.postForEntity("/api/auth/2fa/disable", new TwoFaCodeRequest("000000"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.postForEntity("/api/auth/2fa/recovery-codes/regenerate",
                new TwoFaCodeRequest("000000"), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- helpers ---

    private String register() {
        String email = "twofa+" + UUID.randomUUID() + "@example.com";
        RegisterRequest request = new RegisterRequest(
                "Two", "Factor", email, "+2348000000000", PASSWORD, "NG", Currency.NGN);
        assertThat(restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        return email;
    }

    /** Registers 2FA on a fresh account and returns the secret. */
    private String enableTwoFactor(String email) throws Exception {
        String token = loginExpectingTokens(email).token();
        TwoFaSetupResponse setup = setup(token);
        confirm(token, currentCode(setup.secret()));
        return setup.secret();
    }

    private String enableTwoFactorForBootstrappedAdmin() throws Exception {
        if (twoFaEnabled(ADMIN_EMAIL)) {
            throw new IllegalStateException("admin already has 2FA; tests must not share this account's state");
        }
        String token = loginExpectingTokens(ADMIN_EMAIL).token();
        TwoFaSetupResponse setup = setup(token);
        confirm(token, currentCode(setup.secret()));
        return setup.secret();
    }

    private String verifiedSessionToken(String email, String secret) throws Exception {
        TwoFactorChallengeResponse challenge = loginExpectingChallenge(email);
        ResponseEntity<AuthResponse> verified = restTemplate.postForEntity(
                "/api/auth/2fa/verify",
                new TwoFaVerifyRequest(challenge.challengeToken(), currentCode(secret)), AuthResponse.class);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        return verified.getBody().token();
    }

    private List<String> recoveryCodesFor(String email, String secret) throws Exception {
        String token = verifiedSessionToken(email, secret);
        ResponseEntity<RecoveryCodesResponse> response = restTemplate.exchange(
                "/api/auth/2fa/recovery-codes/regenerate", HttpMethod.POST,
                entity(token, new TwoFaCodeRequest(currentCode(secret))), RecoveryCodesResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().recoveryCodes();
    }

    private TwoFaSetupResponse setup(String token) {
        ResponseEntity<TwoFaSetupResponse> response = restTemplate.exchange(
                "/api/auth/2fa/setup", HttpMethod.POST, new HttpEntity<>(bearer(token)), TwoFaSetupResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private RecoveryCodesResponse confirm(String token, String code) {
        ResponseEntity<RecoveryCodesResponse> response = restTemplate.exchange(
                "/api/auth/2fa/confirm", HttpMethod.POST,
                entity(token, new TwoFaCodeRequest(code)), RecoveryCodesResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private AuthResponse loginExpectingTokens(String email) {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().token()).isNotBlank();
        return response.getBody();
    }

    private TwoFactorChallengeResponse loginExpectingChallenge(String email) {
        ResponseEntity<TwoFactorChallengeResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, PASSWORD), TwoFactorChallengeResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().twoFactorRequired()).isTrue();
        assertThat(response.getBody().challengeToken()).isNotBlank();
        return response.getBody();
    }

    private ResponseEntity<String> verifyRaw(String challengeToken, String code) {
        return restTemplate.postForEntity(
                "/api/auth/2fa/verify", new TwoFaVerifyRequest(challengeToken, code), String.class);
    }

    private ResponseEntity<String> postRaw(String token, String path, Object body) {
        return restTemplate.exchange(path, HttpMethod.POST, entity(token, body), String.class);
    }

    private HttpEntity<Object> entity(String token, Object body) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /** What an authenticator app would show right now. */
    private String currentCode(String secret) throws Exception {
        return codeGenerator.generate(secret, Math.floorDiv(timeProvider.getTime(), PERIOD_SECONDS));
    }

    // --- raw JDBC, for state the API deliberately never exposes ---

    private static boolean twoFaEnabled(String email) {
        return singleLong("SELECT count(*) FROM users WHERE lower(email) = lower('" + email
                + "') AND two_fa_enabled = true AND two_fa_confirmed_at IS NOT NULL") == 1;
    }

    private static UUID userIdOf(String email) {
        return UUID.fromString(singleString("SELECT id FROM users WHERE lower(email) = lower('" + email + "')"));
    }

    private static long countRows(String table, String whereClause) {
        return singleLong("SELECT count(*) FROM " + table + " WHERE " + whereClause);
    }

    private static Connection connection() {
        try {
            return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
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

    private static String singleString(String sql) {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
