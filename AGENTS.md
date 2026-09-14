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
stores NDPR-regulated personal data (`directors.id_number`/`bvn`) that must
never land in a shared log.

## `directors` is the most sensitive table in the system

`directors.id_number` and `directors.bvn` are NDPR-regulated personal data,
stored in plaintext today with none of the following yet implemented —
each is a standing TODO carried in the table's own Postgres comment, not
just here: encrypt at rest; restrict reads to compliance staff (never all
platform staff, never another tenant's staff); log every read as an
auditable event, not an ordinary query; define and enforce a retention
policy. Whoever builds the repository/service layer for this table owns
closing these, not deferring them further.

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
