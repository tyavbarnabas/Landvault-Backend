package com.techcomfort.landvaultbackend.marketplace.internal.ratelimit;

import com.techcomfort.landvaultbackend.common.FixedWindowRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Throttles the public marketplace. An unauthenticated surface with no
 * throttle is an invitation to scrape the whole catalogue or exhaust the
 * database; nothing else in this system was public, so nothing needed this
 * before.
 * <p>
 * <strong>Keyed on {@code request.getRemoteAddr()}, never
 * {@code X-Forwarded-For}.</strong> That header is set by whoever sends the
 * request; trusting it would let any client pick a fresh key per request and
 * turn the limiter into decoration. The assumption this makes: no reverse
 * proxy sits in front of the app today, so the remote address is the real
 * client. {@code server.forward-headers-strategy} is deliberately unset, so
 * Spring doesn't rewrite the remote address from headers either. Behind a
 * proxy, every client would share the proxy's address; the fix then is
 * Tomcat's RemoteIpValve configured with that proxy as the only trusted one,
 * never reading the header here.
 * <p>
 * <strong>In-memory, so per-instance.</strong> Behind more than one server,
 * each keeps its own counts and a client gets the limit once per instance.
 * That needs a shared store (Redis, or the database), recorded in AGENTS.md.
 * <p>
 * The counting itself is {@link FixedWindowRateLimiter}, shared with the
 * onboarding form's limiter.
 */
@Component
@RequiredArgsConstructor
public class MarketplaceRateLimitFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/marketplace/";

    private final MarketplaceRateLimitProperties properties;
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter("Marketplace");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long retryAfterSeconds = limiter.tryAcquire(request.getRemoteAddr(), properties.getRequestsPerWindow(),
                properties.getWindow(), properties.getMaxTrackedClients());
        if (retryAfterSeconds > 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Too many requests. Please slow down and try again shortly.\","
                    + "\"code\":\"RATE_LIMITED\",\"fieldErrors\":null}");
            return;
        }
        chain.doFilter(request, response);
    }
}
