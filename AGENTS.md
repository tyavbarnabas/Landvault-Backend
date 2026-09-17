# landvault-backend

Spring Boot backend for **LandVault**, a multi-tenant PropTech land-investment platform (Nigeria + diaspora buyers). This project is the real backend for the frontend prototype at:

```
~/landvault   (repo: figma-make-app / LandVault client frontend, React + Vite + Tailwind)
```

Read `~/landvault/AGENTS.md` and `~/landvault/INTEGRATION.md` before designing any endpoint — the frontend already defines the contract this backend must satisfy (see `API_CONTRACT.md` in this repo, derived from it). Read `~/landvault/CLAUDE.md`'s project memory equivalents too if working from a Claude Code session — a fresh session here starts with no memory of the frontend's design decisions, so re-derive from those files rather than guessing.

## What LandVault is

An enterprise multi-tenant platform: **Super Admin** (platform operator) → **Tenant** (a land-developer company, with branches) → **Client** (buyer). Buyers browse/reserve/purchase plots within estates; developers manage their estate inventory and buyer transactions; Super Admin verifies tenants, governs the public marketplace, and watches platform health. The frontend repo currently implements only the client-facing surface plus a slice of the Super Admin console (tenant verification lifecycle, marketplace listing-conflict detection). The developer portal and the rest of Super Admin are speced (see the frontend's project memory / backlog docs) but not built anywhere yet — this backend is greenfield for all of it.

## Current state of this project

Fresh Spring Initializr scaffold, nothing domain-specific built yet:
- `LandvaultBackendApplication.java` — bare `@SpringBootApplication` entrypoint
- `application.properties` — empty
- No entities, controllers, repositories, or Liquibase changelogs yet

## Stack

- **Spring Boot 4.1.1**, Java 21
- **Spring Data JPA** + **Liquibase** for migrations (put changelogs under `src/main/resources/db/changelog/`; never hand-edit the schema outside Liquibase once real data exists)
- **Spring Modulith** (core + JPA starters) — structure packages as modules, one per domain (see below), with Modulith's application-module boundaries enforced by its test starter rather than an honor system
- **PostgreSQL** — and specifically, the plot-geometry and listing-conflict-detection work (see below) depends on **PostGIS**, not just plain Postgres — provision the Postgres instance with the PostGIS extension enabled
- **springdoc-openapi** (Swagger UI) for the actual API docs once endpoints exist
- Validation, WebMVC, Lombok

## Suggested module boundaries

One Modulith application module per domain, matching the frontend's one-service-file-per-domain split (`~/landvault/src/services/*.ts`):

`auth`, `tenants` (+ branches, verification, audit log), `estates` (+ plots, price tiers), `marketplace` (+ public listing projection, unified feed, listing-conflict detection), `checkout` (+ transactions, reservations), `portfolio` (+ owned plots, payments, installment schedules), `resale`, `upgrade`, `documents`, `kyc`, `inspections`, `disputes`, `reviews`, `syndicates`, `notifications`, `platform-metrics` (Super Admin).

## Auth & permissions model to match

- Two roles today: `"client" | "super_admin"` — plus a **string permission-slug array** per user (e.g. `client.portfolio.view`, `admin.tenants.manage`), not just a role check. See `~/landvault/src/services/authService.ts` for the exact slugs already assumed by the frontend's route/zone gating.
- Bearer token auth: `Authorization: Bearer <token>` on every request; `POST /api/auth/login` returns `{ user, token }`; `POST /api/auth/refresh` re-issues a token (frontend calls this with `credentials: "include"`, anticipating an httpOnly-cookie-based refresh — the frontend's own token storage is `localStorage`, which it flags as an XSS exposure it can't fix unilaterally; this backend's cookie/CORS/CSRF design decides how refresh actually gets secured).
- Super Admin is never self-registered — seed the first Super Admin account directly (matches the frontend's mock `admin@landvault.com` account, which stands in for a seeded row, not a signup path).

## Domain rules worth preserving (the frontend enforces these client-side against mock data; the backend is the real source of truth for all of them)

- **Append-only history, never hard-delete**: `Document.supersedes`, `Tenant.verificationHistory`, `OwnedPlot.supersedes` (set by upgrade/swap) are all chains, not mutable single records. A "void" or "reissue" is a new row referencing the old one, never a delete or in-place edit.
- **Two-step payment verification**: a payment/transaction moves through a "webhook/gateway confirmed" state (`pending_verification`) before a Finance-role action marks it `confirmed`/`verified`. Never auto-confirm a payment.
- **Marketplace publication gate**: an `Estate` only appears on the public marketplace if its owning `Tenant` clears verification AND the estate itself has `published: true` — a developer can pull one listing without touching tenant status.
- **Upgrade/swap delta pricing is signed** — a downgrade produces a real (possibly refundable) negative delta, never clamped to zero.
- **Listing-conflict detection (the SA-3.4 story)** is real PostGIS polygon-intersection math (`ST_Intersects`/`ST_Overlaps` or equivalent) over each estate's real footprint geometry — this is called out repeatedly in the frontend's design notes as *the* differentiating feature; do not let it degenerate into a bounding-box or distance-heuristic shortcut.
- **Plot geometry**: the frontend's `row`/`col` grid is an acknowledged placeholder for real PostGIS-derived polygons — model plots with real geometry from the start here rather than porting the grid model.
- **No fabricated data, ever, in a response** — if a metric/status genuinely isn't computable yet, the contract is an honest absence (`null`/omitted field/empty page), never a plausible-looking placeholder number. This is a hard rule the frontend team already applies to itself (see recent commits removing hardcoded display numbers) and the backend must not undermine it by inventing figures either.

## Spatial data conventions

These are decisions, not preferences — getting them wrong produces silently wrong numbers rather than errors:

- **Store geometry as SRID 4326** (WGS84 lat/lng), declared as `geometry(Polygon,4326)`. This matches the `GeoPoint` lat/lng shape the frontend already uses for estate footprints.
- **Compute areas via a geography cast**: `ST_Area(footprint::geography)` returns square **metres**. Calling `ST_Area` on raw 4326 geometry returns square **degrees**, which is meaningless for plot sizing and will look like a plausible number.
- **Every geometry column gets a GiST index.** Spatial queries without one degrade to full table scans. The rule above forbidding a bounding-box heuristic in place of real intersection means the real `ST_Intersects` path has to stay fast enough to use.

## Response conventions to match

- **Pagination envelope** (`Page<T>` in `~/landvault/src/lib/pagination.ts`): `{ items: T[], total: number, cursor?: string, hasMore: boolean }`. `cursor` is opaque — the frontend never parses it, so encode it however's convenient (keyset-encoded, base64, etc.). Only genuinely unbounded lists use this; a naturally small/bounded list (e.g. one estate's price tiers) can stay a plain array.
- **Error body**: `{ message?: string, code?: string, fieldErrors?: Record<string,string> }`, with the HTTP status carrying the primary signal (4xx = client error, never retried; 5xx = transient, the frontend retries idempotent/idempotency-keyed requests with backoff).
- Non-idempotent mutations (POST) that the frontend needs retried safely should accept an `Idempotency-Key` header — the frontend's `apiClient` already sends one when it wants that.

See `API_CONTRACT.md` in this repo for the concrete endpoint-by-endpoint surface, derived from every exported function in `~/landvault/src/services/*.ts`.

## Modulith package structure

Spring Modulith treats a module's top-level package as its **public API**;
only sub-packages named `internal` are hidden from other modules. Concretely:

```
identity/
    IdentityApi.java        ← public — the module's only public surface
    dto/UserDto.java         ← public — safe to share
    internal/
        domain/User.java     ← invisible outside identity
```

**Never expose an entity or a repository from a module.** Define a
`<Module>Api` interface that returns DTOs instead. Entities carry `tenantId`
and operate under row-level-security expectations; if another module can
obtain a raw entity and a repository, it can query around the isolation
guarantees RLS exists to provide. Keeping repositories/entities `internal`
makes that structurally impossible rather than merely discouraged. **If you
find yourself wanting to expose a repository or entity from a module, the
boundary is in the wrong place.**

A DTO must not itself expose an internal type through its own signature
(e.g. an internal enum) — translate to a public shape (a String wire value,
a public enum in `common`, etc.) at the DTO boundary instead.

`common` is the one deliberately shared/open module (`ApplicationModule.Type.OPEN`)
— every module depends on it, which is the point of a shared kernel, not a
boundary violation. Keep it free of domain logic: base entities, enums used
across modules, and cross-cutting configuration only.

Only create a module's package (and its `package-info.java`) once its first
real class exists — an empty module package is noise Modulith verification
doesn't need. The verification test (`ApplicationModules.of(...).verify()`,
a plain unit test, not `@SpringBootTest`) is what converts "we agreed not to
reach into `internal`" into a build failure instead of an honor system.

## Tenant nullability and why `@TenantId` was rejected

`tenantId`/`branchId` on `AbstractEntity` are nullable on purpose. Four kinds
of user share one `users` table and only one is genuinely tenant-scoped:

| kind | tenantId | branchId |
|---|---|---|
| platform staff (Super Admin, moderator, compliance) | null | null |
| buyer / investor | **null** | null |
| tenant staff (Executive Director, finance, surveyor) | set | usually set |
| independent agent | null | null |

**A buyer must never be tenant-scoped.** On the national marketplace a buyer
transacts with several companies under one account and one document vault —
what links a buyer to a company is their *transaction*, not their user row.
The same reasoning nulls it for `organizations` (it *is* the tenant),
`branches` (can't reference itself), `roles`, `permissions`, and `audit_log`.

## Two levels of scoping — tenant and branch, and they behave differently

Beyond tenant scope (Estintin never sees Crestview's data — absolute), there
is a second, narrower level: **branch scope** (within Estintin, a Double
King manager sees only Double King's estates, plots and clients; Heritage's
are invisible to them).

The two levels of scoping behave differently by role, and that difference is
deliberate:

- For a **branch manager**, branch scope is a **hard wall**. They cannot
  widen it, and no UI control should offer to.
- For an **Executive Director**, branch scope is a **switchable lens**. They
  default to organization-wide and may narrow to view one branch — a branch
  *switcher*, not a restriction.

Same mechanism (a `scoped_branch_id`), different permission to change it.
This is why the distinction is carried by the **user's role assignment**
(`user_roles.scoped_branch_id`), not by a single `branch_id` on `users`
itself: the same person can legitimately hold an organization-wide finance
role and a branch-scoped sales role at once, and a user's branch scope isn't
one fixed fact about them — it's a property of *which role* they're acting
under.

**Consequence for the service layer (not built yet):** when an Executive
Director selects a branch to view, the backend must verify that branch
belongs to their organization *and* that their role assignment actually
permits the switch, before honouring it — otherwise it's just a
client-supplied parameter an attacker can tamper with to view another
branch (or, worse, the switch logic gets applied to a hard-wall role like
branch manager, defeating the wall entirely).

## Permissions are assembled at login, never stored on `users`

The frontend's `AuthUser.permissions` string array is assembled at login by
flattening a user's roles' permissions — it is not a column on `users`.
Storing it there would mean rewriting every affected row whenever a role's
permission set changes, and would drift out of sync the moment it did.
`user_roles` → `role_permissions` → `permissions` is the only source of
truth; a future service computes the flattened array per login, it never
gets persisted as its own field.

Hibernate's `@TenantId` was deliberately **not** used, because it filters
every query by the current tenant automatically — which would silently
return an *empty result* (never an error) for three reads this platform
genuinely needs across tenants: Super Admin's tenant directory, the public
marketplace feed (aggregating published listings across companies), and
spatial listing-conflict detection (inherently a cross-tenant polygon query
looking for one company's boundaries overlapping another's). Isolation
instead comes from Postgres row-level security plus explicit repository
scoping — one mechanism, enforced at the database level.

## Soft delete is an entity-level filter, not a repository convention

Every soft-deletable entity gets `@SQLRestriction("deleted = false")`
(Hibernate 6.3+; not the deprecated `@Where`) directly on the entity.
Relying on each repository method to remember `WHERE deleted = false` is the
same class of bug that row-level security exists to prevent for tenants —
the filter belongs on the entity, once, not re-implemented (and eventually
forgotten) per query.

## `organizations` vs. `tenant_id` — the naming split

The table is **`organizations`**, the entity is **`Organization`**, but the
foreign key *other* modules use to point at it is **`tenant_id`**
(`AbstractEntity.tenantId`). This isn't an inconsistency — it's deliberate:
"tenant" is the architectural term (an isolated customer of the platform);
"organization" is the domain term (Estintin Group, a real company with a CAC
number). The FK name reflects what the column is *for* — isolation — while
the table reflects what the row *is*. Don't rename either half to match the
other, and don't invent a second `Organization`/`Tenant` entity — there is
exactly one row type here; "tenant" and "organization" are two names for it,
not two things.

Within the `tenancy` module itself, `Organization` and `Branch` don't reuse
the generic inherited `tenantId`/`branchId` columns for their own
relationships — both stay `null` on every row of both entities (an
organization *is* the tenant, so it has no parent tenant; a branch cannot
reference itself as its own branch). `Branch`'s real link to its
`Organization` is the explicit, purpose-named `organization_id` FK instead.

## The two status axes on `Organization` — do not collapse them

- **`status`** (`TenantStatus`) — can this tenant's staff use the portal at
  all?
- **`verificationState`** (`VerificationState`) — can this tenant publish to
  the marketplace or collect payments?

These are independent. A tenant can legitimately be `ACTIVE` +
`UNDER_REVIEW` (exploring the portal, setting up estates, while compliance
review is pending) or `VERIFIED` + `SUSPENDED` (compliant but suspended for
non-payment — see Northbridge Estates in the frontend's seed data).
Collapsing them into one column would make both of those real states
unrepresentable. Each column carries a Postgres column comment recording
this at the schema level, not just here.

## Title documents are per-estate, not per-company

`organization_documents` holds corporate verification documents (CAC
certificate, CAC status report / Memart, TIN, proof of address, SCUML
certificate, state regulator permits, REDAN certificate). It deliberately
does **not** hold land title documents (C of O, R of O, Governor's Consent,
Gazette, survey plan) — title evidence is per-estate, not per-company, since
a company can hold clean title on one estate and none on another. Title
lives on the estate record and is captured at estate creation, whenever that
lands; don't add title-document columns/types to `organization_documents`.

**LASRERA is not structurally special.** `organization_state_regulators` is
a plain repeatable list — LASRERA is just a row with `state = 'Lagos'` and
`regulator_name = 'LASRERA'`. The onboarding wizard pre-fills one of these
rows when Lagos is among the organization's states of operation; there is no
dedicated LASRERA column, table, or code path, and none should be added.

`organization_documents`/`organization_regulatory`/`organization_state_regulators`
belong to a specific tenant (unlike `Organization` itself, which *is* the
tenant) — each sets the inherited `tenantId` equal to its own
`organizationId` in `prePersist`, rather than leaving it null.
`AbstractTenantEntity` doesn't exist yet; when it's introduced, migrate these
three onto it rather than leaving them on plain `AbstractEntity` with a
manually-populated `tenantId`.

## `dev` profile and SQL logging

`show-sql`/`format_sql` live in `application-dev.yml`, not the base
`application.yml` — the base config stays quiet under every environment
where no profile is explicitly activated, including tests and any deploy
that forgets to set one. **`dev` is the local default**: local development
is expected to run with `SPRING_PROFILES_ACTIVE=dev` set (already in
`.env.example`/`.env`, loaded via `springboot4-dotenv`), not via a
Spring-level `spring.profiles.default` fallback baked into `application.yml`
— that was tried and reverted, because it makes `dev` (and SQL logging)
active for *anything* that doesn't explicitly set a profile, which is the
opposite of what "quiet by default" means once a real deploy exists. This
matters beyond noise: bind parameters get logged too, and this module now
stores NDPR-regulated personal data (`directors.id_number`) that must
never land in a shared log.

## `directors` is the most sensitive table in the system

`directors.id_number` is NDPR-regulated personal data, stored in plaintext
today with none of the following yet implemented — each is a standing TODO
carried in the table's own Postgres comment, not just here: encrypt at
rest; restrict reads to compliance staff (never all platform staff, never
another tenant's staff); log every read as an auditable event, not an
ordinary query; define and enforce a retention policy. Whoever builds the
repository/service layer for this table owns closing these, not deferring
them further.

`directors.bvn` used to carry the same obligations and no longer exists at
all — see "BVN was removed" below for why, and the one condition under
which it could legitimately come back.

**`is_beneficial_owner` is stored, never derived.** The threshold is 25%
ownership (standard AML practice, protecting the platform from onboarding a
front company), but the flag is a compliance assertion made at a point in
time — recomputing it live from `ownership_pct` would silently rewrite who
was flagged when as ownership changes. Never replace the stored column with
a computed one.

## The account-name mismatch is a reviewer signal, not a constraint

`organization_financial.account_name` is allowed to differ from
`organizations.registered_name` — trading names, abbreviations, and recently
renamed companies are all legitimate. No DB constraint or trigger compares
them; the frontend already treats a mismatch as a warning banner
(`accountNameLooksMismatched`), never a validation failure, and the backend
preserves that: the comparison belongs in a verification service, computed
at review time, not enforced at write time.

## No gateway credentials in the database

`organization_gateways` records connection *status* only (`gateway_name`,
`status`, `connected_at`) — never an API key or secret. Those belong in a
secrets manager. Do not add an encrypted-credential column to this table
later; "just one encrypted column" is how credentials end up in a database
backup. Also: **Stripe is not a Nigerian local rail** — local collection
runs through the enumerated `GatewayName` providers; diaspora payments use
virtual accounts or international wire instead. Don't add Stripe to that
enum.

## JSON enum-mapping strategy

Every JSON-facing enum carries an explicit `@JsonValue`-annotated `value`
field plus a matching `@JsonCreator` static factory, mapping the Java
constant (`UPPER_SNAKE_CASE`, always persisted via
`@Enumerated(EnumType.STRING)` — never ordinal, since ordinal silently
reinterprets every existing row if a constant is reordered or inserted) to
the frontend's literal wire value. This is one mechanism, applied uniformly,
rather than a global Jackson naming strategy plus per-enum exceptions —
several of the frontend's unions (`CompanyType`, `GovIdType`, `GatewayName`)
carry values with spaces, parentheses, a slash, or brand capitalization that
no case-conversion strategy produces automatically, so every enum uses the
explicit pair regardless of whether its own values would have fit a
convention. Enum values must match the frontend's unions exactly — read them
from the relevant `~/landvault/src/services/*.ts` file; never invent or
rename a state. `EnumJsonMappingTests` round-trips a couple of representative
enums (`VerificationState`, `VerificationDecisionType`) through Jackson as a
cheap regression guard — extend it if a new enum's mapping is non-obvious.

**Unverified risk, found while wiring identity's controllers (2026-09-14):**
Spring Boot 4.1's auto-configured `ObjectMapper` is the new `tools.jackson`
(Jackson 3) stack, not the classic `com.fasterxml.jackson` one —
`spring-boot-starter-jackson` pulls `tools.jackson.core:jackson-databind`,
a different Maven coordinate and Java package entirely. `EnumJsonMappingTests`
only proves `@JsonValue`/`@JsonCreator` work against a hand-built
`com.fasterxml.jackson.databind.ObjectMapper` — it does **not** prove Spring
MVC's actual runtime message conversion (the `tools.jackson`-based one)
honors those same `com.fasterxml.jackson.annotation` annotations. `jackson-annotations`
staying at its classic coordinates in the dependency tree (unlike
`jackson-core`/`jackson-databind`) is a strong signal it does, but this
hasn't been proven by an actual HTTP round trip of a non-trivial enum yet —
none of identity's slice-2 endpoints happen to serialize one (`Currency`
round-trips fine either way since Jackson's default `name()`-based
enum serialization needs no annotation and already matches the frontend's
uppercase values). **Verify this for real before the first tenancy-module
controller ships a `TenantStatus`/`VerificationState`/etc. over HTTP** — a
quick MockMvc or WebTestClient round trip of one such enum is enough.

## Append-only tables: `AbstractAppendOnlyEntity`

`verification_decisions` and `support_access_grants` are where the
append-only invariant first becomes structural rather than a convention.
Both extend `common.AbstractAppendOnlyEntity`, **not** `AbstractEntity` —
it's a separate `@MappedSuperclass` that redeclares `id`/`createdAt` rather
than inheriting them, specifically so it cannot pick up `AbstractEntity`'s
mutable columns: no `updatedAt`/`updatedBy` (implies a mutability that must
not exist), no `deleted` (a soft-delete flag on an audit trail directly
contradicts "append-only" — a decision that can be hidden is not a record),
no `tenantId`/`branchId` (these entities carry their own explicit
`organization_id` FK instead, same as every other tenancy entity). A
correction is a **new row**, never an edit to the old one. Neither entity
gets `@SQLRestriction` — there's no `deleted` column to filter on.

This currently rests on application discipline alone (no repository writes
an UPDATE/DELETE to these tables) — the class carries a TODO to enforce it
at the database level too (revoke UPDATE/DELETE grants, or a trigger) so it
survives more than just correct application code. Not implemented yet.

Don't retrofit slices 1–3's entities onto `AbstractAppendOnlyEntity` — they
genuinely need `updatedAt`/soft-delete, this base class is for the two
tables where mutability must be structurally impossible, not a general
replacement for `AbstractEntity`.

## `REQUEST_MORE_INFO` doesn't transition verification state

Recording a `VerificationDecision` with `decision = REQUEST_MORE_INFO` does
**not** change the organization's `verification_state` — it stays
`UNDER_REVIEW` while the reviewer waits for a clearer scan. Only `APPROVED`
and `REJECTED` transition state. The transition logic itself lands in a
later slice (the service layer); this rule is recorded now so it isn't
reinvented differently when that slice is built.

## Support access is visibility, not authority

`support_access_grants` is time-boxed, reason-required, and fully logged —
and it must **never** be usable to move money or sign documents on a
tenant's behalf. It exists for troubleshooting a real issue, not as a
side-channel around the platform's normal authorization rules. The
enforcement of that boundary belongs in the authorization layer, built in a
later slice; until then, treat it as a hard constraint on that future
design, not a detail to reconsider once it's convenient not to.

## JWT carries every role assignment, not one effective scope

A user may hold several role assignments with different branch scopes at
once — an organization-wide finance role *and* a branch-scoped sales role.
The access token's `roles` claim carries **all of them**, not a single
resolved role/scope pair. This is what lets an Executive Director switch
branches without re-authenticating, and it avoids a DB round-trip on every
request.

**The trade-off this creates, stated plainly: a revoked role stays valid
until the access token expires**, because the token is never checked
against the database. This is why the access token is short-lived (15
minutes) — it bounds the window, it doesn't close it. Immediate revocation
would require either a denylist (checked on every request, which defeats
much of the point of a stateless token) or shortening expiry further.
Neither is built; the 15-minute window is the accepted exposure for now.
Don't "fix" this by silently adding a per-request DB check — that's a real
design trade-off to make deliberately, not a bug to patch quietly.

## Token lifetimes and refresh rotation

- **Access token: 15 minutes.** Stateless — signed, never looked up in the
  database. `tenant_id` in its claims comes from the database at login,
  **never from client input** — a client-supplied tenant would be a
  cross-tenant data breach, full stop.
- **Refresh token: 30 days.** Stored as `token_hash` only (never the raw
  value), revocable, rotated on every use.

**Rotation**: each `/api/auth/refresh` call issues a new refresh token,
marks the presented one `revoked_at`, and sets its `replaced_by` to the new
row's id — a chain, not an overwrite.

**Theft detection is why refresh tokens are stored at all**: if an
already-`revoked_at` token is ever presented again, that's a signal the
token was stolen and used after the legitimate client already rotated past
it. The correct response is to revoke the *entire token family* for that
user (walk the `replaced_by` chain, or simply revoke every non-expired
token for that `user_id`) and force re-login — not to quietly reject the
one request and let the rest of the family keep working.

**Concurrent refresh of the same token is resolved with a DB-level atomic
compare-and-swap** (`UPDATE ... WHERE revoked_at IS NULL`, checking the
affected-row count), not an in-Java check-then-write — the frontend's
apiClient already single-flights refresh calls, but the backend can't
assume every caller does. Only one concurrent request can ever win the
rotation; the loser deletes its own orphaned token and triggers the same
family-revocation path as reuse of a revoked token, rather than forking a
divergent branch.

**Gotcha worth knowing before touching `AuthService.refresh()`**: its
`@Transactional` is `noRollbackFor = AuthException.InvalidRefreshToken.class`,
and that's load-bearing, not incidental. The theft-detection and lost-race
branches deliberately write (revoke the family / delete an orphaned token)
and *then* throw to fail the request — Spring's default rollback-on-any-
unchecked-exception behavior would otherwise silently undo exactly the
revocation those branches exist to make stick. This was a real bug caught
by the Testcontainers integration test, not the mocked unit tests (mocks
don't roll back anything, so they couldn't have caught it) — a reminder
that the integration test isn't redundant with the unit tests here.

## Permission slugs are authorities; `tenant_id` is never client-supplied

The `permissions` claim (flattened, deduplicated union of the user's roles'
permissions — see the earlier "Permissions are assembled at login" note) is
what `JwtAuthenticationFilter` loads into the `SecurityContext` as Spring
Security authorities, which is what makes
`@PreAuthorize("hasAuthority('admin.tenants.view')")` work. `tenant_id` gets
the same treatment as the permissions: read from the authenticated user's
own row at login/refresh time, placed in the token, never accepted as a
request parameter or body field for any endpoint that would use it to scope
a query.

## API documentation is a development affordance, not a production endpoint

A publicly readable OpenAPI document (Swagger UI, `/v3/api-docs`) hands an
attacker the complete API surface — every endpoint, parameter, and response
shape — before they've authenticated at all. Free reconnaissance, once this
is actually deployed. Both are gated to the `dev` profile two ways at once:
`SecurityConfig` only adds their paths to the public list when
`Environment.matchesProfiles("dev")`, and `springdoc.api-docs.enabled`/
`springdoc.swagger-ui.enabled` are `false` in the base `application.yml`
(re-enabled in `application-dev.yml`) so the document isn't even generated
outside `dev`, not merely unreachable. `/api/auth/**` and
`/actuator/health` stay public in every profile — real clients and health
checks must work regardless of environment.

**Gotcha for anyone touching `SecurityConfig` again**: a hand-rolled
`SecurityFilterChain` does not get Spring Boot's default exemption for the
error-view path. Any request the app can't directly serve — wrong HTTP
method, no matching handler — gets an internal forward to `/error` to
render the error response, and that forward is itself a fresh request this
same chain evaluates. Without `/error` in the public path list, that
forward gets rejected as unauthenticated, and the real 404/405 is masked by
a misleading 401. `/error` is in `ALWAYS_PUBLIC_PATHS` for exactly this
reason — don't remove it as looking redundant.

## The tenant-context filter: how a request becomes a scoped database session

`TenantContextFilter` (`identity.internal.security`) runs immediately after
`JwtAuthenticationFilter` and before the controller. It resolves a
`common.TenantScope` from the authenticated principal and stores it in
`common.TenantContext` (a `ThreadLocal`) for the life of the request. No RLS
policies exist yet — they come next, and are meaningless until something
sets the session variables they'd read. This slice is that something.

**The tenant/branch scope comes from the signed token, never from a
request header.** `X-Tenant-ID` naming a tenant directly is the common
tutorial pattern and looks reasonable, but a header is trivially
client-supplied while the JWT is signed — honouring a client-supplied
tenant id would be a cross-tenant breach on the first request that tried
it. The one header this filter does read, `X-Branch-Id`, only ever
*narrows* a scope the token already establishes and authorizes — it can
never name a tenant, and it's checked against the caller's own
already-resolved tenant before being honoured at all.

### Branch resolution (from the token's `roles` claim)

- **No role assignment carries a branch** (all null, including holding no
  role assignments at all) → organization-/platform-wide. Executive
  Directors, group finance officers.
- **Every assignment names the same one branch** → that branch, and it's a
  **hard wall**: nothing this filter does can widen it. A branch manager.
- **Assignments name different branches, or an organization-wide
  assignment is mixed with a branch-scoped one** (an org-wide finance role
  plus a branch-scoped sales role, say) → organization-wide. The user has
  legitimate reach beyond one branch either way; narrowing would hide data
  they're entitled to. The switcher (below) lets them narrow on request
  instead of the filter guessing which branch they meant.
- **Platform staff** → tenant and branch both null, unconditionally,
  regardless of whatever role claims happen to be present.

### The branch switcher (`X-Branch-Id`)

Honoured only when **all** of: (1) the base scope is organization-wide
(branch-scoped users are a hard wall — this can narrow, never re-target or
widen); (2) the requested branch belongs to the caller's own tenant
(`TenancyApi.branchBelongsToTenant` — the one method this slice added to
that interface, and the only thing it does); (3) the caller isn't platform
staff, who have no tenant of their own to narrow within. Failing any check,
or a malformed header, the header is silently ignored (logged at debug),
never a 403 — a 403 here would confirm to a prober whether a guessed branch
id exists.

### Why `SET LOCAL`, never plain `SET`

`SET LOCAL` scopes a Postgres session variable to the current transaction
only; it's gone the moment that transaction ends, regardless of what
happens to the physical connection afterward. Plain `SET` persists on the
connection itself — the next tenant whose request happens to borrow that
same pooled connection would inherit the previous tenant's session
variables. Same class of bug as a missing `TenantContext.clear()`, one
layer lower in the stack and much harder to notice, because it depends on
which physical connection the pool happens to hand out next.

### Which transaction hook, and two wrong ones tried first

`common.TenantScopedDataSource` wraps the app's `DataSource` bean (via a
`BeanPostProcessor`, `TenantScopedDataSourceConfig`, so it applies
identically to the auto-configured Hikari pool and to a Testcontainers
`@ServiceConnection` datasource in tests) and issues the three `SET LOCAL`
statements the instant a connection's `setAutoCommit(false)` call is
observed — via a `java.lang.reflect.Proxy` around the JDBC `Connection`,
not just around `DataSource.getConnection()`. Getting there took two wrong
attempts worth recording so they aren't retried:

1. **Gating on `TransactionSynchronizationManager.isActualTransactionActive()`
   inside `getConnection()`.** That flag only flips `true` in Spring's
   `prepareSynchronization()`, which runs *after*
   `JpaTransactionManager.doBegin()` returns — but the physical connection
   is acquired *inside* `doBegin()`. The check was always false at exactly
   the point this code could act, confirmed with a temporary debug log
   showing `isActualTransactionActive()=false` on the very connection a
   `TenantScope` was already correctly resolved for.
2. **Dropping that check and running `SET LOCAL` unconditionally in
   `getConnection()`, trusting Postgres to no-op it outside a real
   transaction.** `getConnection()` fires before the caller has done
   *anything* to the connection, including flipping `setAutoCommit(false)`
   — so `SET LOCAL` always ran while the connection was still in the
   pool's default autocommit=true state. Postgres doesn't quietly ignore
   this: it emits `WARNING: SET LOCAL can only be used in transaction
   blocks` *and*, worse, registers the custom GUC as an empty-string
   placeholder from then on — every later `current_setting(..., true)`
   read on that connection then returns `''`, indistinguishable from this
   design's own deliberate "authenticated, no tenant" empty-string
   convention. This was caught by `TenantContextIT`, not guessed —
   `dbPlatformScope` came back `""` instead of `"off"`, which is what
   led to testing the exact failure directly against Postgres
   (`SET LOCAL x = 'y'; SELECT current_setting('x', true);` over two
   separate statements, no enclosing `BEGIN`) and seeing the same warning
   and the same empty-string result.

The fix: stop inferring "a transaction is probably starting" from
`DataSource`-level timing, and react to the one call that unambiguously
means a real transaction has begun — `Connection.setAutoCommit(false)`.

Two more, smaller things worth knowing if this is touched again:

- The verification endpoint behind this (`GET /api/me/tenant-scope`,
  `MeController`) reads the Postgres session variables back via the same
  `EntityManager` every repository in this codebase already uses, not a
  separately-acquired `JdbcTemplate` connection. `JdbcTemplate`'s own
  `getConnection()` isn't bound to the JPA-managed transaction/connection
  by default in this app (that requires explicitly configuring
  `JpaTransactionManager.setDataSource(...)`, not done here), so its
  queries would autocommit independently — the same "`SET LOCAL` reverts
  before the next statement" failure as above, one level higher. This was
  tried first and every value came back `NULL`.
- `TenantContextIT`'s cross-tenant leak test pins
  `spring.datasource.hikari.maximum-pool-size=1` for that test class.
  Without it, HikariCP would *probably* still hand the same physical
  connection to two sequential requests, but not certainly — pinning the
  pool makes connection reuse guaranteed rather than likely, which is what
  turns this into a real regression test for "`SET LOCAL`, never plain
  `SET`" instead of one that could pass by luck.

## Row-level security: the database enforces isolation, not the application

The session variables `TenantScopedDataSource` sets are meaningless until
something reads them. This is that something — RLS policies on the
tenant-owned tables, enforced by Postgres itself rather than by repository
code that something (a native `ST_Intersects` query, a psql session, a
future reporting tool) could always bypass.

### Which tables are policied, and which are deliberately not

**Policied**: `organization_documents`, `organization_regulatory`,
`organization_state_regulators`, `directors`, `organization_financial`,
`organization_gateways` (generic `tenant_id`/`branch_id` shape, changeset
020); `branches` (custom shape, changeset 021 — see below); `organizations`
(its own id is the tenant, changeset 022).

**Deliberately not policied, and why**:

- `roles`, `permissions`, `role_permissions` — platform-wide reference
  data, identical for every user. Policying them would break login for
  everyone; there is no "tenant" for a permission slug to belong to.
- `refresh_tokens` — looked up by `token_hash` only, never enumerated by
  tenant, and needed during `/api/auth/refresh` *before* any tenant scope
  exists — refresh is one of the things that establishes a scope, it can't
  presuppose one.
- `verification_decisions`, `verification_decision_documents`,
  `support_access_grants` — reached only through their organization today,
  with no tenant-facing endpoint consuming them yet. TODO: candidates for
  policies once something tenant-facing exposes them; policying now would
  only block the Super Admin flows that are their sole consumer.
- `event_publication` — Modulith infrastructure, not domain data.
- **`users` — the one deviation from this slice's own literal spec, and
  the most important thing to understand before touching this table.**
  RLS on `users` cannot be implemented as "self-read + tenant + platform"
  without service-layer changes this slice was explicitly constrained not
  to make, for two independent, structural reasons, not one:
  1. `AuthService.register()`'s duplicate-email check
     (`existsByEmailIgnoreCase`) must see every tenant's rows to enforce
     global email uniqueness — no per-tenant or per-user scoping can ever
     satisfy a query that is inherently cross-tenant by nature.
  2. `AuthService.login()` (looked up by email) and `.refresh()` (looked
     up by user id from the refresh token) both read `users` *before* any
     `TenantContext` exists — same bootstrapping shape as `refresh_tokens`
     above, just one table over. A self-read policy keyed on a
     `landvault.user_id` GUC doesn't help either: at login you don't know
     your own id yet, that's the whole point of looking yourself up.

  Enabling RLS on `users` today, done "properly," would either lock out
  every login (fail-closed with no scope to satisfy) or require a real
  architectural addition (e.g. a `SECURITY DEFINER` function or a
  dedicated bootstrap role for exactly these three queries) — genuine
  future work, not something to bolt on silently. `users` stays open for
  now; closing this gap is a TODO for whoever builds the next slice that
  touches identity's repository layer.

### Why `branches` needed a different policy shape

`Branch.tenantId`/`branchId` are never populated (see the entity's own
class Javadoc) — only `organizationId` is. Every other tenant-owned entity
sets `tenantId = organizationId` in `prePersist`; `branches` alone doesn't.
Writing its policy against the generic `tenant_id` column would have hidden
every branch from every tenant user, silently, since that column is always
null on every row. The policy compares `organization_id` instead. It also
doesn't have a `branch_id` FK the way other tables do — each row *is* a
branch — so its own branch-scope check compares the row's own `id` against
`landvault.branch_id`, not a `branch_id` column.

### Why a restricted database role, not just policies

**Superusers (and any role with `BYPASSRLS`) unconditionally bypass row-
level security — `FORCE ROW LEVEL SECURITY` cannot override this, full
stop.** Before this slice, the application connected as the `postgres`
superuser for everything. Every policy in 020/021/022 would have been
syntactically correct and completely inert — the app itself would have
seen every row, every time, regardless of what any policy said. This was
caught before writing a single policy, by checking
`pg_roles.rolbypassrls` for the connection the app actually used, not
assumed.

The fix (changeset 019): a new, non-superuser, non-owning role
(`landvault_app`) for the application's own runtime connection
(`spring.datasource.*`). Liquibase keeps running as the superuser
(`spring.liquibase.*`, configured as an explicit, separate connection in
`application.yml` — `url` is repeated rather than left to fall back to the
primary datasource bean, so Liquibase is guaranteed its own connection
rather than silently inheriting the app's restricted one) — migrations
need `CREATE ROLE`/`GRANT`/`CREATE EXTENSION` privileges this role
deliberately doesn't have. The role's password is never hardcoded in the
changelog — `${appDbUsername}`/`${appDbPassword}` are Liquibase changelog
parameters bound from `APP_DB_USERNAME`/`APP_DB_PASSWORD` (`.env`, same
"never a secret in a committed file" rule as `JWT_SECRET`).

Since `landvault_app` doesn't own these tables (the superuser role that ran
the migrations does), `FORCE ROW LEVEL SECURITY` is defensive-in-depth for
this specific role rather than strictly load-bearing today — RLS already
applies to any non-owning, non-superuser role regardless of FORCE. It's
kept anyway so this doesn't silently regress if table ownership ever
changes.

**Integration tests can't use `@ServiceConnection` for this** — that
annotation wires *both* `spring.datasource.*` and `spring.liquibase.*` to
the same container credentials, and this slice specifically needs them to
differ (superuser for Liquibase, restricted role for the app). `RowLevelSecurityIT`
wires both explicitly via `@DynamicPropertySource` instead. Tests that
don't touch a policied table (`AuthenticationIT`, `TenantContextIT`,
`SwaggerDevProfileIT`) were left on `@ServiceConnection` — they never
exercise RLS either way, since `users`/`roles`/`permissions`/`refresh_tokens`
aren't policied.

### The fail-closed default, and its consequence

With neither `landvault.tenant_id` nor `landvault.platform_scope` set —
Liquibase's own connection, an unauthenticated request, a future
background job or scheduled task that never establishes a scope — every
policy in this slice evaluates false, and the table returns zero rows.
That's deliberate: fail closed, not open. A bug that forgets to establish
scope should make data disappear, not leak.

**The consequence, worth remembering before writing the first scheduled
job**: it cannot rely on "no context means see everything." Any background
process that needs real data access has to explicitly establish a scope —
platform scope for something that legitimately spans tenants, or an actual
tenant scope for something that doesn't — the same way `TenantContextFilter`
does for a request. There is no ambient "system" identity that sees past
RLS today.

### `tenant_id::text = current_setting(...)`, never `current_setting(...)::uuid`

Found by a failing integration test, not by inspection. The policies
originally cast the *setting* to `uuid`:
`tenant_id = current_setting('landvault.tenant_id', true)::uuid`, guarded
by an earlier `current_setting(...) != ''` check in the same `AND`/`OR`
chain. Postgres does not guarantee left-to-right short-circuit evaluation
of a `WHERE`/`USING` boolean expression the way a procedural language
does — the planner is free to evaluate the `::uuid` cast before the guard
immediately to its left that was meant to protect it, and casting `''` to
`uuid` throws `invalid input syntax for type uuid: ""`. `RowLevelSecurityIT`
caught this immediately (every read failed, not just returned wrong rows).
The fix: cast the *column* to text instead —
`tenant_id::text = current_setting('landvault.tenant_id', true)` — which
has no cast that can ever fail, since a `uuid` column has no non-`uuid`
values to choke on. Every policy in 020/021/022 uses this form; don't
reintroduce the `::uuid`-on-the-setting version even though it reads more
naturally.

## The first Super Admin is a deployment step, not a Liquibase seed

`POST /api/auth/register` can only ever create a buyer — deliberately, since
a self-registering Super Admin would be a serious hole. That means the
*first* Super Admin account can't come from the normal request path at all,
and the obvious alternative — a Liquibase changeset that inserts the user —
is wrong for a reason worth stating plainly so nobody reaches for it later:

**A seeded account needs a password, and a password in a changelog is a
password in git** — readable by anyone who clones the repo, and identical
across every environment that runs the same migrations, including
production. Hashing it first doesn't help: the hash is in git too, and a
known BCrypt hash of a known candidate password is a known password.
Credentials belong in the environment, never in version control — the same
rule `JWT_SECRET`/`APP_DB_PASSWORD` already follow.

`SuperAdminBootstrap` (`identity.internal.service`) is the alternative: an
`ApplicationRunner` that reads `landvault.bootstrap.super-admin.*`
(`BOOTSTRAP_SUPER_ADMIN*` in `.env`) and does nothing at all unless
explicitly enabled — defaulting `enabled` to `false` means an ordinary
startup has zero log noise, zero queries, zero risk. Runs after Liquibase
(`ApplicationRunner`s fire once the context is fully refreshed, and
Liquibase's own migration — a plain `InitializingBean` — completes as part
of that refresh, strictly before), so the seeded `super_admin` role always
exists by the time it looks for it — looked up by code, never created here;
a missing role means migrations didn't run, and silently creating one would
mask that.

**Idempotent by construction, not by a flag**: it checks whether *any* user
already holds the `super_admin` role (`UserRoleRepository.existsByRoleId`)
before creating one, and skips (logging at INFO, not silently) if so. This
is what makes leaving `BOOTSTRAP_SUPER_ADMIN=true` set after the first
successful run harmless rather than a standing way to mint a second admin
on every restart — deliberate, since whoever can set an environment
variable on a running system must not be able to grant themselves platform
staff this way.

**Half-configured is a startup failure, not a silent no-op**: enabled with
a blank email or password throws rather than returning quietly — a
bootstrap that appears to have worked but didn't is discovered at the worst
possible time (trying to log in, with no clue why), so it fails loudly at
the moment the misconfiguration actually exists instead.

User creation and the role assignment happen in one `@Transactional`
method — a user with no role would be a locked-out account with no obvious
cause, so it's both writes or neither.

**Never logged: the password, at any level, including debug.** Only the
created account's email is logged, and that log line was checked directly
against a real startup log (not just read from the source) before this was
considered done.

### The open TODO: `must_change_password` is not enforced yet

The bootstrap account is flagged `must_change_password = true` (a plain
boolean column, defaulted `false` for every other row) and that flag is
surfaced in the login response (`AuthUserResponse.mustChangePassword`) so
the frontend can route to a change-password screen — but **login itself is
not blocked on it**. There is no change-password endpoint yet to redirect
to or to clear the flag once used, so blocking login here would strand the
very account this slice exists to create. Whoever builds that endpoint
should also make login check this flag and force the redirect — until
then, a bootstrapped admin can go on using the bootstrap password
indefinitely, which is a real, open gap, not a closed one. Don't remove
this note once the endpoint exists without actually wiring the enforcement
first.

## Three tests that pin infrastructure assumptions, not application behavior

`RowLevelSecurityIT.appRoleCannotBypassRowLevelSecurity`,
`.policiedTablesHaveRowSecurityEnabledAndForced`, and
`.appRoleHasNoCreatePrivilegeOnPublicSchema` don't test anything this
codebase's own logic does — they pin facts about the database role and
schema that every *other* RLS test silently assumes are true and none of
them would catch if one stopped being true. Concretely: if the
`landvault_app` role were ever granted `SUPERUSER` (the realistic way this
happens — someone hits a permissions error in staging, escalates the role
to unblock themselves, and moves on, not an attack), every other test in
that class would keep passing, because a superuser satisfies every
`USING`/`WITH CHECK` clause trivially by never being subject to them.
Nothing would fail, nothing would log, nothing would look different — the
application would keep working perfectly and silently return every
tenant's data to every tenant. These three tests exist so that specific
failure mode is loud instead of invisible.

The role name is read from `spring.datasource.username` at test time
(`@Value`), never hardcoded — a hardcoded name would keep passing even if
the app were reconfigured to connect as something else entirely, defeating
the point. Confirmed these tests can actually fail, not just always pass,
by temporarily granting the escalation each one guards against (`ALTER
ROLE ... SUPERUSER`, `NO FORCE ROW LEVEL SECURITY`, `GRANT CREATE`)
directly in the test body, watching it go red, then reverting — a guard
test that cannot fail isn't a guard.

## Tenancy slice A: the first real client, and what it found

`GET /api/admin/tenants` and `GET /api/admin/tenants/{id}` are the first
endpoints anything outside this backend actually calls — the first genuine
exercise of JWT issue/parse, the tenant context filter, RLS's platform
bypass, the pagination envelope, and enum wire casing together, against the
frontend's real `tenantsService.ts` contract rather than an assumption
about it. It found real gaps. Record them here rather than let each get
silently "fixed" differently the next time someone touches this code.

**`TenantSummaryDto` is deliberately not the frontend's `Tenant` shape.**
`tenantsService.ts` types `fetchTenants()` as returning `Page<Tenant>` — the
same full shape the detail page uses, including a real `branches: TenantBranch[]`
array the directory then reduces client-side for a branch/estate count. This
backend returns a slimmer `TenantSummaryDto` with `branchCount: number`
instead, computed with one grouped query for the whole page
(`BranchRepository.countGroupedByOrganizationId`) rather than loading every
organization's branches to only count them — the N+1 this slice was
explicitly told to avoid. **This is a real, live contract mismatch**:
`TenantDirectory.tsx` today reads `t.branches.length` and
`t.branches.reduce((s,b) => s + b.estateCount, 0)`, which this endpoint's
response can't satisfy as-is. Reconciling it means either the frontend
gaining a distinct, slimmer list-row type (matching what a directory
actually needs) or this endpoint returning full branch arrays and eating
the N+1 — not fixed unilaterally here since it's a frontend/backend contract
decision, not a backend implementation detail.

**There is no stored "primary contact" anywhere in the schema.** Not on
`Organization`, not on any other tenancy table — no person's name, role
title, personal government ID, or personal phone is captured for a tenant
at all. `Organization` stores only company-level `companyEmail`/`companyPhone`.
`TenantSummaryDto.primaryContactEmail` uses `companyEmail` as the closest
real substitute; `primaryContactName` is always `null`; `TenantDetailDto.primaryContact`
(the frontend's full `PrimaryContact` — name/role/work email/phone/government
ID) is always `null` outright rather than a nested object filled with
nulls, per the "honest absence, never a plausible-looking placeholder" rule
above. Closing this needs either a schema change (out of scope for this
slice) or reusing an existing director as the primary contact by
convention — a real decision for whoever owns onboarding next, not made
here.

**`statesOfOperation` is derived, not stored** — there's no column or table
holding "every state this tenant operates in." The directory response uses
just `registeredState`/`operatingState` (no extra query per row); the
detail response also unions in every state the organization has a
regulator registration for (already loaded for the Regulatory section, so
free). Neither is the frontend's actual concept, just the closest
approximation from what's genuinely stored.

**`directorsAttestation` has no backing column either** — inferred as
"true once `verificationState` has progressed past `CREATED`", since Stage
2 submission (the only way it advances) always carries attestation on the
frontend's own `SubmitVerificationInput`. An inference from real state, not
a fabrication, but still worth knowing it isn't a stored fact.

**`additionalPermits` and `failedDocumentIds` are always empty/`null`** —
no table backs "additional permits" as distinct from a state regulator
entry, and `verification_decision_documents` (the join table that would
back `failedDocumentIds`) has no entity or repository yet — building one is
write-side work (`resubmitDocument`) explicitly out of scope for this
read-only slice. Left `null`/empty rather than fabricated, and rather than
building unused write-adjacent infrastructure just to populate a read.

**`reviewerName` (on a verification decision) and `managerName` (on a
branch) are always `null`.** Both entities store only a user id
(`reviewerUserId`/`managerUserId`); resolving a name means calling
`identity.IdentityApi`. `identity` already depends on `tenancy` (via
`TenancyApi`, for the tenant-context filter's branch switcher, see the
section above) — `tenancy` also depending on `identity` would be a genuine
module dependency **cycle**, and `ModularityTests` correctly rejects it:
this was tried, not assumed, and failed with exactly
`Cycle detected: Slice identity -> Slice tenancy -> Slice identity`.
Resolving user names cross-module needs a design that doesn't create a
back-edge — e.g. a read model in `common` that `identity` populates and
`tenancy` only reads, never a live call back into `identity` — not
attempted here.

**Enum-typed DTO fields are wire-value strings, not the internal enum
types** (`String plan`, not `TenantPlan`) — a public DTO must not expose an
internal type through its own signature (see the Modulith package-structure
section above); `UserDto.status` already established this pattern for
identity, this slice just follows it for every tenancy enum crossing the
`tenancy.dto` boundary.

**Director `idNumber` is masked to the last 4 characters** in every
response (list and detail) — never returned in full. A reveal action needs
its own endpoint with its own access logging (this file already requires
every read of it to be logged) — not built here; masking is the only
protection today. Verified with a real HTTP round trip asserting the raw
response body doesn't contain the seeded full value, not just that the
masked field looks right. (`bvn` was masked the same way when this slice
was built; it was removed entirely shortly after — see "BVN was removed"
below.)

**Estate count is always 0** — no inventory module exists yet to source a
real count from; matches the no-fabricated-data rule rather than inventing
a plausible number.

## BVN was removed — collected but never verified

`directors.bvn` (and its frontend counterpart) existed for one slice, then
was removed entirely — schema column dropped, entity/DTO fields deleted,
form input removed, masked-reveal UI deleted. Worth recording why, so
nobody re-adds it for the same reason it was added the first time (it
"seemed like it should be there").

**BVN verification is genuinely useful** — it confirms a person is who they
claim to be by checking the number against NIBSS records. **But nothing in
this system ever did that.** The number was collected as a plain string,
stored, masked on display, and never checked against anything at all. An
unverified BVN proves nothing: a director could type eleven arbitrary
digits and it would sit in the database looking official — it looks like
compliance evidence without being any.

Meanwhile it's among the most sensitive personal data in Nigeria, and
`directors` already carried four *unmet* obligations for it (see above):
encrypt at rest, restrict reads to compliance staff, log every read,
retention policy. So the position was **liability without benefit**: a
breach would leak directors' BVNs, and the platform gained nothing in
exchange, because the field was never actually used for anything.

`id_number` (NIN or international passport) stayed — it corresponds to the
identity document actually cross-checked against the CAC status report at
onboarding, so it does real work today.

**The condition under which BVN could legitimately return**: only
alongside a real NIBSS (or equivalent) verification integration — never
collected again as a bare, unverified string. And even then, store the
**verification result** (a boolean, a verified-at timestamp, a provider
reference), not the BVN itself. That gives the compliance evidence a
reviewer actually needs without the platform ever holding the number —
strictly less liability than what was just removed, not the same liability
reintroduced with an extra step attached.
