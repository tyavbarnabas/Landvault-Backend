package com.techcomfort.landvaultbackend.identity.internal.security;

import com.techcomfort.landvaultbackend.tenancy.TenancyApi;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless JWT security: no session cookie, {@link JwtAuthenticationFilter}
 * validates the access token and populates authorities from its permission
 * slugs, {@code /api/auth/**} and {@code /actuator/health} are public in
 * every profile, everything else requires authentication.
 * {@code @EnableMethodSecurity} makes
 * {@code @PreAuthorize("hasAuthority('...')")} usable from day one.
 * {@link TenantContextFilter} runs immediately after {@code JwtAuthenticationFilter}
 * — there must be an authenticated principal before tenant/branch scope can
 * be resolved from it — and before the controller. See AGENTS.md.
 * <p>
 * API documentation (Swagger UI / the raw OpenAPI JSON) is a development
 * affordance, not a production endpoint — see AGENTS.md. It's public only
 * when the {@code dev} profile is active; one chain with a
 * profile-conditional path list rather than two {@code @Profile}-split
 * chains, since duplicating CSRF/CORS/session/JWT-filter/exception-handling
 * wiring across two beans for one list of paths would be the worse
 * maintenance trade-off.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    /**
     * Listed one route at a time, deliberately not as {@code /api/auth/**}.
     * That wildcard was correct while every auth route was public, but
     * {@code /api/auth/2fa/setup|confirm|disable|recovery-codes/regenerate}
     * require an authenticated session — under the wildcard they would have
     * been reachable by anyone, letting a stranger start (or turn off) 2FA on
     * an account. Only {@code /2fa/verify} stays public, because it completes
     * a login and its caller has no token yet.
     * <p>
     * Adding a new public auth route means adding it here; forgetting simply
     * makes it require authentication, which is the safe direction to fail.
     */
    private static final String[] ALWAYS_PUBLIC_PATHS = {
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/api/auth/2fa/verify",
            "/actuator/health",
            // A hand-rolled SecurityFilterChain doesn't get Boot's default
            // exemption for the error-view path — without this, any
            // request Spring MVC can't directly serve (wrong method, no
            // handler) forwards internally to /error, which then gets
            // re-evaluated by this same chain and comes back 401 instead
            // of the real 404/405. Found while verifying this fix, not
            // introduced by it — the original PUBLIC_PATHS had the same gap.
            "/error"
    };

    /**
     * The public marketplace: the only routes an anonymous caller can read
     * data from. Listed one by one, never as {@code /api/marketplace/**}:
     * wishlist, enquiries and reservations will live under that prefix and
     * must require a login, and a wildcard would make them public silently
     * the day they ship. Permitted for GET only, a step stricter than the
     * auth routes above, so a future write under one of these exact paths
     * (a "save" on {@code /api/marketplace/estates/{id}}, say) doesn't
     * inherit anonymous access either.
     */
    private static final String[] PUBLIC_GET_PATHS = {
            "/api/marketplace/estates",
            "/api/marketplace/estates/*",
            "/api/marketplace/estates/*/geojson"
    };

    // A publicly readable OpenAPI document hands an attacker the complete
    // API surface — every endpoint, parameter, response shape — before
    // they've authenticated at all. Free reconnaissance once this is
    // actually deployed, so these are added to the public list only under
    // the `dev` profile (see securityFilterChain below). Also consider
    // disabled outright outside dev via springdoc.api-docs.enabled, so the
    // document isn't generated at all, not just unreachable — see
    // application.yml/application-dev.yml.
    private static final String[] DEV_ONLY_PUBLIC_PATHS = {
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    /**
     * TODO: work factor (currently BCrypt's default, strength 10) should be
     * tuned to production hardware once that's known. The default is
     * acceptable for now, not a permanent choice.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtService jwtService, CorsConfigurationSource corsConfigurationSource,
            Environment environment, TenancyApi tenancyApi)
            throws Exception {
        List<String> publicPaths = new ArrayList<>(List.of(ALWAYS_PUBLIC_PATHS));
        if (environment.matchesProfiles("dev")) {
            publicPaths.addAll(List.of(DEV_ONLY_PUBLIC_PATHS));
        }

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(publicPaths.toArray(new String[0])).permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) -> writeError(response, 401,
                                "Authentication required.", "UNAUTHENTICATED"))
                        .accessDeniedHandler((request, response, ex) -> writeError(response, 403,
                                "You do not have permission to perform this action.", "FORBIDDEN")))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new TenantContextFilter(tenancyApi), JwtAuthenticationFilter.class);

        return http.build();
    }

    // Hand-written rather than via an injected ObjectMapper: Boot 4.1's
    // auto-configured bean is the new tools.jackson (Jackson 3) type, while
    // jjwt-jackson/jackson-datatype-jsr310 elsewhere on the classpath still
    // pull in classic com.fasterxml.jackson 2.x — two distinct ObjectMapper
    // types coexist in this app. message/code here are always our own
    // fixed literal strings (never user input), so skipping real JSON
    // serialization machinery for this one shape is safe, not a shortcut
    // that risks unescaped output.
    private static void writeError(HttpServletResponse response, int status, String message, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\":\"" + message + "\",\"code\":\"" + code + "\"}");
    }
}
