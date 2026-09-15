package com.techcomfort.landvaultbackend.identity.internal.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Issues and validates the platform's access tokens. See AGENTS.md for why
 * every role assignment (not one resolved scope) travels in the token, and
 * for the resulting revocation trade-off.
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(JwtProperties.class)
public class JwtService {

    private final JwtProperties properties;
    private SecretKey key;


    @PostConstruct
    void init() {
        if (properties.secret() == null || properties.secret().isBlank()) {
            throw new IllegalStateException(
                    "app.jwt.secret (JWT_SECRET) is not set. Refusing to start with no signing "
                            + "key rather than silently default to one — see .env.example.");
        }
        // Keys.hmacShaKeyFor throws WeakKeyException below 256 bits for
        // HS256 — this is the "obviously weak" fail-fast check.
        try {
            this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        } catch (io.jsonwebtoken.security.WeakKeyException e) {
            throw new IllegalStateException(
                    "app.jwt.secret (JWT_SECRET) is too short for HS256 (needs 256+ bits). "
                            + "Generate a real one, e.g. `openssl rand -base64 32` — see .env.example.", e);
        }
    }

    public AccessTokenIssue issueAccessToken(
            UUID userId, String email, UUID tenantId, boolean platformStaff,
            List<RoleClaim> roles, List<String> permissions) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        List<Map<String, Object>> roleClaims = roles.stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", r.role());
                    m.put("branch", r.branch() == null ? null : r.branch().toString());
                    return m;
                })
                .collect(Collectors.toList());

        String token = Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("tenant_id", tenantId == null ? null : tenantId.toString())
                .claim("platform_staff", platformStaff)
                .claim("roles", roleClaims)
                .claim("permissions", permissions)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .id(UUID.randomUUID().toString())
                .signWith(key)
                .compact();

        return new AccessTokenIssue(token, expiresAt);
    }

    /**
     * Validates signature and expiry, then extracts the claims. Throws
     * {@link JwtException} (expired, malformed, bad signature — any of
     * them) if the token isn't valid; callers treat that as unauthenticated.
     */
    @SuppressWarnings("unchecked")
    public AccessTokenClaims parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();

        String tenantIdClaim = claims.get("tenant_id", String.class);
        List<String> permissions = claims.get("permissions", List.class);
        List<Map<String, Object>> rawRoles = claims.get("roles", List.class);

        return new AccessTokenClaims(
                UUID.fromString(claims.getSubject()),
                claims.get("email", String.class),
                tenantIdClaim == null ? null : UUID.fromString(tenantIdClaim),
                Boolean.TRUE.equals(claims.get("platform_staff", Boolean.class)),
                permissions == null ? List.of() : List.copyOf(permissions),
                rawRoles == null ? List.of() : rawRoles.stream().map(JwtService::toRoleClaim).toList()
        );
    }

    // issueAccessToken wrote this claim but nothing ever read it back until
    // now — branch scope travelled in the token and was silently discarded.
    // See AccessTokenClaims and TenantContextFilter.
    private static RoleClaim toRoleClaim(Map<String, Object> raw) {
        String role = (String) raw.get("role");
        Object branch = raw.get("branch");
        return new RoleClaim(role, branch == null ? null : UUID.fromString((String) branch));
    }
}
