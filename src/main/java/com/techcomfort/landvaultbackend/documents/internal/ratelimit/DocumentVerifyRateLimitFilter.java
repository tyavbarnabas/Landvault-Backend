package com.techcomfort.landvaultbackend.documents.internal.ratelimit;

import com.techcomfort.landvaultbackend.common.FixedWindowRateLimiter;
import com.techcomfort.landvaultbackend.documents.internal.config.DocumentsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Throttles the public verify route. Keyed on the remote address, never
 * {@code X-Forwarded-For}, for the reasons in {@code MarketplaceRateLimitFilter}.
 * Counts unknown codes too — those are exactly what a guesser sends.
 */
@Component
@RequiredArgsConstructor
public class DocumentVerifyRateLimitFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/documents/verify/";

    private final DocumentsProperties properties;
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter("Document verify");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long retryAfterSeconds = limiter.tryAcquire(request.getRemoteAddr(), properties.getVerifyRequestsPerWindow(),
                properties.getVerifyWindow(), properties.getMaxTrackedClients());
        if (retryAfterSeconds > 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Too many requests from this connection. Please try again "
                    + "later.\",\"code\":\"RATE_LIMITED\",\"fieldErrors\":null}");
            return;
        }
        chain.doFilter(request, response);
    }
}
