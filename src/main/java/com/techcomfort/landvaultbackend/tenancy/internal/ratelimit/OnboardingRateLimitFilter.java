package com.techcomfort.landvaultbackend.tenancy.internal.ratelimit;

import com.techcomfort.landvaultbackend.common.FixedWindowRateLimiter;
import com.techcomfort.landvaultbackend.tenancy.internal.service.OnboardingProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Throttles the public onboarding form, far tighter than the marketplace:
 * every accepted submission sends two emails, so an unthrottled form is a
 * way to flood HomeTrue's inbox and anyone else's. Keyed on the remote
 * address, never {@code X-Forwarded-For}, for the reasons in
 * {@code MarketplaceRateLimitFilter}. Counts invalid submissions too, so
 * probing with bad bodies isn't free.
 */
@Component
@RequiredArgsConstructor
public class OnboardingRateLimitFilter extends OncePerRequestFilter {

    private static final String PATH = "/api/onboarding-requests";

    private final OnboardingProperties properties;
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter("Onboarding form");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long retryAfterSeconds = limiter.tryAcquire(request.getRemoteAddr(), properties.getSubmissionsPerWindow(),
                properties.getWindow(), properties.getMaxTrackedClients());
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
