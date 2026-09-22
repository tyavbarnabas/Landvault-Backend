package com.techcomfort.landvaultbackend.marketplace.internal.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * Fixed windows: simple, and the edge case (a burst straddling a window
 * boundary gets up to twice the limit) doesn't matter at this scale.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketplaceRateLimitFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/marketplace/";

    private final MarketplaceRateLimitProperties properties;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = System.currentTimeMillis();
        long windowMillis = properties.getWindow().toMillis();
        String client = request.getRemoteAddr();

        if (windows.size() >= properties.getMaxTrackedClients()) {
            windows.values().removeIf(w -> now - w.start >= windowMillis);
            if (windows.size() >= properties.getMaxTrackedClients()) {
                log.warn("Marketplace rate limiter tracked {} clients; clearing", windows.size());
                windows.clear();
            }
        }

        Window window = windows.compute(client, (key, existing) ->
                existing == null || now - existing.start >= windowMillis ? new Window(now) : existing);
        int count;
        synchronized (window) {
            count = ++window.count;
        }

        if (count > properties.getRequestsPerWindow()) {
            long retryAfterSeconds = Math.max(1, (window.start + windowMillis - now + 999) / 1000);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Too many requests. Please slow down and try again shortly.\","
                    + "\"code\":\"RATE_LIMITED\",\"fieldErrors\":null}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static final class Window {
        private final long start;
        private int count;

        private Window(long start) {
            this.start = start;
        }
    }
}
