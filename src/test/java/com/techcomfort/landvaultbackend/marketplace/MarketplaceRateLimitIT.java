package com.techcomfort.landvaultbackend.marketplace;

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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limiter, wired for real. {@code @ServiceConnection} is fine here: this
 * class tests throttling, not row-level security, and an empty feed is still
 * a 200 to count.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class MarketplaceRateLimitIT {

    private static final int LIMIT = 5;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "integration-test-signing-secret-of-at-least-32-bytes");
        registry.add("landvault.marketplace.rate-limit.requests-per-window", () -> String.valueOf(LIMIT));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aBurstBeyondTheThresholdIsRejectedAndAForgedForwardedForDoesNotHelp() {
        for (int i = 0; i < LIMIT; i++) {
            assertThat(get(new HttpHeaders()).getStatusCode()).as("request " + (i + 1)).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<String> throttled = get(new HttpHeaders());
        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(throttled.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(throttled.getBody()).contains("RATE_LIMITED");

        // Anyone can send this header. If the limiter keyed on it, every
        // request could claim a fresh address and never be throttled.
        HttpHeaders forged = new HttpHeaders();
        forged.set("X-Forwarded-For", "203.0.113.77");
        assertThat(get(forged).getStatusCode())
                .as("a client-supplied X-Forwarded-For must not reset the limit")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private ResponseEntity<String> get(HttpHeaders headers) {
        return restTemplate.exchange("/api/marketplace/estates", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
