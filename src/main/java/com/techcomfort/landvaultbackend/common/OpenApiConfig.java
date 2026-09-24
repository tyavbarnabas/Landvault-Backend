package com.techcomfort.landvaultbackend.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The OpenAPI document's title, security scheme and tag order.
 * <p>
 * <strong>This only describes what exists.</strong> The document is still
 * generated and served under the {@code dev} profile alone — see
 * {@code SecurityConfig.DEV_ONLY_PUBLIC_PATHS} and
 * {@code springdoc.api-docs.enabled} in {@code application.yml}. A publicly
 * readable spec hands an attacker the whole API surface before they have
 * authenticated; that gating is deliberate and this class does not change it.
 * <p>
 * Security is declared <strong>globally</strong> here, so every operation
 * requires the bearer token by default and an endpoint that forgets to say
 * anything is documented as protected — the safe direction to be wrong. The
 * genuinely public ones opt out with {@code @SecurityRequirements} (empty),
 * and that list is cross-checked against {@code SecurityConfig} in AGENTS.md.
 */
@Configuration
public class OpenApiConfig {

    /** Referenced by operations that opt out; see the class comment. */
    public static final String BEARER_SCHEME = "bearerAuth";

    public static final String TAG_AUTH = "Authentication";
    public static final String TAG_TWO_FACTOR = "Two-Factor";
    public static final String TAG_SESSION = "Session";
    public static final String TAG_MARKETPLACE = "Marketplace (public)";
    public static final String TAG_KYC = "Verification";
    public static final String TAG_RESERVATIONS = "Reservations";
    public static final String TAG_CHECKOUT = "Checkout";
    public static final String TAG_PORTAL_ESTATES = "Portal — Estates";
    public static final String TAG_PORTAL_CONFLICTS = "Portal — Conflicts";
    public static final String TAG_ADMIN_TENANTS = "Admin — Tenants";
    public static final String TAG_ADMIN_KYC = "Admin — Verification";
    public static final String TAG_ADMIN_CONFLICTS = "Admin — Conflicts";
    public static final String TAG_ADMIN_AUDIT = "Admin — Audit";

    private static final String DESCRIPTION = """
            The backend for LandVault, a multi-tenant land-investment platform for Nigeria \
            and diaspora buyers.

            ## Three audiences, three prefixes

            - **`/api/marketplace/*` — buyers.** Public: no token, no account. Browsing \
            never requires signing up; only acting does.
            - **`/api/portal/*` — a land developer's own staff.** A tenant sees only its \
            own inventory, enforced by Postgres row-level security rather than by query \
            filters.
            - **`/api/admin/*` — the platform operator.** Tenant verification, the \
            marketplace conflict queue, the audit trail.

            `/api/auth/*` serves all three.

            ## Things worth knowing before integrating

            - **Boundaries are GeoJSON `Polygon` in `[longitude, latitude]` order**, \
            SRID 4326 — not Leaflet's `[lat, lng]`. Getting this backwards raises no \
            error; it silently places the estate somewhere else. The frontend owns the \
            conversion.
            - **Login may return a two-factor challenge instead of tokens.** See \
            `POST /api/auth/login`.
            - **Money is `numeric`/`BigDecimal`**, never floating point. A corner plot's \
            price is computed from its tier and the estate's corner premium, never stored.
            - **Absent data is absent.** A metric that cannot be computed is `null` or an \
            empty list, never a plausible-looking placeholder.
            - **Errors** are `{ message, code, fieldErrors }`, with the HTTP status \
            carrying the primary signal: 4xx is a client error and is never retried, 5xx \
            is transient.
            - **Pagination** is `{ items, total, cursor, hasMore }`. `cursor` is opaque — \
            pass it back, do not parse it.

            ## Not built yet

            Sales, checkout, payments, documents, KYC submission, resale and reviews do \
            not exist. Nothing here is a placeholder for them.
            """;

    @Bean
    public OpenAPI landvaultOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LandVault API")
                        .version("0.0.1-SNAPSHOT")
                        .description(DESCRIPTION)
                        .contact(new Contact().name("LandVault engineering")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("""
                                The access token from `POST /api/auth/login`, sent as \
                                `Authorization: Bearer <token>`.

                                It lasts **15 minutes** and is never checked against the \
                                database, so a role revoked mid-session stays valid until \
                                it expires. Use `POST /api/auth/refresh` for a new one; \
                                refresh tokens rotate on every use.""")))
                // Protected by default: an operation that says nothing is
                // documented as needing a token.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                // Audience-first ordering, so someone integrating the buyer
                // app can ignore everything marked "Admin —" at a glance.
                .tags(List.of(
                        new Tag().name(TAG_AUTH)
                                .description("Registration, login, token refresh and password reset. "
                                        + "Public."),
                        new Tag().name(TAG_TWO_FACTOR)
                                .description("TOTP enrolment, verification and recovery codes. Setup and "
                                        + "confirmation are two separate steps."),
                        new Tag().name(TAG_SESSION)
                                .description("What the current token resolves to. Diagnostics, not a "
                                        + "feature surface."),
                        new Tag().name(TAG_MARKETPLACE)
                                .description("The public buyer surface. No authentication; rate-limited. "
                                        + "Only estates meeting all five publication conditions appear."),
                        new Tag().name(TAG_KYC)
                                .description("A buyer's own identity verification. Gates buying, "
                                        + "never browsing or signup; held once, platform-wide."),
                        new Tag().name(TAG_RESERVATIONS)
                                .description("Holding a plot for 45 minutes while checking out. The "
                                        + "hold is atomic: two buyers can never both take one plot."),
                        new Tag().name(TAG_CHECKOUT)
                                .description("The pending purchase record. Price is captured at "
                                        + "reservation; nothing here allocates a plot."),
                        new Tag().name(TAG_PORTAL_ESTATES)
                                .description("A developer's own estates, blocks, price tiers and plots, "
                                        + "plus publishing to the marketplace."),
                        new Tag().name(TAG_PORTAL_CONFLICTS)
                                .description("A developer's view of boundary conflicts affecting their "
                                        + "own estate. Never identifies the other party."),
                        new Tag().name(TAG_ADMIN_TENANTS)
                                .description("Tenant onboarding, the verification state machine, plan, "
                                        + "status and support access."),
                        new Tag().name(TAG_ADMIN_KYC)
                                .description("Manual review of buyers' identity documents. No "
                                        + "registry is called; every decision is recorded as such."),
                        new Tag().name(TAG_ADMIN_CONFLICTS)
                                .description("The spatial conflict review queue: overlapping land claims "
                                        + "across companies."),
                        new Tag().name(TAG_ADMIN_AUDIT)
                                .description("The append-only audit trail. Read-only by design.")));
    }
}
