package com.techcomfort.landvaultbackend.identity.internal.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static JwtService newService(String secret) {
        JwtService service = new JwtService(new JwtProperties(secret, Duration.ofMinutes(15), Duration.ofDays(30)));
        service.init();
        return service;
    }

    @Test
    void issuesAndParsesRoundTrip() {
        JwtService service = newService("a-sufficiently-long-test-secret-of-at-least-32-bytes");
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        List<RoleClaim> roles = List.of(new RoleClaim("BRANCH_MANAGER", branchId), new RoleClaim("SALES_MANAGER", null));
        List<String> permissions = List.of("client.dashboard.view", "client.portfolio.view");

        AccessTokenIssue issued = service.issueAccessToken(userId, "user@example.com", tenantId, false, roles, permissions);
        assertThat(issued.token()).isNotBlank();

        AccessTokenClaims claims = service.parse(issued.token());
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.email()).isEqualTo("user@example.com");
        assertThat(claims.tenantId()).isEqualTo(tenantId);
        assertThat(claims.platformStaff()).isFalse();
        assertThat(claims.permissions()).containsExactlyInAnyOrderElementsOf(permissions);
    }

    @Test
    void tenantIdIsNullableInClaims() {
        JwtService service = newService("another-sufficiently-long-test-secret-of-32-plus-bytes");
        AccessTokenIssue issued = service.issueAccessToken(UUID.randomUUID(), "buyer@example.com", null, false, List.of(), List.of());
        assertThat(service.parse(issued.token()).tenantId()).isNull();
    }

    @Test
    void platformStaffTravelsInTheToken() {
        JwtService service = newService("yet-another-sufficiently-long-test-secret-32-plus-bytes");
        AccessTokenIssue issued = service.issueAccessToken(UUID.randomUUID(), "admin@example.com", null, true, List.of(), List.of());
        assertThat(service.parse(issued.token()).platformStaff()).isTrue();
    }

    @Test
    void rejectsMissingSecretAtStartup() {
        JwtService service = new JwtService(new JwtProperties(null, Duration.ofMinutes(15), Duration.ofDays(30)));
        assertThatThrownBy(service::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsWeakSecretAtStartup() {
        JwtService service = new JwtService(new JwtProperties("too-short", Duration.ofMinutes(15), Duration.ofDays(30)));
        assertThatThrownBy(service::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsATamperedToken() {
        JwtService service = newService("a-third-sufficiently-long-test-secret-32-plus-bytes");
        AccessTokenIssue issued = service.issueAccessToken(UUID.randomUUID(), "user@example.com", null, false, List.of(), List.of());
        String token = issued.token();

        // Flip a character well inside the token rather than its tail: the
        // last base64url group of a byte sequence isn't necessarily a
        // multiple of 3, so its final character carries some decoder-
        // ignored "don't care" bits — touching only those can, depending
        // on the specific bytes, decode to the exact same signature and
        // make this test flake. A middle character carries no such
        // ambiguity, so the byte value is guaranteed to change.
        int index = token.length() / 2;
        char replacement = token.charAt(index) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, index) + replacement + token.substring(index + 1);

        assertThatThrownBy(() -> service.parse(tampered)).isInstanceOf(JwtException.class);
    }
}
