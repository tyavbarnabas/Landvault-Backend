package com.techcomfort.landvaultbackend.identity.internal.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import java.util.List;

/**
 * Stateless JWT security: no session cookie, {@link JwtAuthenticationFilter}
 * validates the access token and populates authorities from its permission
 * slugs, {@code /api/auth/**} plus health/docs are public, everything else
 * requires authentication. {@code @EnableMethodSecurity} makes
 * {@code @PreAuthorize("hasAuthority('...')")} usable from day one.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/auth/**",
            "/actuator/health",
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
            HttpSecurity http, JwtService jwtService, CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) -> writeError(response, 401,
                                "Authentication required.", "UNAUTHENTICATED"))
                        .accessDeniedHandler((request, response, ex) -> writeError(response, 403,
                                "You do not have permission to perform this action.", "FORBIDDEN")))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        // TODO (next slice): the tenant-context filter sits here — after
        // authentication (it reads tenant_id off the Authentication this
        // filter chain establishes), before the controller.

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
