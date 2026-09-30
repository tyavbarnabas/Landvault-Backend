package com.techcomfort.landvaultbackend.identity.internal.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Writes, reads and clears the refresh-token cookie. {@code HttpOnly} is not
 * configurable: it is the reason the cookie exists. The raw token only ever
 * travels in {@code Set-Cookie}/{@code Cookie} headers — never a body, never
 * a URL, never a log line.
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(RefreshTokenProperties.class)
public class RefreshTokenCookies {

    private final RefreshTokenProperties properties;
    private final JwtProperties jwtProperties;

    public ResponseCookie issue(String rawRefreshToken) {
        return build(rawRefreshToken, jwtProperties.refreshTokenTtl());
    }

    /** Same name, path, SameSite and Secure as {@link #issue} — a browser only removes a cookie that matches them. */
    public ResponseCookie clear() {
        return build("", Duration.ZERO);
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> properties.cookieName().equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(properties.cookieName(), value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite(properties.cookieSameSite())
                .path(properties.cookiePath())
                .maxAge(maxAge)
                .build();
    }
}
