package com.techcomfort.landvaultbackend.identity;

import com.techcomfort.landvaultbackend.common.Currency;
import com.techcomfort.landvaultbackend.identity.dto.AuthResponse;
import com.techcomfort.landvaultbackend.identity.dto.LoginRequest;
import com.techcomfort.landvaultbackend.identity.dto.MeResponse;
import com.techcomfort.landvaultbackend.identity.dto.RefreshRequest;
import com.techcomfort.landvaultbackend.identity.dto.RefreshResponse;
import com.techcomfort.landvaultbackend.identity.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proof the slice works: register -> login -> call a protected endpoint
 * with the token -> refresh -> call again with the new token. Also the
 * 401/403 checks from the slice's own verification steps.
 * <p>
 * A plain {@code postgres} image is not enough — the schema (from earlier
 * slices) needs PostGIS, matching Compose.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class AuthenticationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void jwtSecret(DynamicPropertyRegistry registry) {
        // Independent of any real .env — an IT must not depend on the
        // developer's local secret to pass.
        registry.add("app.jwt.secret", () -> "integration-test-signing-secret-of-at-least-32-bytes");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private TestRestTemplate restTemplate;

    @Test
    void registerLoginProtectedEndpointRefreshAndCallAgain() {
        String email = "ada+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest(
                "Ada", "Lovelace", email, "+2348000000000", "correct horse battery staple", "NG", Currency.NGN);

        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AuthResponse registered = registerResponse.getBody();
        assertThat(registered).isNotNull();
        assertThat(registered.user().role()).isEqualTo("client");
        assertThat(registered.user().permissions()).contains("client.dashboard.view", "client.portfolio.view");
        assertThat(registered.user().kycStatus()).isEqualTo("unsubmitted");

        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, "correct horse battery staple"), AuthResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthResponse loggedIn = loginResponse.getBody();
        assertThat(loggedIn).isNotNull();
        String accessToken = loggedIn.token();
        String refreshToken = loggedIn.refreshToken();

        // The roles claim must carry Role.code verbatim (lowercase, no
        // transformation) — this is AuthService.loadContext's behavior,
        // not just JwtService's own round-trip fidelity, so it's worth
        // checking here against a token that actually went through
        // register()/login(), not a hand-built one.
        assertThat(decodeJwtPayload(accessToken))
                .contains("\"role\":\"buyer\"")
                .doesNotContain("\"BUYER\"");

        ResponseEntity<MeResponse> meResponse = callMe(accessToken);
        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meResponse.getBody()).isNotNull();
        assertThat(meResponse.getBody().email()).isEqualToIgnoringCase(email);
        assertThat(meResponse.getBody().permissions()).contains("client.dashboard.view");

        ResponseEntity<RefreshResponse> refreshResponse = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshRequest(refreshToken), RefreshResponse.class);
        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        RefreshResponse rotated = refreshResponse.getBody();
        assertThat(rotated).isNotNull();
        assertThat(rotated.token()).isNotEqualTo(accessToken);
        assertThat(rotated.refreshToken()).isNotEqualTo(refreshToken);

        ResponseEntity<MeResponse> meAgain = callMe(rotated.token());
        assertThat(meAgain.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The old refresh token was rotated out — reuse must now fail
        // (and, per the theft-detection rule, take the new one with it).
        ResponseEntity<RefreshResponse> reuseOldToken = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshRequest(refreshToken), RefreshResponse.class);
        assertThat(reuseOldToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<RefreshResponse> reuseRotatedToken = restTemplate.postForEntity(
                "/api/auth/refresh", new RefreshRequest(rotated.refreshToken()), RefreshResponse.class);
        assertThat(reuseRotatedToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpointWithNoTokenIsUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/me", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // No profile is active in this class (see the dev-profile-active
    // counterpart, SwaggerDevProfileIT) — proves the docs paths are
    // genuinely blocked by default, not just conditionally documented as
    // such. auth/health stay reachable regardless.
    @Test
    void apiDocsAreNotPubliclyReadableOutsideDevProfile() {
        assertThat(restTemplate.getForEntity("/v3/api-docs", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity("/swagger-ui.html", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpointWithInsufficientPermissionIsForbidden() {
        String email = "buyer+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest(
                "Regular", "Buyer", email, "+2348000000000", "another correct horse battery staple", "NG", Currency.NGN);
        AuthResponse registered = restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class).getBody();
        assertThat(registered).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(registered.token());
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/me/admin-check", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void duplicateEmailRegistrationConflicts() {
        String email = "dup+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest(
                "First", "User", email, "+2348000000000", "correct horse battery staple", "NG", Currency.NGN);
        restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class);

        ResponseEntity<AuthResponse> second = restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void loginWithWrongPasswordAndUnknownEmailReturnTheSameStatusAndGenericMessage() {
        String email = "known+" + UUID.randomUUID() + "@example.com";
        RegisterRequest register = new RegisterRequest(
                "Known", "User", email, "+2348000000000", "correct horse battery staple", "NG", Currency.NGN);
        restTemplate.postForEntity("/api/auth/register", register, AuthResponse.class);

        ResponseEntity<String> wrongPassword = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, "not the right password"), String.class);
        ResponseEntity<String> unknownEmail = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest("nobody-" + UUID.randomUUID() + "@example.com", "irrelevant"), String.class);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody()).contains("INVALID_CREDENTIALS");
        assertThat(unknownEmail.getBody()).contains("INVALID_CREDENTIALS");
    }

    private ResponseEntity<MeResponse> callMe(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return restTemplate.exchange("/api/me", HttpMethod.GET, new HttpEntity<>(headers), MeResponse.class);
    }

    // Raw payload decode, deliberately not via JwtService — this is
    // checking the actual wire content the frontend would receive, not
    // re-verifying JwtService's own (de)serialization.
    private static String decodeJwtPayload(String jwt) {
        String payload = jwt.split("\\.")[1];
        return new String(java.util.Base64.getUrlDecoder().decode(payload), java.nio.charset.StandardCharsets.UTF_8);
    }
}
