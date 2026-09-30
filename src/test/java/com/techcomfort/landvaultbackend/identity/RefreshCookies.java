package com.techcomfort.landvaultbackend.identity;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * The refresh token only travels in the {@code lv_refresh} cookie now, so
 * ITs read it from {@code Set-Cookie} and present it as a {@code Cookie}
 * header — TestRestTemplate keeps no cookie jar of its own.
 */
public final class RefreshCookies {

    public static final String NAME = "lv_refresh";

    private RefreshCookies() {
    }

    /** The raw {@code Set-Cookie} line for the refresh cookie; fails if there isn't one. */
    public static String header(ResponseEntity<?> response) {
        List<String> setCookies = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        return setCookies.stream()
                .filter(c -> c.startsWith(NAME + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + NAME + " cookie in " + setCookies));
    }

    /** The token value from the response's refresh cookie. */
    public static String of(ResponseEntity<?> response) {
        String header = header(response);
        return header.substring(NAME.length() + 1, header.indexOf(';'));
    }

    /** How the server stores it — SHA-256 hex, so a test can find the row. */
    public static String hash(String rawToken) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A body-less request carrying the refresh cookie. */
    public static HttpEntity<Void> presenting(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, NAME + "=" + token);
        return new HttpEntity<>(headers);
    }
}
