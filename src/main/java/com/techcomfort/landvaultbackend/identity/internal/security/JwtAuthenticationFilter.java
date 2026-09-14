package com.techcomfort.landvaultbackend.identity.internal.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Validates the {@code Authorization: Bearer} token and populates the
 * {@code SecurityContext} with the token's permission slugs as authorities
 * — stateless, no database lookup. See AGENTS.md.
 * <p>
 * TODO: the tenant-context filter is the next slice, not this one — it
 * sits right after this filter (after authentication, before the
 * controller), reading {@code tenant_id} off the same principal this filter
 * establishes.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length());
            try {
                AccessTokenClaims claims = jwtService.parse(token);
                List<SimpleGrantedAuthority> authorities = claims.permissions().stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList();

                var authentication = new UsernamePasswordAuthenticationToken(claims, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                // Invalid/expired/malformed — leave unauthenticated rather
                // than throw here; the security chain's own authorization
                // rules turn "no authentication" into 401 for protected
                // endpoints.
                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(request, response);
    }
}
