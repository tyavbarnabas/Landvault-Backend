package com.techcomfort.landvaultbackend.identity.internal.security;

import com.techcomfort.landvaultbackend.identity.internal.exceptions.AuthException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * The CSRF defence for the two cookie-authenticated routes, refresh and
 * logout: a request carrying an {@code Origin} outside the configured
 * allowed-origins list (the same list CORS uses) is refused. Covers what
 * {@code SameSite} cannot — every subdomain is the same site — and keeps a
 * move to a cross-site deployment a config change. See AGENTS.md.
 * <p>
 * An absent header is allowed: browsers always send {@code Origin} on a
 * cross-site POST, so its absence means a non-browser client, which cannot
 * carry a victim's cookie. A present but unlisted value — including the
 * literal {@code "null"} a sandboxed or privacy-stripped page sends — is
 * refused.
 * <p>
 * Spring's CORS processor already rejects most foreign origins server-side
 * before this runs; it passes same-origin requests, though, and this check
 * does not rely on the CORS configuration staying as strict as it is today.
 */
@Component
@RequiredArgsConstructor
public class OriginGuard {

    private final CorsProperties corsProperties;

    public void requireAllowedOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null) {
            return;
        }
        if (!corsProperties.allowedOrigins().contains(origin)) {
            throw new AuthException.OriginNotAllowed();
        }
    }
}
