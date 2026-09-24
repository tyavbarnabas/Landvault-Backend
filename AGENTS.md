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

## Logging: Lombok's `@Slf4j`, not a hand-written `Logger` field

Every class that logs uses `@Slf4j` (generates the `log` field via
annotation), not `private static final Logger log = LoggerFactory.getLogger(X.class)`
by hand — consistent with how heavily this codebase already leans on Lombok
elsewhere (`@Getter`/`@Setter`/`@Builder`/`@RequiredArgsConstructor` on
nearly every class). The two classes that predate this convention
(`SuperAdminBootstrap`, `TenantScopeResolver`) were converted to match
rather than left as the odd ones out. Log the *fact* an action happened
(`"Tenant created: ... by actor {}"`) at INFO, never anything sensitive —
same rule as `SuperAdminBootstrap`'s own comment: a password (or a
temporary one, see `TenantStaffAccountListener`) is never logged, at any
level, including debug.

## `dev` profile and SQL logging

`show-sql`/`format_sql` live in `application-dev.yml`, not the base
`application.yml` — the base config stays quiet under every environment
where no profile is explicitly activated, including tests and any deploy
that forgets to set one. Local development is expected to run with
`SPRING_PROFILES_ACTIVE=dev`, not via a Spring-level
`spring.profiles.default` fallback baked into `application.yml` — that was
tried and reverted, because it makes `dev` (and SQL logging) active for
*anything* that doesn't explicitly set a profile, which is the opposite of
what "quiet by default" means once a real deploy exists. This matters beyond
noise: bind parameters get logged too, and this module now stores
NDPR-regulated personal data (`directors.id_number`) that must never land in
a shared log.

### `SPRING_PROFILES_ACTIVE` in `.env` does NOT activate the profile

This file previously claimed the `dev` profile comes from `.env` "loaded via
`springboot4-dotenv`". **That is false, and was verified false** — an earlier
version of this note asserted it without checking. Running the app with
`SPRING_PROFILES_ACTIVE=dev` present on line 1 of `.env` logs:

```
No active profile set, falling back to 1 default profile: "default"
```

The reason: dotenv contributes a *property source* to the Spring
`Environment`, but profile activation is resolved earlier, during config-data
processing, before that property source is consulted. Everything else in
`.env` still works fine (`DB_PORT`, `JWT_SECRET`, `BOOTSTRAP_SUPER_ADMIN*`,
…), because those are ordinary late-bound property lookups. Profile selection
is the one thing that needs the value *before* Boot reads any
`application-{profile}.yml`.

**The profile must come from the real environment**: an env var on the
IntelliJ run configuration, or `SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run`
from a shell. Both were confirmed working (`The following 1 profile is
active: "dev"`).

**What this silently broke for however long it was believed**: everything
`application-dev.yml` carries was simply never active locally — `show-sql`
and `format_sql` (so SQL logging was never on, despite the section above
describing it as a local default), and Swagger UI / `/v3/api-docs`, which the
base config disables and only `dev` re-enables. `SwaggerDevProfileIT` passes
regardless because it activates the profile explicitly, so no test ever
contradicted the claim. It surfaced only when the password-reset slice added
`spring.mail.*` to `application-dev.yml` and startup began failing outright
with "required a bean of type 'JavaMailSender'" — a missing profile finally
became loud instead of invisible. If a future slice puts something in
`application-dev.yml` and it "doesn't seem to apply", check the profile line
in the startup log first.

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
and `REJECTED` transition state. Built in `AdminTenantService.recordVerificationDecision`
(Tenancy slice B1) — `EnumJsonMappingTests`-style regression coverage for
this exact rule lives in `AdminTenantWriteIT.requestMoreInfoLeavesVerificationStateUnderReview`,
exercised over real HTTP, not just a unit test, since this is exactly the
kind of exception a generic "decision implies transition" implementation
gets wrong by default.

## A verification decision cascades to every document's status, not just `verificationState`

`recordVerificationDecision` doesn't stop at flipping `Organization.verificationState`
— found missing during a live manual walkthrough, not written from the task
spec (which only mentioned `failedDocumentIds` in the context of
`REJECTED`), then fixed once the gap was actually visible: a tenant
approved via real HTTP came back `verificationState: "verified"` while
every one of its documents still sat at `status: "pending"` forever, which
doesn't reflect reality — a verified tenant's evidence should read as
verified too. Matches the real frontend's own mock logic exactly
(`recordVerificationDecision` in `tenantsService.ts`):

- **`APPROVED`** → every `OrganizationDocument` on the tenant becomes
  `VERIFIED`.
- **`REJECTED`** → the documents named in `failedDocumentIds` become
  `REJECTED` (with the decision's `reason` copied onto each one's own
  `rejectionReason`); every *other* document on the tenant becomes
  `VERIFIED` — a rejection is a statement about specific evidence, not an
  indictment of the whole submission.
- **`REQUEST_MORE_INFO`** → no document status changes at all, same as
  `verificationState` — see the note above.

## Document resubmission doesn't itself advance verification state

`POST /api/admin/tenants/{id}/documents/{documentId}/resubmit` replaces one
`OrganizationDocument`'s metadata (`fileName`/`size`/`storageKey`) and resets
its `status` to `PENDING` — nothing more. It deliberately does **not** touch
the organization's `verificationState`: `submit-documents`/`begin-review`
still have to run again afterward to move the tenant back into the review
queue. Keeping the state machine's edges explicit (state only ever changes
inside `submitDocuments`/`beginReview`/`recordVerificationDecision`) means
there's no side door where re-uploading a file quietly re-triggers a review
transition nobody asked for.

## `TenantStatus.SUSPENDED` vs `VerificationState.SUSPENDED` — two different axes, same word

Both enums have a `SUSPENDED` constant, and they mean **completely
different things** — see "The two status axes on `Organization`" above for
the full status/verificationState split this sits inside. `TenantStatus.SUSPENDED`
means portal access is cut off (the tenant's staff can't log in / use the
console at all). `VerificationState.SUSPENDED` means marketplace
publishing/payment collection is paused while still `ACTIVE` on the portal
axis — e.g. a compliant tenant suspended for non-payment (Northbridge
Estates in the frontend's seed data) can still explore the portal and
manage estates; a portal-`SUSPENDED` tenant can't do anything regardless of
its verification state. **Never write a method that takes both a
`TenantStatus` and a `VerificationState` and reasons about them together as
if they were one concept** — that's the tell the two axes are being
collapsed into one, which this whole design exists to prevent. Tenancy
slice B1 touches `VerificationState` only; `TenantStatus` transitions
(suspend/reactivate/offboard) are slice B2's job, not this one's.

## `audit` is a cross-cutting module, not a shared/open one

`audit` is a real Spring Modulith application module (not `Type.OPEN` like
`common`) — it has actual behaviour (`AuditApiImpl`, a real table), not just
shared base types. It's cross-cutting for a structural reason: *every*
module will eventually need to write an audit entry (a tenant is created, a
verification decision is recorded, a support-access grant is used, a plan
changes), and none of those writes should ever touch `audit_log_entries`
directly — they call `AuditApi.record(AuditEntryRequest)`, the module's one
public method, same "define an `<Module>Api` interface, never expose the
entity/repository" rule every other module follows. `action` is a plain
string code (`"tenant.created"`, `"tenant.verification_approved"`, ...), not
a shared enum, specifically so a future module adding a new audit event
never has to modify a type `audit` owns.

`AuditApi.record(...)` is a **plain `@Transactional` method call**, not an
event — deliberately, unlike `TenantStaffAccountRequested` below. An audit
entry must roll back together with whatever it's recording (a tenant
creation that fails partway through must not leave an orphaned "tenant
created" audit row), so it has to run in the caller's own transaction, the
same way a direct method call always does. There's no cycle risk here to
justify an event: `audit` doesn't need anything back from `tenancy`/
`identity`/etc., so the dependency is one-directional and ordinary.

The read side (a `GET` audit-log endpoint, dashboard activity-stream wiring)
is explicitly out of scope for slice B1 — `AuditLogEntryRepository` exists
with no custom query methods yet, on purpose.

## Cross-module writes that would create a cycle: use a plain `@EventListener`, never `@ApplicationModuleListener`

`identity` already depends on `tenancy` (via `TenancyApi`, for the
tenant-context filter's branch switcher). So when `tenancy`'s tenant-creation
flow (Tenancy slice B1) needed to create an `identity`-owned `User` +
`UserRole` (the new tenant's first Executive Director account), a direct
`tenancy` → `identity` call would have closed that into a two-module cycle —
confirmed directly, not guessed: it was tried, and `ModularityTests` failed
with "Cycle detected: Slice identity -> Slice tenancy -> Slice identity."

The fix: `tenancy` publishes a plain Spring application event,
`TenantStaffAccountRequested` (a record living in `tenancy`'s own public
package, not `internal` — publishing a public event type is a normal part
of a module's public API, no different from a DTO), and `identity`'s
`TenantStaffAccountListener` consumes it with a **plain**
`org.springframework.context.event.@EventListener`, never Spring Modulith's
`@ApplicationModuleListener`. This distinction matters and is easy to get
backwards:

- Modulith's `@ApplicationModuleListener` defaults to **asynchronous,
  after-commit** execution, backed by the `event_publication` tracking
  table already in this schema (for reliable at-least-once delivery across
  a restart). That's the right tool when the two things genuinely don't
  need to happen atomically. It is the **wrong** tool here: "the
  organization, its first Executive Director user, and that user's role
  assignment all roll back together as one transaction" is a hard
  requirement of this slice, and async/after-commit delivery would mean the
  organization commits *before* the user is ever created, with no way to
  undo it if user creation then fails.
- A plain `@EventListener` runs **synchronously, on the same thread, inside
  the same transaction** as the `ApplicationEventPublisher.publishEvent(...)`
  call that raised it — behaviourally identical to a direct method call for
  transaction-propagation purposes. An exception thrown inside the listener
  propagates straight back out of `publishEvent(...)` and fails/rolls back
  the whole enclosing `@Transactional` method, exactly as if `tenancy` had
  called `identity` directly. `TenantStaffAccountListener`'s handler method
  is additionally marked `@Transactional(propagation = MANDATORY)` — not to
  start a transaction (`publishEvent` already runs inside one), but to fail
  loudly if it's ever invoked with no active transaction, rather than
  silently doing the wrong thing.

The dependency direction stays exactly what it already was: only `identity`
imports `TenantStaffAccountRequested` (the same direction `TenancyApi`
already goes), `tenancy` imports nothing from `identity` at all. This
pattern — a public event type owned by the module doing the writing,
consumed by a plain synchronous listener in the module that owns the target
entity — is the general answer whenever two Modulith application modules
would otherwise need to depend on each other in both directions for a
same-transaction write. Reach for it before reaching for
`@ApplicationModuleListener` if "must roll back together" is a requirement.

**A real bug this created, found and fixed after the fact**: `TenantStaffAccountListener`
originally saved the new `User` with no email-uniqueness check at all —
unlike `AuthService.register()`, which proactively checks
`existsByEmailIgnoreCase` before writing. The DB-level unique index
(`idx_users_email_lower`) still caught a collision, so no duplicate account
could ever actually get created — but the error the caller saw was wrong:
`AdminTenantController`'s only `DataIntegrityViolationException` handler at
the time unconditionally reported every conflict as `RC_NUMBER_ALREADY_REGISTERED`,
even when the real cause was the primary contact's email being taken by an
existing account. Fixed by: (1) the same proactive-check-plus-DB-backstop
pattern already used for `rc_number`, added to the listener; (2) a new
`common.DuplicateEmailException` — living in `common`, not `identity`,
specifically so `tenancy`'s exception handler (which cannot import anything
from `identity`) can still catch and correctly label it. Worth remembering
next time a listener/service writes to a uniquely-constrained column: a
`DataIntegrityViolationException` handler that reports one fixed message
for every possible constraint violation will mislabel every collision
except the one it was written for.

## The verification reviewer's identity comes from the authenticated caller, never the request body

`VerificationDecisionRequest` (the body of `POST /api/admin/tenants/{id}/verification-decision`)
deliberately has no `reviewerName`/`reviewerUserId` field, even though the
frontend's own `RecordDecisionInput.reviewerName: string` exists — that
field is a mock-mode-only convenience the frontend uses because there's no
real session in mock mode to derive an identity from. The real backend
already has one: `reviewerUserId` (on `VerificationDecision` and
`Organization.reviewerUserId`, set by `begin-review`) is always the
authenticated Super Admin's own id, resolved server-side from
`common.TenantContext.get().userId()` — the same "never client-supplied"
principle already applied to `tenant_id` (see "Permission slugs are
authorities" above). A client-supplied reviewer identity would let any
caller attribute a decision to someone else entirely; trusting the token
(or, here, the tenant-context scope `TenantContextFilter` already resolved
from it) is the only safe source. `AdminTenantController` reads this via
`TenantContext`/`TenantScope` from `common` specifically so it never has to
import anything from `identity` — see the event-listener note above for why
that matters.

## Support access is visibility, not authority

`support_access_grants` is time-boxed, reason-required, and fully logged —
and it must **never** be usable to move money or sign documents on a
tenant's behalf. It exists for troubleshooting a real issue, not as a
side-channel around the platform's normal authorization rules.

**Built in tenancy slice B2** (`POST`/`GET /api/admin/tenants/{id}/support-access`):
deliberately **a record, not a gate**. Creating a grant does not itself open
any door — a Super Admin already has `admin.tenants.manage` and the
platform-scope RLS bypass, which are what actually let them view a tenant's
data, independent of whether a grant row exists. This endpoint's only job is
producing the auditable record ("Ada viewed Estintin's data for this
reason, in this window") — there is no code anywhere that checks for an
active grant before allowing a read, and an expired grant blocks nothing.
Building that gate is deliberately **not** done here: it's a genuinely
larger design question (does it apply to every admin endpoint? how does it
interact with platform-scope RLS?) that belongs in its own slice, not a
quick addition riding on this one. `grantedToUserId` is always the
authenticated caller (`TenantContext`), never the request body — same
"never client-supplied" principle as everywhere else — and every grant's
audit entry is written with **`privileged = true`**, the one thing
`AuditLogEntry.privileged` exists for (see the `audit` module note above).

## `TenantStatus.OFFBOARDED` is terminal

Built in tenancy slice B2 (`POST /api/admin/tenants/{id}/status`).
`OFFBOARDED` is a one-way door: no transition away from it is ever allowed,
including back to `ACTIVE`. `SUSPENDED ↔ ACTIVE` moves freely in both
directions (suspend for non-payment, reactivate once resolved), but once a
tenant is offboarded, that endpoint refuses every further status change
outright, `AdminTenantService.changeStatus` checks this before even looking
at what status was requested. If the business genuinely needs to bring an
offboarded tenant back, that's a deliberate new-tenant decision (a fresh
`POST /api/admin/tenants`), not a status flip — reinstating the same
`Organization` row would blur "this company's relationship with the
platform ended" into something reversible, which defeats the point of
having a terminal state at all. Setting the same status a tenant already
has is rejected too (not silently accepted) — it usually means the caller
is acting on stale data.

## `TenancyApi` reads through SECURITY DEFINER functions, and must keep doing so

Both `TenancyApi` methods answer questions asked **before any tenant scope
exists**, so neither can read its table through an ordinary repository — RLS
fails closed and returns nothing:

- **`isTenantActive`** runs inside `AuthService.login()`/`refresh()`. Login is
  what *establishes* a scope, so it cannot presuppose one.
- **`branchBelongsToTenant`** runs inside `TenantContextFilter` while it is
  still *resolving* the scope, so `TenantContext` isn't populated yet.

Both therefore call `SECURITY DEFINER` functions (changeset 043) —
`landvault_tenant_is_active(uuid)` and
`landvault_branch_belongs_to_tenant(uuid, uuid)` — which run as the table
owner and so aren't subject to RLS. Each returns a **boolean, never a row**,
so they answer exactly the question the caller is entitled to ask without
reopening either table to unscoped reads. `EXECUTE` is revoked from `PUBLIC`
and granted only to the application role, and each function pins
`SET search_path = public, pg_temp` so a caller can't shadow `organizations`
with a temp table and have the definer's privileges applied to it.

**Do not "simplify" either back to a repository call.** It compiles, it passes
every superuser-connected integration test, and it breaks every tenant-staff
login in any environment where RLS is actually enforced.

### The bug this fixed, and why nothing caught it for so long

Shipped in tenancy slice B2 and only found when a human tried to log in as
tenant staff for the first time. Under RLS, `organizations` returned zero rows
to the unscoped lookup, `isTenantActive` returned false, and **every
tenant-staff login was refused as `TENANT_NOT_ACTIVE`** — the
session-revocation gate was blocking all tenant staff, not just suspended
ones. The branch half was quieter but equally broken: `branches` returned zero
rows, so the `X-Branch-Id` switcher silently never honoured a branch (it
ignores failures by design).

Verified against the app's own role rather than inferred:
`SET ROLE landvault_app; SELECT count(*) FROM organizations WHERE id = '<an
ACTIVE tenant>'` returns **0** with no GUCs set, and `branches` likewise;
setting `landvault.tenant_id` first makes both visible.

**Three independent reasons the suite couldn't see it**, which is the lesson
worth carrying:

1. Every login IT uses `@ServiceConnection` — the container superuser — so RLS
   is bypassed entirely.
2. The one IT that *does* use the restricted role only logs in as platform
   staff, whose `tenantId` is null, so the check never runs.
3. The only real tenant-staff accounts are created by
   `TenantStaffAccountListener` with deliberately unrecoverable passwords, so
   nobody had ever logged in as one.

`TenantStaffLoginUnderRlsIT` exists to close that hole: it wires the app to the
restricted role explicitly and logs in as tenant staff. **Do not convert it to
`@ServiceConnection`** — that would make it pass with or without the fix,
which is precisely how the bug survived. Proven to fail without the fix
(`expected: 200 OK but was: 403 FORBIDDEN`) before being considered done.

**The general rule this establishes**: any query on an RLS-policied table that
runs during authentication or scope resolution needs this treatment. When
adding one, ask first whether a scope exists yet at that point — and if the
answer is "no, this is what creates it", a repository call is the wrong tool.

## Closing the session-revocation gap: a login/refresh-time check, not an event

Tenancy slice B2 raised a real question: when a tenant is suspended, should
its staff's existing sessions be immediately cut off, or is the existing
15-minute access-token exposure (see "JWT carries every role assignment"
below) an acceptable trade-off here too? **Decision: closed, via a
login/refresh-time check — not left open, and not solved with a
revocation event.**

The mechanism the task suggested — `tenancy` publishing an event that
`identity` listens to and revokes refresh tokens on — was considered and
rejected as **insufficient on its own**: revoking refresh tokens stops a
suspended user from *renewing* their session, but doesn't stop them from
simply logging in again fresh immediately afterward, since nothing would
otherwise check tenant status at login either. The event alone would be
security theatre — it closes a door while leaving the window next to it
open.

The actual fix: `TenancyApi.isTenantActive(UUID tenantId)` (a new, minimal
public method — returns `boolean`, never the internal `TenantStatus` enum,
same "don't leak an internal type across the module boundary" rule as
everywhere else), called from `AuthService.login()` and `AuthService.refresh()`
whenever `user.getTenantId() != null` (tenant staff only — buyers and
platform staff have none). Both already do a fresh database read on every
call, so this adds no *new* per-request DB check the way the token's own
15-minute design deliberately avoids (see below) — it's one more read on a
path that was already hitting the database. A suspended/offboarded tenant's
staff can no longer log in, and cannot refresh past their current access
token's remaining lifetime — which closes the gap within the **same
15-minute window** this codebase already treats as an accepted trade-off
for role revocation, with no new cross-module event, no `identity.internal`
reached into from `tenancy` (the dependency direction was already
`identity → tenancy`, unchanged), and no separate token-family revocation
to keep correct. `AuthException.TenantNotActive` (403, `TENANT_NOT_ACTIVE`)
is deliberately generic — `identity` only ever gets a `boolean` back, so it
has nothing more specific to report than "not active."

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

## One `otp_codes` table, discriminated by purpose

Password reset's one-time codes live in a single `otp_codes` table carrying a
`purpose` discriminator, **not** a `password_reset_codes` table. Registration
verification is deliberately deferred but may be switched on later, and
sensitive-action confirmation is plausible after that — three near-identical
tables (each with its own hashing, expiry, attempt-limiting and rate-limiting
code) would be strictly worse than one. Turning on a new purpose should be a
configuration and code-path decision, not new infrastructure.

**`purpose` has exactly one legal value today** (`PASSWORD_RESET`), enforced by
a CHECK constraint. `REGISTRATION` is deliberately *not* pre-seeded as an
allowed value: a value with no code path behind it is the same kind of
fabrication as a permission slug nothing checks. Widen the CHECK in the slice
that actually builds the new purpose.

**`channel` is the opposite case, deliberately**: `EMAIL | SMS | WHATSAPP` are
all legal, though only `EMAIL` is ever produced today. That column exists so an
SMS/WhatsApp implementation needs no schema change — it records how a code was
actually delivered, which is a fact about the row, not a claim that a code path
exists.

**`destination` is captured at send time, never re-read from the user at verify
time.** If the user's email changes between requesting a code and using it, the
record must still say where the code actually went.

**`otp_codes` is not RLS-policied**, for exactly the same reason as
`refresh_tokens` and `users`: both reset endpoints are public and run *before*
any tenant scope exists, so the fail-closed default would return zero rows and
break the flow outright. Codes are reached only by `user_id`, never enumerated
across tenants.

### SHA-256, not BCrypt, for codes

`otp_codes.code_hash` is SHA-256 — deliberately *not* the `BCryptPasswordEncoder`
used for passwords. BCrypt's slowness exists to protect low-entropy, long-lived
secrets against offline cracking. A one-time code is neither: it lives ten
minutes and is attempt-limited to five guesses, so the brute-force protection
is the *limiter*, not the hash cost. BCrypt here would buy nothing and make
every verification needlessly slow. Same reasoning, same algorithm, as
`refresh_tokens.token_hash`.

Comparison is `MessageDigest.isEqual`, not `String.equals` — constant-time, so
verification timing never narrows the code.

### `consumedAt` marks every terminal state, not just successful use

A code stops being usable for three reasons, and they're deliberately recorded
differently:

- **used successfully** → `consumed_at` set
- **superseded by a resend** → `consumed_at` set (never two valid codes at once)
- **attempt limit exhausted** → `consumed_at` stays null; this is already
  visible as `attempt_count` reaching the limit

So no information is lost by the first two sharing a column — the three cases
stay distinguishable from the surrounding data.

## The neutral-response rule on password reset

`POST /api/auth/forgot-password` returns **the same status and the same body**
whether or not the address belongs to an account, and whether or not a code was
actually generated. Three separate branches return without generating anything
(unknown email, account over its rate limit, and the success path's own
early exits) and none of them is distinguishable from outside.

This is the same discipline as the timing-safe `login()`: a helpful "no account
found" hands an attacker a way to enumerate which addresses are registered.
`AuthException.InvalidOrExpiredResetCode` extends it to the *verify* side — one
exception and one message for "no code was requested", "expired", "already
used", "attempt limit exhausted", "wrong code" and "no such account", because
distinguishing them leaks both account existence and whether a reset is in
flight.

**Don't add a branch to `AuthController.forgotPassword` that varies the
response** — that would undo the whole point of the endpoint.

**One honest limitation, stated rather than papered over**: the *body* is
byte-identical (asserted in `PasswordResetIT`), but a real account still does
strictly more work (a DB write plus delivery) than an unknown address, which
leaves a theoretical timing side-channel. `login()` closes its equivalent with
a precomputed dummy hash; this endpoint does not attempt to equalise, because
doing so would mean performing fake writes. The exposure is far narrower than a
distinguishable response would be, and it is a known, accepted gap — not one
this slice claims to have closed.

## "Sessions revoked" on password reset means refresh tokens, not access tokens

A completed reset revokes **every one of the user's refresh tokens**, so no new
session can be minted. An **already-issued access token still rides out its
remaining lifetime** — up to the full 15 minutes.

This is the same accepted trade-off as every other permission change in this
system (see the JWT section above, and the tenant-suspension gate). It is
recorded here because a user resetting their password *specifically because
they think they're compromised* would reasonably assume the attacker is
ejected instantly, and they aren't. Never describe this as instant severance in
an API response, a comment, or UI copy. Closing it properly needs the same
denylist-or-shorter-expiry decision the JWT note describes, not a quiet patch.

## `noRollbackFor` on `resetPassword`, and why the mocked test can't catch it

`AuthService.resetPassword` is `@Transactional(noRollbackFor = AuthException.InvalidOrExpiredResetCode.class)`
and that is **load-bearing**, exactly as it is on `refresh()`. The wrong-code
branch increments `attempt_count` and *then* throws to fail the request;
Spring's default rollback-on-unchecked-exception would silently undo every
increment, leaving the counter permanently at zero and PR-4's brute-force
protection completely inert — a six-digit code with no working limiter.

This was verified by deliberately removing the annotation and watching
`PasswordResetIT.fiveWrongAttemptsInvalidateTheCodeEvenForTheCorrectOne` go red
with `expected: 5 but was: 0`, then restoring it — a guard that can't fail isn't
a guard. Note which test caught it: the **mocked unit test
(`AuthServicePasswordResetTest.aWrongCodeIncrementsTheAttemptCounter`) passed
either way**, because mocks don't roll back anything. Same lesson already
recorded for refresh-token theft detection: for anything that writes and then
throws, the integration test is not redundant with the unit tests.

## OTP delivery: MailDev + `JavaMailSender`, and the alternatives rejected

`OtpDeliveryService` has two implementations. **`EmailOtpDeliveryService` is
the default** — plain SMTP via Spring's `JavaMailSender`, pointed at **MailDev**
in development. `LoggingOtpDeliveryService` exists for tests and CI only.
Each obvious alternative was considered and rejected for a specific reason:

- **Why not just log the code to the console?** It proves the *flow* works but
  never exercises the *email*. SMTP connection handling, message construction,
  the subject line, the body — all of it stays untested until the day you point
  at a real provider and discover something is wrong. MailDev exercises the
  whole real path.
- **Why not a provider account (Brevo, SES, Gmail) from the start?** It makes
  the slice depend on a vendor signup, credentials in config, and a daily send
  limit, for a feature nobody outside the team is using yet. MailDev needs none
  of that: `docker compose up -d` and there's a working inbox at
  `http://localhost:1080`.
- **Why MailDev rather than MailHog?** Both are local fake SMTP servers with a
  web UI; MailDev is actively maintained, MailHog's development has largely
  stalled.
- **Why `JavaMailSender` rather than a provider SDK?** It's plain SMTP, already
  in Spring Boot, and vendor-agnostic. Moving from MailDev to Brevo, SES or a
  self-hosted server is a **host/port/credentials change per profile, not a code
  change**. A provider SDK would weld the sending code to one vendor.
- **What MailDev deliberately cannot do** is deliver to a real inbox. That is
  the point: test emails can never reach real people.

`EmailOtpDeliveryService` is therefore environment-agnostic by design — it never
knows which SMTP server it's talking to. Keep it that way; a provider-specific
branch inside it would defeat the entire arrangement. The message is plain text
(code, expiry, and an ignore-this-if-you-didn't-ask-for-it line); an HTML
template system is deliberately out of scope.

### Where the SMTP host is, and is not, configured

`spring.mail.*` is set **only in `application-dev.yml`** (MailDev at
`localhost:1025`, no auth, no TLS). The base `application.yml` deliberately
defines **no default host at all**. With no host, Boot creates no
`JavaMailSender` bean, so `EmailOtpDeliveryService` cannot be constructed and
the application **fails at startup** — the same fail-loud-on-missing-config rule
as `JWT_SECRET`. A deployment that forgets to configure mail must not boot
happily and silently send into a container that isn't there. An earlier draft
defaulted the host to an empty string; that was wrong, because Boot treats the
property as *present* and builds a sender with a blank host, turning a startup
failure into a confusing runtime one.

The `from` address is configuration too (`landvault.otp.from-address`), never
hardcoded in the sending code.

### `LoggingOtpDeliveryService` is for tests only — and that is a security boundary

Selected by `landvault.otp.delivery=log`, set once in
`src/test/resources/application.properties` so the whole suite gets it without
each test class opting in (that file layers *onto* the main `application.yml`
rather than replacing it — a different filename, so both load; this was
verified, not assumed). `PasswordResetIT` reads codes back out of that log line,
which is why the suite passes with **no MailDev container running**.

**It is the only place in this codebase permitted to log a code.** Every other
path — `EmailOtpDeliveryService` included — treats a code the way
`SuperAdminBootstrap` treats a password: never logged at any level including
debug, and never returned in an API response (`PasswordResetIT` asserts the
response body doesn't contain it). Selecting `log` in a real environment would
write live reset codes into the application log, where anyone with log access
could take over an account. That's why it is no longer the `matchIfMissing`
default: forgetting to configure delivery now yields email (or a loud startup
failure), never silent logging.

`landvault.otp.*` also carries `code-ttl` (10m), `max-attempts` (5),
`rate-limit-max-requests` (3) and `rate-limit-window` (15m). Every one is a
security parameter: the attempt limit is what makes a million-combination code
safe, and the rate limit is what stops the endpoint being a free way to flood
someone's inbox and run up delivery costs. Rate limiting counts codes
*generated* in the window, so superseded and failed ones still count — otherwise
requesting repeatedly would reset the limit each time.

## TOTP for 2FA, deliberately not the `otp_codes` mechanism

Password reset built an OTP mechanism that *sends a code over a channel*. 2FA
does **not** reuse it, and the two must not be merged:

- **No delivery cost or dependency.** TOTP codes are computed independently on
  both sides from a shared secret and the current 30-second window. Nothing
  travels over the network — no provider, no per-message cost, no delivery
  failure mode.
- **Immune to SIM swap.** SMS 2FA is defeated by porting a phone number, a
  real and common attack in this market. TOTP isn't.
- **Works offline**, which matters for a diaspora user on poor connectivity.

`otp_codes` remains correct for password reset, where the point is proving
control of a *contact channel*. TOTP proves possession of a *device*.
Different guarantees, different mechanisms.

**The algorithm is a library, not hand-rolled** — `dev.samstevens.totp`
covers secret generation, the `otpauth://` URI, verification and drift.
Implementing RFC 6238 by hand is an unnecessary source of subtle bugs.
`TotpService` pins the parameters every authenticator app assumes (SHA-1, 6
digits, 30s); changing one silently breaks pairing for apps that ignore the
URI's parameters.

### Setup and confirmation are separate states, and that is not optional

`POST /2fa/setup` issues a secret and leaves `two_fa_enabled` **false**.
`POST /2fa/confirm` verifies a real code and only then sets
`two_fa_enabled = true`, `two_fa_confirmed_at = now`, and issues recovery
codes.

`two_fa_confirmed_at` exists as its own column precisely so "a secret has been
issued" and "the pairing is proven" are distinguishable. Enabling 2FA at setup
time, before the user's app demonstrably holds the secret, **permanently locks
them out of their own account** if the pairing silently failed — a mis-scanned
QR, a crashed app — because recovery codes are only issued at confirmation, so
there is nothing to recover with. It is the single worst outcome this feature
can produce. Every read that asks "is 2FA on?" checks **both** fields.

### Tokens are never issued before the second factor verifies

With 2FA confirmed, `POST /api/auth/login` returns a
`TwoFactorChallengeResponse` — no access token, no refresh token, no user
object — and `POST /api/auth/2fa/verify` exchanges that challenge plus a TOTP
or recovery code for the real `AuthResponse`. The challenge shares no field
name with `AuthResponse`, so a client cannot mistake one for the other.
**Accounts without 2FA are completely unaffected**; that path is unchanged.

The challenge lives in `two_fa_challenges` rather than being a self-contained
signed token. This is a **deliberate third schema change beyond the slice's
stated two**: the challenge must be single-use, and single use cannot be
enforced by a signed token, which stays valid and replayable until it expires.
Storing it gives a `consumed_at` to set, exactly like `refresh_tokens`.

### Recovery codes are the part that must not be skipped

8–10 single-use codes, generated at confirmation, stored hashed, returned in
plaintext **exactly once** and never retrievable again. Without them TOTP is a
one-way door: a lost or replaced phone means an account only manual database
intervention can reach. This is the most commonly omitted part of a TOTP
implementation and the one that generates the most support burden when it's
missing.

They're 64 bits of randomness, not six digits — unlike a TOTP code they never
expire, so they must survive being guessable over a long period. Input is
normalised (case, grouping dashes) because users retype them by hand, and
formatting shouldn't decide whether someone gets back into their account.
Regeneration requires a **TOTP code specifically**, never a recovery code —
otherwise one stale code could mint a whole fresh set. Using a recovery code
writes its own audit entry: it means the user lost device access, which is
worth a record.

### Disabling requires a code, and platform staff cannot disable at all

`POST /2fa/disable` needs a valid TOTP or recovery code. **A session alone is
deliberately not enough** — if a hijacked session could strip 2FA, the
protection is defeated by the exact attack it exists to prevent.

2FA is **mandatory for platform staff** (`super_admin`, `platform_moderator`,
`compliance_officer`), who hold the platform-scope RLS bypass — the most
sensitive credential in the system. Disable returns a specific
`TWO_FACTOR_MANDATORY` rather than a generic 403, so the response can say why.
That check runs *before* the code check, so staff aren't invited to keep
guessing at a door that never opens.

**Login is not blocked on it**, though. A platform-staff account without
confirmed 2FA authenticates normally and gets `mustSetUpTwoFa: true` in the
login response for the frontend to route on. Blocking would strand the
bootstrapped Super Admin, who cannot set 2FA up without first signing in.

**The bootstrapped Super Admin's intended first-login order is: change
password, then set up 2FA, then normal access.** Both `mustChangePassword` and
`mustSetUpTwoFa` can be true at once, and neither blocks login, precisely so
that state is always escapable. Do not "harden" either into a login block
without first making sure the other can still be completed — requiring both
while neither can be satisfied is an unrecoverable account.

### Encryption at rest, and the rotation gap

`users.two_fa_secret` is encrypted by `TwoFaSecretConverter` (AES-256-GCM,
random IV per value, `base64(iv || ciphertext)`). A readable secret would let
anyone with database access mint valid second factors for any account —
strictly worse than the `directors.id_number` exposure, since it yields
account access rather than personal data.

A converter rather than encrypt/decrypt calls in the service layer, for the
same reason `@SQLRestriction` lives on the entity: it makes writing plaintext
structurally impossible rather than merely discouraged. GCM is authenticated,
so a tampered value fails loudly instead of decrypting to garbage.

`TOTP_ENCRYPTION_KEY` has **no default** and must decode to exactly 32 bytes;
a missing, malformed or wrong-sized key fails startup, same rule as
`JWT_SECRET`.

**TODO — key rotation is not implemented.** A single configured symmetric key
is the accepted scope. There is no key id on stored values, so two keys cannot
coexist; re-keying today means decrypting every secret with the old key and
re-encrypting offline. Changing the key without that migration makes every
existing secret undecryptable and forces every user to set 2FA up again.

### Throttling, and the `noRollbackFor` trap for the third time

Failed second factors are counted on the user (`two_fa_failed_attempts`), and
five failures set `two_fa_locked_until`. The lockout is **time-based, not
permanent**: a TOTP user cannot request a fresh code the way a password-reset
user can, so a permanent lock would strand them.

Every method in `TwoFactorService` that increments the counter carries
`noRollbackFor` — same trap as `resetPassword` and `refresh`, now in a third
place. Verified the same way: removing it from `verify()` turned
`TwoFactorIT.fiveFailedVerificationsLockOutEvenACorrectCode` red with
`expected: 1L but was: 0L`, meaning the lockout never persisted and the
limiter was permanently inert.

### `SecurityConfig` no longer wildcards `/api/auth/**`

Public auth routes are now listed one at a time. The wildcard was correct
while every auth route was public, but `/api/auth/2fa/setup|confirm|disable|
recovery-codes/regenerate` require an authenticated session — under
`/api/auth/**` they would have been reachable by anyone, letting a stranger
start or turn off 2FA on someone else's account. Only `/2fa/verify` stays
public, because it completes a login and its caller holds no token yet.
Adding a new public auth route means adding it to that list; forgetting makes
it require authentication, which is the safe direction to fail.

## Boundaries arrive as GeoJSON, and the wire is `[lng, lat]`

`POST /api/portal/estates` takes a GeoJSON `Polygon` in the request body —
not a file upload, not a shapefile. It's what surveyors' tools export, PostGIS
parses it natively, and the frontend already models a boundary as a coordinate
ring. File upload (`.geojson`, shapefile, CAD) is a follow-up, not built.

**The API speaks GeoJSON order — `[longitude, latitude]` — and PostGIS stores
SRID 4326. The frontend converts for Leaflet; the backend never does.**
Leaflet uses `[latitude, longitude]`, the other way round, and getting it
backwards produces no error at all: just a structurally valid polygon in the
wrong place.

`GeoJsonPolygonParser` rejects: a non-`Polygon` type, an unclosed ring, fewer
than four positions, and coordinates outside Nigeria's box (longitude 2–15,
latitude 4–14).

### The bounds check does NOT catch every swap — know what it actually buys

Nigeria's longitude range (2–15) and latitude range (4–14) **overlap across
4–14**, so any interior point whose coordinates both sit in that band is
swap-ambiguous. Verified case by case rather than assumed:

| City | Original `[lng, lat]` | Transposed | Caught? |
|---|---|---|---|
| Lagos | `3.4, 6.5` | `6.5, 3.4` | yes — latitude 3.4 is offshore |
| Abuja | `7.4, 9.05` | `9.05, 7.4` | **no** — lands in Taraba |
| Kano | `8.5, 12.0` | `12.0, 8.5` | **no** |
| Port Harcourt | `7.0, 4.8` | `4.8, 7.0` | **no** |

No bounds check of any shape can reject transposed Abuja, because the result
is genuinely inside the country. So the guard catches out-of-country
boundaries and coastal/western swaps, and that is all it can catch. The real
protections are the documented wire convention and the frontend owning the
Leaflet conversion.

**The follow-up is two changes, not one.** A per-state bounding-box check
would close most of the gap — FCT's box is small enough that transposed Abuja
falls outside it — but it can only work if every estate actually carries a
state. A live walkthrough created an estate with a transposed Abuja boundary
and `state: null`, which is precisely the case a per-state check would have
been unable to help with.

So: **`state` is now required on estate creation** (`@NotBlank` on
`CreateEstateRequest`), which is step one. Step two, still to build, is
validating the boundary against that state's bounding box.

Two things whoever builds step two should settle first:

- **`state` is free text today.** "FCT", "Federal Capital Territory" and
  "Abuja" would all be accepted, and a bbox lookup needs one canonical form.
  The frontend already has the authoritative list in
  `~/landvault/src/data/nigerianStates.ts` — constrain against that rather
  than inventing a second list.
- The column itself is still nullable in the database, deliberately: existing
  rows predate the requirement, and a `NOT NULL` migration would need them
  backfilled first. The API is the enforcement point for now.

`PortalEstateCreationIT.swappedCoordinatesAreOnlyCaughtWhenTheyLeaveTheCountryBox`
pins the current limitation, and `anEstateWithoutAStateIsRejected` pins the
precondition, so neither gets quietly undone.

## `actual_area_sqm` is computed on write, and must be recomputed on edit

When a plot is created with a footprint, its surveyed area is computed as
`ST_Area(footprint::geography)` — square metres — and stored. It is **never
accepted from the request**.

Computed on write rather than per read: simpler, and it matches the column
that already exists. **The consequence is that editing a footprint must
recompute it**, or the stored area silently describes the old boundary.
Nothing edits footprints yet; whoever builds that owns this.

No footprint means `actual_area_sqm` stays **null**, never the nominal figure.

`GeometryCalculator` runs both this and the plot-within-estate containment
check through the shared `EntityManager`, so they participate in the caller's
transaction — the same reason `MeController` reads session variables that way
rather than through a `JdbcTemplate` on a different connection.

## A plot's nominal size comes from its tier, never the request

`plots.nominal_size_sqm` is copied from the plot's `PriceTier` at creation.
Accepting it from the request would let a caller supply a size contradicting
the tier the plot is priced by, leaving the invoice and the deed disagreeing.

**The `UNIT_TYPE` decision**: `nominal_size_sqm` was `NOT NULL`, which a
built-unit tier cannot satisfy — a `UNIT_TYPE` tier has no size of its own.
It is now **nullable**, because for a 4th-floor apartment there genuinely is
no exclusive land area, only a share; writing a zero would fabricate a figure
and requiring the caller to supply one forces them to invent it.

A terrace on its own plot *does* have a real land area, so
`nominalSizeSqmOverride` is accepted — **only for `UNIT_TYPE` tiers**, and
ignored entirely for `LAND_SIZE` ones, where the tier is authoritative.

The "a `LAND_SIZE` plot must have a size" half cannot be a CHECK constraint:
it depends on the referenced tier's type, which a row-level constraint cannot
see. The service enforces it instead.

## Estate creation: tenant from context, plot containment, publication

- **`tenantId` is never a request field.** It comes from `TenantContext`.
  `branchId` is taken from the caller's scope when they have one, and when
  they're organization-wide it must be supplied and is checked against their
  own tenant via `TenancyApi.branchBelongsToTenant` — never trusted from the
  request alone.
- **`published` starts false, always.** Publication is a separate deliberate
  action, never a creation-time flag, and remains one of the four conditions
  in the marketplace gate above.
- **A plot's boundary must sit within its estate's** (`ST_Within`), when both
  exist. A plot outside its own estate is a data error worth catching at the
  door. This is *not* plot-against-plot overlap — that's conflict detection,
  which needs its own design.
- **`portal.estates.manage`** is the first `portal.*` permission in the
  schema, granted to `executive_director` and `surveyor_project_manager`
  (whose seeded description is literally spatial inventory). Sales sells what
  exists, finance reconciles it, legal papers it — none define the inventory.
  `branch_manager` was the arguable exclusion, left out on the same
  narrower-is-reversible reasoning as `platform_moderator` and the audit log.

## Built property and rentals: the hierarchy extends, it does not fork

The catalogue must eventually carry developed properties (houses, apartments)
and rentals. Three columns make that *possible* without building either —
`plots.property_type`, `plots.listing_intent`, `price_tiers.tier_type`. There
is deliberately **no `units` table**, no bedroom counts, no service charges,
no tenancy agreements and no construction status: those need their shape
understood before they are designed.

**Land is always the base. A building sits on a plot.**

```
Estate → Block → Plot → Unit (0..n)
```

Bare land is a plot with no units; a house is a plot with one; an apartment
block is a plot with many. So `property_type = BUILT` means "this plot carries
units", never "this is a different kind of thing".

Two consequences, and they are what keep slice 1's work valid:

- **Geometry stays at plot level.** Two flats in the same building do not
  overlap spatially, so conflict detection must never try to reason about
  them. A double-sold apartment is caught by **unit identity**, not by
  `ST_Intersects`. Plot-level detection keeps working exactly as designed.
- **A rental is not a different property — it is a different *transaction*.**
  What differs is recurring rent, a tenancy agreement, a deposit, renewals,
  and ownership never transferring. That divergence belongs to `sales`,
  `finance` and `documents`. Estates, blocks, plots and tiers are the same
  rows either way, which is why `listing_intent` is the *only* column rentals
  need here. Don't build a parallel rental model in `inventory`.

### The renting party is a `Lessee`, never a "tenant"

**"Tenant" already means *organization*** throughout this codebase —
`tenant_id`, `TenantStatus`, `TenancyApi`, the entire isolation model. A
renter is also colloquially a tenant, and that collision would cause real
confusion the moment rentals are built.

**Decision: the renting party is a `Lessee`.** Chosen over `Renter` because it
has a natural counterpart (`Lessor`) for the owning side, and over `Occupant`
because an occupant isn't necessarily the contracting party — family members
occupy without being party to the agreement. It is also the term Nigerian
property law already uses.

No such entity exists yet; the point is that the vocabulary is settled before
someone introduces one. **Never introduce a class, column or enum value that
uses "tenant" to mean a person renting a property.**

### `PlotIntent` and `ListingIntent` are different axes

- **`PlotIntent`** (`DEVELOPMENT` | `INVESTMENT`) — what the **buyer** means
  to do with the land: build on it, or hold it.
- **`ListingIntent`** (`FOR_SALE` | `FOR_RENT` | `BOTH`) — what the **seller**
  is offering.

Both legitimate, and orthogonal: a plot can be `FOR_SALE` with
`PlotIntent.INVESTMENT`. Each enum's Javadoc points at the other, because
otherwise one will eventually be folded into the other by someone tidying up.

### A tier is not always a square-metre band

`price_tiers.tier_type` is `LAND_SIZE` or `UNIT_TYPE`. A land tier is a size
band ("250 sqm at ₦4.2M"); a built-unit tier is a product ("3-bedroom terrace
at ₦85M") — the same concept, a priced category a plot belongs to, but the
discriminator isn't square metres. For a `UNIT_TYPE` tier, `size_sqm` is null
and the existing `label` carries the meaning; no extra column was needed.

`size_sqm` was `NOT NULL`, which structurally forbade built-unit tiers, so it
is now nullable — guarded by
`CHECK (tier_type <> 'LAND_SIZE' OR size_sqm IS NOT NULL)`, since a land tier
without a size is meaningless. A `UNIT_TYPE` tier is permitted but not
required to carry one.

**`price` stays the developer's own figure per tier**, never derived from a
per-sqm rate — the rule holds more strongly here, since a per-sqm rate isn't
even definable for a built unit.

**Operational caveat on rolling back changeset 040**: restoring `NOT NULL` on
`size_sqm` only succeeds while no null-size tier exists. Once the capability
is actually used, rolling back means dropping those rows first. That is
inherent to the migration, not a defect — verified directly, and the reason
the nullability change is its own changeset rather than bundled with the
column add.

### Completed units before off-plan

When built property is eventually built, **do completed units first**.
Off-plan drags milestone payments, construction tracking and arguably escrow
into the critical path — a much larger surface than simply listing a finished
house. Recorded here so the sequencing isn't relitigated.

## Inventory: nominal vs surveyed area, and why both exist

`plots` carries **two** area columns, deliberately:

- **`nominal_size_sqm`** — what the plot is *sold and priced as*. Comes from
  its tier; it is the number on the deed and the invoice.
- **`actual_area_sqm`** — what the *survey* says, derived from `footprint` via
  `ST_Area(footprint::geography)`.

A plot sold as "250 sqm" may survey at 248.6. **Price off the nominal tier;
display the surveyed area separately.** Storing only one loses either the
commercial truth or the physical truth, and there is no safe way to recover
the missing one later.

`actual_area_sqm` is nullable and **must never default to the nominal value**.
A plot with no footprint has no surveyed area; copying the nominal figure
across would manufacture a survey result nobody produced — the same
fabrication rule that governs metrics elsewhere in this codebase.

## Corner premium is a modifier, tier prices are never per-sqm

**`estates.corner_premium_pct` is a modifier, not a tier.** A corner plot
costs `tierPrice × (1 + cornerPremiumPct / 100)`, computed. No corner price is
stored anywhere, so the two figures can never drift apart. Corner plots are
deliberately absent from the tier list — a corner is a per-plot modifier, not
a menu item.

**`price_tiers.price` is the developer's own price for that band, never
derived from a single per-sqm rate.** Larger plots are routinely discounted
per square metre — 180 sqm at ₦18,000/sqm while 600 sqm sells at ₦14,500/sqm.
A per-sqm figure is a *displayed comparison* computed for the UI so a buyer
can weigh a 250 against a 600 honestly; it is never an input to pricing.
Deriving price from a rate would quietly overcharge every large plot.

Money and areas are `numeric` / `BigDecimal` throughout — never floating
point.

## Title is per-estate; `organization_documents` is the other half of that rule

`estate_titles` holds the land title instrument (C of O, R of O, Governor's
Consent, Gazette), one row per estate. A developer can hold clean title on one
estate and none at all on the next, so this **cannot** live on
`Organization` — and `organization_documents` already carries the mirror of
the rule, deliberately holding corporate verification documents (CAC, TIN,
SCUML) and no land title. The two halves are meant to be read together;
changing one without the other reopens a question that is already settled.

## `verification_source` is what makes a check worth more than a boolean

`estate_verification_checks` records AGIS registration, encroachment status
and title verification. Two columns carry the weight:

- **`status`** — `NOT_CHECKED` is the default, and **it must never render as
  positive**. The absence of a check is not a clean bill of health; the
  frontend removed hardcoded "No encroachment notices on file" claims for
  exactly this reason. A row that does not exist means nobody has looked.
- **`verification_source`** — a green badge produced by a real registry API
  call and a green badge produced by a human eyeballing a PDF are different
  claims, and a buyer deciding whether to part with money deserves to know
  which one they are looking at. If this collapsed into `verified: true`, the
  badge would mean nothing. It is an enum rather than free text specifically
  so `MANUAL_REVIEW` can never be spelled two ways — a typo would silently
  split the one distinction the column exists to make.

## The marketplace publication gate: four conditions, recorded here, enforced there

An estate is publicly listable only when **all five** hold:

1. `estates.published` is true (the developer's own opt-in switch)
2. the owning tenant's `verificationState` is `VERIFIED`
3. that tenant holds the `marketplacePublishing` entitlement
4. the tenant's `status` is **`ACTIVE`** — corrected from "not
   `SUSPENDED`", which would have left an **`OFFBOARDED`** company's land
   for sale on a platform it has left
5. **no conflict blocks it** — `ConflictDetectionApi.publicationCheckFor(estateId)`
   returns `blocked == false` (added by inventory slice 4; see the conflict
   detection sections below for why HIGH blocks and MEDIUM only warns)

**Built in the publication slice** as `marketplace_estate_eligibility`
(changeset 049) — see "The public marketplace" below.

**The gate belongs to the `marketplace` projection, not to the inventory
schema** — which is why `estates.published` is only condition 1 and carries a
Postgres column comment saying so. A developer can pull one listing without
touching tenant status, and a tenant can lose the right to publish without any
estate changing. Whoever builds the projection implements all four; don't
half-implement it as "published = true".

## Inventory's geometry: where the spatial conventions finally bite

`estates.footprint` and `plots.footprint` are the first real geometry in this
schema — `geometry(Polygon,4326)`, both GiST-indexed, mapped to JTS `Polygon`.
The AGENTS.md spatial conventions above stop being theoretical here, and the
failure mode is the dangerous kind: `ST_Area` on raw 4326 returns square
*degrees*, which is not an error, just a plausible-looking wrong number.

**Plot-level geometry is the point, not a nicety.** The within-estate double
allocation — the same plot sold twice inside one estate — is the more common
scam and has been undetectable precisely *because* plots had no geometry.
That column is what makes real `ST_Intersects` detection possible instead of
the bounding-box heuristic this file forbids.

One Postgres subtlety worth keeping: `plots`'s uniqueness is
`UNIQUE NULLS NOT DISTINCT (estate_id, block_id, plot_number)`. `block_id` is
nullable (not every estate uses blocks), and Postgres's default treats every
NULL as distinct — so a plain unique constraint would happily allow two
"Plot 4" rows in the same block-less estate, which is exactly the duplicate
the constraint exists to prevent. Requires Postgres 15+; the project runs 16.

## The audit log is readable, and read-only by design

`GET /api/admin/audit-log` is the audit module's only read surface, gated on
**`admin.audit.view`**. An audit log nobody can read is a compliance artefact
that satisfies nothing — the append-only invariant exists so someone can later
*prove what happened*, which requires the entries to be retrievable and
filterable.

**There is no write, update, delete or archive route, and there must never be
one.** `AuditLogEntry` extends `AbstractAppendOnlyEntity` (no `updatedAt`, no
`deleted`) so the schema already makes revision impossible; the API surface
must not reintroduce what the schema deliberately omits.
`AuditLogEntryRepository` is read-and-append only for the same reason.

**`privileged` is carried through verbatim and is filterable.** Support-access
grants are flagged `privileged = true` precisely so a reviewer can see when a
platform operator looked into a tenant's data and why. Isolating those entries
is the single most likely reason someone opens this screen deliberately; if
the read path flattened the flag, it would serve no purpose.

### Who holds `admin.audit.view`, and why not everyone

- **`super_admin`** — obviously.
- **`compliance_officer`** — reviewing who did what *is* the role. An officer
  who can't read the audit trail can't do the job.
- **`platform_moderator` — deliberately not granted.** Moderation is about
  marketplace content; the audit log is far wider, exposing every platform
  operator's actions across every tenant, including the `privileged` entries
  that reveal when the platform is investigating a tenant. That's compliance
  reach, not moderation reach. The narrower grant is also the reversible one:
  widening later is a one-line changeset, un-leaking an investigation is not.

The slug follows the frontend's lowercase dotted convention exactly — the nav
renders directly off these strings, so a renamed slug silently removes a menu
item.

### Actor names: a third module cycle, solved by inverting the dependency

The read path resolves `actorUserId` to a display name, because raw UUIDs are
unreadable in an activity stream. The obvious implementation — `audit` calling
`IdentityApi` — is **impossible**: `identity` already depends on `audit` (both
`AuthService` and `TwoFactorService` record entries), so the reverse edge is a
cycle. Confirmed rather than assumed, the same way the earlier two were:
adding `IdentityApi` to `AuditApiImpl` failed `ModularityTests` with
`Cycle detected: Slice audit -> ...`.

The fix is **dependency inversion**, not a new module and not a read model:
`audit` declares the `ActorNameResolver` interface in its own public package,
and `identity` implements it (`IdentityActorNameResolver`). Every arrow keeps
pointing the way it already did — `identity → audit`, never back.

This is now the **third** distinct cross-module-naming collision in this
codebase (after `tenancy`'s `reviewerName`/`managerName`, and `tenancy`'s
tenant-staff account creation). Worth recognising the shape early: when module
A needs a label owned by module B and B already depends on A, inverting the
interface is cheaper than either a read model or a new composition module.
Note this does **not** retroactively fix `reviewerName`/`managerName` in
tenancy — those remain null, and the same technique would work there if
someone wants them.

Resolution is **batched** by construction: the interface takes a collection,
so a page resolves its actors in one `findAllById`, never one query per row.
A missing actor renders as `"Unknown user"` rather than blank or a crash —
audit entries outlive the accounts that created them, by design, so that is an
ordinary case.

`targetId` is deliberately **not** resolved to a name. Targets span
organizations, users and documents across several modules, so doing it
properly needs a lookup strategy per type; the frontend gets `targetType` and
`targetId` and can link. A possible follow-up, not a gap being hidden.

### Platform scope only — and the TODO that comes with it

A Super Admin sees every entry across all tenants, via the existing
platform-scope RLS bypass. **`audit_log_entries` carries no RLS policy**,
which is fine while only platform staff can read it.

**TODO:** it becomes a real gap the moment tenant staff need their own
organization's trail through `/api/portal/*`. That view needs *both* a policy
on this table *and* a decision about what a tenant may see — their own entries
certainly, but almost certainly **not** `privileged` support-access entries,
which would let a tenant watch the platform investigating them. Don't build
the tenant-facing view without settling that second question first.

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

## Tenancy slice B2: two more deliberate divergences from `tenantsService.ts`

Same spirit as slice B1's `submit-documents`-vs-`submit-verification` gap —
the task's own spec for these two endpoints diverges from what the real
frontend actually sends, on purpose, not by accident. Following the task
spec here (not silently reconciling toward the frontend) since both are
specific enough to read as deliberate:

- **`POST .../status`**: this slice requires a `reason` field (required for
  `SUSPENDED`/`OFFBOARDED`). The real frontend's `setTenantStatus` sends
  only `{ status }` — no reason at all. The endpoint still accepts and
  requires one; the real frontend would need a wiring change (adding a
  reason prompt to that action) to actually satisfy this backend as built.
- **`PUT .../plan`**: this slice's `TenantPlanUpdateRequest` is flat —
  `{ plan, marketplacePublishing, mlmModule, fxRails }`. The real frontend's
  `updateTenantPlan` sends a nested shape instead —
  `{ plan, entitlements: { marketplacePublishing, mlmModule, fxRails } }`
  (reusing `TenantEntitlements`). Same story: the endpoint works exactly as
  the task specified, but the real frontend's current call wouldn't
  deserialize into it as-is.

Neither gap blocks anything in this slice's own tests (which call the
endpoints exactly as specified) — they're recorded here so a future
frontend-integration pass knows exactly what to reconcile, the same reason
slice B1's divergences were written down rather than fixed silently in
either direction.

## Inventory reads: RLS was missing, and the slice spec assumed otherwise

Inventory slice 3 (`GET /api/portal/estates` and friends) was specified on
the premise that "tenant and branch scoping is automatic — RLS already
filters by `tenant_id`, do not re-filter in the repository."

**That premise was false, and was checked rather than trusted.** Before a
line of read code was written, `pg_class.relrowsecurity` showed `false` for
all seven inventory tables, and `SET ROLE landvault_app; SELECT count(*)
FROM estates` returned rows with no tenant context set at all. Slices 1 and
2 had both listed RLS as out of scope, which was harmless while nothing
could read these rows back — a write always stamps `tenant_id` from
`TenantContext`, so it can't land in the wrong tenant. **Reads are where
the absence becomes a breach**: following the spec literally would have
shipped an endpoint where any authenticated tenant user lists every
tenant's estates.

Changeset **044** closes it: the same two-permissive-policy shape as 020
(platform-scope policy plus tenant/branch policy) on `estates`,
`estate_amenities`, `blocks`, `price_tiers`, `plots`, `estate_titles`,
`estate_verification_checks`. Same `tenant_id::text = current_setting(...)`
form, never `current_setting(...)::uuid` — see 020's note for why.

**The branch clause is load-bearing here in a way it isn't on the corporate
tables.** Every inventory entity populates `branch_id` (estates from the
caller's scope, everything beneath from its estate), so unlike
`organization_documents` et al. — where `branch_id` is usually null and the
clause passes trivially — this is what actually walls a branch manager to
their own branch. That wall is now pinned by
`PortalEstateReadUnderRlsIT.aBranchManagerSeesOnlyTheirOwnBranchesEstates`,
and **proven to fail red**: temporarily granting `landvault_app` `BYPASSRLS`
turns it red, confirming the database is what enforces it and not something
in the repository layer.

`EstateSpecifications`/`PlotSpecifications` therefore carry **no tenant or
branch predicate at all**, deliberately. A second, weaker copy of the
isolation guarantee in application code is how the two eventually disagree,
and the weaker one is the bug nobody looks for.

### The testing blind spot this also revealed

`PortalEstateCreationIT` uses `@ServiceConnection` — a superuser connection,
where RLS is inert. So slice 2 proved the write path works, but never that
it satisfies a `WITH CHECK` clause. Exactly the shape of the tenant-staff
login bug. `PortalEstateReadUnderRlsIT` wires the restricted role explicitly
via `@DynamicPropertySource` and creates its fixtures over real HTTP, so it
covers both sides. **Don't convert it to `@ServiceConnection`** — every
isolation assertion in it would then pass whether changeset 044 exists or
not.

## `portal.estates.view` and `portal.estates.manage` are two slugs, and neither implies the other

Reading the inventory is what most of a developer's staff do all day;
defining it is what two roles do. A single slug would have forced every
sales or finance user to hold the permission that also lets them create and
price plots. Changeset **045** grants `portal.estates.view` to
`executive_director`, `surveyor_project_manager`, `branch_manager`,
`sales_manager`, `finance_officer` and `legal_officer`; `portal.estates.manage`
(changeset 041) stays with the first two only. RLS still narrows what any of
them actually gets back — the grant says *what kind of thing* you may do,
the policy says *whose rows*.

`PortalEstateReadUnderRlsIT.readPermissionDoesNotGrantWriteAccess` pins the
non-implication: a sales manager lists estates and gets 403 creating one.

## A branch-scoped staff user with a leftover `buyer` role silently loses their wall

Found by a test fixture failing, not by inspection, and worth knowing before
the first admin "assign a role to this user" endpoint ships.

`TenantContextFilter` resolves a user holding **both** an organization-wide
role assignment and a branch-scoped one to **organization-wide** — correctly,
per the rule in the tenant-context section above: such a user has legitimate
reach beyond one branch, and narrowing would hide data they're entitled to.
That rule was written about two *staff* roles. It does not distinguish a
`buyer` assignment, which `POST /api/auth/register` grants to every account
it creates and which carries no `scoped_branch_id` because a buyer is never
tenant-scoped at all.

The consequence: a branch manager whose user row still has the buyer
assignment from registration is resolved organization-wide, and the hard
wall quietly becomes full access across their tenant's branches.

**Not reachable through any endpoint today** — a buyer has no `tenant_id`,
and nothing exposes granting a staff role to an existing account over HTTP,
so producing this takes direct SQL (which is exactly what a test fixture,
and the manual-walkthrough setup in the memory notes, both do). It becomes
reachable the moment a role-assignment endpoint exists. Fixing it means
deciding whether the mixed-assignment rule should ignore non-staff roles, or
whether granting a staff role should revoke the buyer one — a real decision,
not a one-liner, and not made in this slice.

`PortalEstateReadUnderRlsIT.branchScopeIsLostWhenAStaffUserAlsoHoldsAnOrganizationWideRole`
asserts the **current** behaviour so it's recorded rather than hidden. If it
starts failing because the wall now holds, that's the fix landing — delete
the test, don't restore the behaviour.

## Plot price is computed on read, and all three figures are returned

Nothing stores a corner plot's price (see `Plot`/`Estate.cornerPremiumPct`),
so every read has to compute it. `PlotPricing` is the one place that
happens: `tierPrice × (1 + cornerPremiumPct/100)` for a corner plot, the
tier's own price otherwise, `BigDecimal` throughout at the money scale of
`price_tiers.price` (4), never `double`.

`PlotDetailDto` returns `basePrice`, `cornerPremiumPct` and `price`
together, not just the final figure — a UI showing only `price` on a corner
plot shows a number matching no tier on the price list with nothing to
explain the difference. `cornerPremiumPct` is **null on a non-corner plot**
rather than echoing the estate's value, so a premium that wasn't applied
never reads as though it was.

`pricePerSqm` is **null, never zero**, whenever `nominalSizeSqm` is — a
`UNIT_TYPE` tier (an apartment) has no exclusive land area and therefore no
rate. Substituting `actualAreaSqm` there would quote a rate against a figure
the plot isn't priced by; zero would render as a free plot. And the
direction is fixed: the rate is derived *from* the price, never the reverse
— larger plots are routinely discounted per square metre, so pricing off a
rate would quietly overcharge every large plot.

**`PlotCountsDto.byStatus` only carries statuses that actually occur.** A
status with no plots is absent, not reported as zero, and an estate with no
plots gets `total: 0` with an empty map. A caller rendering a fixed set of
status chips supplies its own zero. It's a map rather than a field per
status so that adding a `PlotStatus` constant needs no change here and can't
silently go uncounted.

## GeoJSON output does not use `ST_AsGeoJSON`, and that is deliberate

`GET /api/portal/estates/{id}/geojson` returns a typed
`GeoJsonFeatureCollectionDto` built by `GeoJsonPolygonWriter` from the JTS
geometry Hibernate already materialised — the exact inverse of
`GeoJsonPolygonParser`, so what was POSTed comes back coordinate for
coordinate in the same `[longitude, latitude]` order. Two reasons it isn't
the obvious `ST_AsGeoJSON`, both found rather than assumed:

1. **It rounds.** `ST_AsGeoJSON` defaults to 9 decimal places, so its output
   isn't necessarily the geometry that was stored. Reading JTS keeps full
   `double` precision, which is what makes
   `geoJsonRoundTripsTheExactBoundaryThatWasPosted` an actual equality
   check instead of an approximate one.
2. **Its output is a JSON string and the properties beside it are not.**
   Embedding a pre-rendered fragment in a typed response means either
   `@JsonRawValue` — whose behaviour under Boot 4.1's `tools.jackson` stack
   this file already flags as unverified — or building the whole document in
   SQL, which would put the enum wire-value mapping (`AVAILABLE_DEV` →
   `"available-dev"`, not a mechanical lowercase) into a hand-written `CASE`
   that a new enum constant would silently fall out of.

Features with no boundary are **omitted, not emitted with a null geometry**:
most mapping clients render a null geometry as a point at `[0, 0]`, in the
Gulf of Guinea. The estate feature comes first so plots draw on top of the
boundary rather than under it.

## Conflict detection: the second deliberate RLS escape, and why it is a function

This is the feature the PostGIS work existed for — detecting when two
parties claim overlapping land — and it runs headlong into row-level
security three separate times. The reasoning sits here beside the first
escape (`TenancyApi`'s bootstrap functions, changeset 043) because the two
are the same shape and should be read together.

The slice spec allowed either approach: `SET LOCAL landvault.platform_scope
= 'on'` for the detection path, or a `SECURITY DEFINER` function.
**`SECURITY DEFINER` was chosen, and the reason is the size of the hole.**
`SET LOCAL` cannot be scoped to one statement — it applies for the rest of
the transaction, which here is the rest of a tenant's estate-creation
request. Every later read and write in that request would bypass tenant
isolation, including ones with nothing to do with detection, and an
exception thrown before it was set back would leave it elevated to the end
of the transaction. A definer function's privileges stop at the function
body.

### Three places the policy gets in the way, not one

Changeset 046 makes `listing_conflicts` **platform-scope only** — a
conflict belongs to neither company, so the standard tenant policy would
have made each one visible to exactly one side, or (with the branch clause)
neither. That single decision has three consequences, and only the first
was in the slice spec:

1. **Reading estates across tenants.** The advertised problem. This is
   literally the query `@TenantId` and Hibernate filters would have
   silently returned empty for — the third of the three reasons both were
   rejected in the foundation work, now actually built.
2. **Writing the conflict rows.** Detection runs inside a *tenant-scoped*
   request (CD-4 puts it at footprint submission), so the INSERT's
   `WITH CHECK` fails against a platform-scope-only policy. Not mentioned
   in the spec; a direct consequence of its own Part 1 instruction.
3. **The tenant's own view of their conflicts.** Same table, same policy —
   a tenant-scoped repository read returns nothing. Handled by a *fourth*
   function, `landvault_tenant_estate_conflicts()`, which turns out to
   strengthen CD-11 rather than complicate it (below).

Four functions in changeset 047, all `SECURITY DEFINER` with
`SET search_path = public, pg_temp`, `EXECUTE` revoked from `PUBLIC` and
granted only to the app role:
`landvault_detect_estate_conflicts`, `landvault_detect_plot_conflicts`,
`landvault_tenant_estate_conflicts`, `landvault_estate_conflict_summary`.

**Do not "simplify" any of these into a repository call.** Same warning as
the 043 functions, same outcome: it compiles, it passes under a superuser
connection, and it silently detects nothing in any environment where RLS is
actually on.

## `ST_Intersects` narrows; the area threshold decides

The single most important line in this feature, and the one the slice spec
originally got wrong (it framed `ST_Intersects` as deciding the answer,
which would have made CD-2 unusable):

**Adjacent plots share edges by design.** `ST_Intersects` is true for
boundary contact, so a detector that decides on it flags every correctly
surveyed neighbour in a subdivided estate. The queue fills with false
positives and stops being read — worse than no queue, because it hides the
real ones.

So the detection SQL puts `ST_Intersects` in the JOIN, where the GiST index
can use it, and decides on
`ST_Area(ST_Intersection(a, b)::geography) > threshold`. Geography cast, so
square metres; raw 4326 would give square degrees.

`estatesSharingOnlyABoundaryLineRaiseNoConflict` pins this, and asserts
first that the two estates genuinely *do* intersect — otherwise it would be
a test that passes because nothing touched.

### The sliver threshold: 1.0 m², and why

`landvault.conflicts.min-overlap-sqm`, default `1.0`. Two correctly
surveyed adjacent parcels meet along a line, which has zero area in exact
arithmetic — but real coordinates carry precision limits and floating-point
geometry produces a sliver. On a typical 250 m² plot (about 15.8 m a side),
1 m² is a strip roughly 6 cm wide along one edge: comfortably inside survey
tolerance, and orders of magnitude below any overlap representing a real
claim. Raise it if real survey data proves noisier; **never** raise it to
silence conflicts that are genuinely real.

### The EXPLAIN finding — the index narrows, exact geometry decides, visibly

Verified against 3,000 estates (on a handful of rows the planner correctly
prefers a sequential scan and the plan would prove nothing). The actual
plan:

```
Aggregate
  ->  Nested Loop
        ->  Index Scan using estates_pkey on estates s
              Index Cond: (id = '...'::uuid)
        ->  Index Scan using idx_estates_footprint on estates e
              Index Cond: (footprint && s.footprint)
              Filter: ((id <> s.id) AND st_intersects(s.footprint, footprint))
```

That is the whole design visible in the planner's own output: the GiST
index is used via the `&&` **bounding-box** operator as `Index Cond` to
eliminate almost everything, and exact `st_intersects` runs as a `Filter`
on the survivors. The bounding box is doing candidate narrowing — which is
what AGENTS.md always permitted — and is never the answer.
`detectionUsesTheGistIndexRatherThanASequentialScan` asserts the index name
appears and that `estates e` is not sequentially scanned; it was confirmed
able to fail by asserting on a string the plan cannot contain.

## Counterparty identity never leaves the database

CD-11's rule is that a tenant is told **that** a conflict exists, the
overlap, the severity and the consequence — and **never who the other
company is**. Both parties will believe they are right; handing each the
other's identity invites direct confrontation over disputed land, with the
platform having created the introduction.

Enforced twice, and the ordering matters:

- **`landvault_tenant_estate_conflicts()` never selects the counterparty's
  columns.** This is the load-bearing defence. The other side's entity id,
  tenant id and name are not fetched-then-dropped; they never leave
  Postgres, so they cannot leak through a later refactor, a debug log, a
  serialization change, or a DTO somebody widens without thinking.
- **`TenantConflictDto` has no field capable of holding one.** A narrow
  type cannot accidentally widen the way a controller stripping fields from
  a wide DTO can — adding disclosure would mean adding a field and meaning
  it.

`TenancyApi.organizationNamesFor` is a third, accidental layer: it is a
plain repository read rather than a definer function, so RLS applies and a
tenant-scoped caller cannot resolve another company's name even if
something did ask. That is why it was left as an ordinary query.

`theTenantViewNeverRevealsTheOtherCompany` asserts against the **raw
serialized body**, not the DTO's accessors — a field leaking through a
custom serializer or an unexpected getter would pass a shape-level check
and fail this one.

**Tone is part of the contract, not decoration.** Most conflicts are survey
errors. The `guidance` string states what was found, what it means for the
listing, and the one action that clears it; nothing reads as an accusation.
Same discipline as arrears messaging.

## HIGH blocks publication, MEDIUM warns — and that asymmetry is the decision

The slice spec required a decision on MEDIUM and here it is: **MEDIUM warns,
it does not block.**

A MEDIUM conflict is one company's own two estates or plots overlapping.
Nobody is about to pay the wrong party; it is an internal survey problem.
Blocking a company's trade over their own data-entry error would be
disproportionate, and it punishes the honest case — a developer who
uploaded two boundaries carefully and got one slightly wrong — exactly as
hard as the dishonest one.

A HIGH conflict is two different companies claiming the same ground. One of
them is wrong and a buyer could pay the wrong one, which is the entire
scam this platform exists to kill. That blocks.

A `CONFIRMED_DUPLICATE` blocks at **any** severity: once a human has looked
and said it is a real duplicate, the survey-error assumption behind MEDIUM
no longer applies.

`ConflictPublicationCheck` keeps `blocked` and `warningConflictCount` as
separate fields rather than one boolean, so whoever builds the publication
gate inherits the distinction instead of re-deciding it (probably
differently).

## `AUTO_RESOLVED` is set by detection, never by a reviewer

CD-9: when a footprint is corrected and the overlap no longer exists, the
conflict resolves itself and **the record is kept**, never deleted — a
queue full of conflicts that were fixed weeks ago stops being trustworthy.

The status is rejected as a manual transition. A reviewer marking a
conflict "resolved itself" while the land still overlaps would be asserting
a fact about the world they are not in a position to assert; `dismissed` is
the judgement they *are* entitled to make.

**This path is built but currently unreachable through HTTP**, and that is
a real gap rather than a finished feature: nothing updates a footprint yet
(creation only), so re-detection never fires on a corrected boundary. The
same gap AGENTS.md already records for recomputing `actual_area_sqm` on
edit — whoever builds footprint correction closes both at once, and should
call `ConflictDetectionApi.detectForEstateBoundary` from it.
`correctingTheGeometryAutoResolvesTheConflictAndKeepsTheRecord` exercises
it by mutating geometry in SQL and invoking the API directly, so the logic
is genuinely tested rather than shipped on a promise.

### CD-8: a re-scan must not destroy work in progress

The upsert refreshes `overlap_area_sqm`/percentages/severity and
**deliberately never touches `status`**, so a Super Admin halfway through
an investigation keeps it. Identity is preserved by a deterministic pair
ordering — `left_entity_id < right_entity_id`, enforced by a database CHECK
rather than trusted of the service — plus a partial unique index over the
live statuses. Without the ordering, detecting A-vs-B and B-vs-A would
create two rows for one conflict and no unique index could prevent it.

The unique index deliberately **excludes** `DISMISSED` and `AUTO_RESOLVED`,
so geometry that is corrected and later broken again raises a fresh
conflict rather than colliding with the historical record. That is also why
re-deciding a closed conflict is refused: the new conflict is a new row,
and the original decision stays intact as history.

## Conflicts: what diverges from `listingConflictsService.ts`

Recorded, as with every earlier slice, rather than reconciled silently in
either direction:

- **The review route is `POST /api/admin/listing-conflicts/{id}/status`**
  taking `{ status, reason }`. The frontend calls `.../{id}/review` with
  `{ decision, note }`. Followed the task spec, which was specific enough
  to read as deliberate.
- **`AUTO_RESOLVED` is not in the frontend's `ConflictStatus` union** —
  a backend-only fifth state. The frontend would render it as an unknown
  status today.
- **`PLOT_OVERLAP` has no frontend concept at all.** The frontend models
  estate conflicts only. `ListingConflictDto.conflictType` distinguishes
  them, and for a plot conflict `estateAId`/`estateBId` carry *plot* ids
  with `estateId` carrying the containing estate — a real wart, kept
  because renaming the fields would break the estate case the page already
  renders.
- **`estateAFootprint`/`estateBFootprint` are not returned.** The frontend
  denormalizes those purely for map rendering. Nothing here fabricates
  them, and serving them would mean this module reaching into inventory's
  geometry for a field the queue does not need to decide anything.

The permission is `admin.marketplace.conflicts`, seeded back in changeset
014 for exactly this feature (SA-3.4) and granted to `super_admin` in 016 —
**verified present before writing anything, not re-created.** The
tenant-facing route reuses `portal.estates.view` rather than inventing a
slug: it is a read about an estate, by the same people who read the estate.

`EstateLabelResolver` is the **fourth** use of the inverted-interface
pattern (after `ActorNameResolver` and tenancy's two). `inventory` already
depends on `conflicts` — it triggers detection when a footprint is written
— so `conflicts` calling an `InventoryApi` back would close a cycle.
Declaring the interface in `conflicts` and implementing it in `inventory`
keeps every arrow pointing the way it already did.

### `EntityManager.flush()` in a service bypasses Spring's exception translation

A regression this slice introduced and a pre-existing test caught, worth
recording because it will recur the next time something is wired in *after*
a save.

Detection needs the row it is about to check to be visible to a native
query, so `ConflictDetectionService` calls `entityManager.flush()`. Wiring
that into plot creation turned
`PortalEstateCreationIT.aDuplicatePlotNumberIsAConflictNotAServerError`
red: a duplicate plot number started escaping as a **500** instead of the
**409** it had been returning since inventory slice 2.

The cause is not the flush itself but *where* it happens. Spring's
persistence-exception translation is applied by the Spring Data repository
proxy — so a constraint violation raised by `repository.saveAndFlush(...)`
arrives as `DataIntegrityViolationException`, which
`InventoryExceptionHandler` maps to 409. The same violation raised by a
bare `EntityManager.flush()` inside a `@Service` is **not** translated: it
comes out as a raw `PersistenceException` that no handler matches, and
Spring Boot's default error path turns it into a 500.

Previously the flush happened implicitly at commit, inside the repository's
own machinery. Moving it earlier moved it outside that machinery.

**The rule: flush through the repository (`saveAndFlush`/`saveAllAndFlush`),
not through the `EntityManager`, whenever the flush might surface a
constraint violation a handler is supposed to catch.** `PortalEstateService`
now does exactly that, and detection's own flush is kept only as a safety
net for a caller that did not.

### A build trap that made a passing test look broken

Also worth knowing, because it wasted a full-suite run: reverting a
temporary test edit with `mv file.java.bak file.java` **preserves the
backup's original mtime**, which can leave the restored source *older* than
the `.class` compiled from the edited version. `maven-compiler-plugin` then
considers it up to date and skips it, and the suite silently runs the stale
class — in this case one still asserting on a deliberately impossible
string. The plan under test was correct the whole time. `touch` the file
after restoring it, or revert with an editor rather than `mv`.

## Auto-resolution does not clear a HIGH conflict, and the reason is an attack, not a nicety

Found live, not in review: CD-9 as originally built let a HIGH conflict
clear itself the instant its overlap dropped below the sliver threshold —
same mechanism as MEDIUM. That is exploitable, and the exploit is cheaper
than it sounds: nothing requires moving off the disputed ground. Shaving a
few square metres off one edge — comfortably inside the sliver threshold —
drops the intersection area under the noise floor while keeping almost all
of the contested parcel. Detection would then silently clear the
conflict and lift the publication block, with no human ever looking at it,
and a buyer would have no way to know a HIGH-severity dispute had ever been
raised on that listing.

Geometry no longer overlapping proves the shapes changed. It does not prove
whether that change was an honest correction or a deliberate dodge — and a
detector that treats the two as the same signal can be defeated by anyone
who understands the threshold, which is public knowledge the moment the
API responds to a probing request.

**The fix mirrors a rule this codebase already has for money**:
*"never auto-confirm a payment"* — a signal arriving is not proof; a human
confirms. Applied here:

- **MEDIUM** (same tenant, nobody at risk of paying the wrong party) keeps
  auto-resolving exactly as CD-9 originally specified — full auto-clear,
  `status → AUTO_RESOLVED`, publication (never blocked to begin with)
  unaffected.
- **HIGH, or anything a human has explicitly marked `CONFIRMED_DUPLICATE`
  at any severity**, does not. Detection instead sets a new column,
  `listing_conflicts.geometry_cleared_at`, and leaves `status` — and
  therefore the publication block — exactly where it was. A Super Admin has
  to look at it and dismiss it. `geometryClearedAt` resets to null if the
  pair overlaps again on a later scan (the upsert's `ON CONFLICT DO UPDATE`
  branch handles this, since a live overlap always re-enters that path).

**The one transition rule that had to loosen to make this usable**: a
decision was previously fully terminal — `CONFIRMED_DUPLICATE` could never
move again. That is still true for every target except one:
`CONFIRMED_DUPLICATE → DISMISSED` is now allowed, specifically so a human
reviewing a correction has a way to stand down. Nothing else escapes a
confirmed duplicate — not `INVESTIGATING`, not re-confirming — because once
a human has decided, the only remaining question is whether that decision
still stands, and dismissal is how they say no.

**`ConflictPublicationCheck`'s blocking message no longer claims a
correction clears anything automatically.** The pre-fix wording ("updating
[coordinates] clears this automatically") was actively teaching a bad actor
the exploit. `TenantConflictDto.guidance` follows the same discipline:
whether the geometry has cleared is now surfaced as its own field
(`underReview`), and the copy is honest in every state —
`clears automatically` is only ever said for the cases where it's actually
true (an unconfirmed MEDIUM conflict, an unconfirmed plot conflict).

**History is kept either way** — nothing here has ever hard-deleted a row,
resolved or not. What changed is that a HIGH resolution now requires a
human act to reach, so the record includes *who* decided and *why*, not
just that the geometry happened to change. Surfacing this to a future
buyer-facing marketplace listing (so a buyer can see "a boundary conflict
was raised on this estate and how it was resolved") is explicitly future
work — no buyer-facing surface exists anywhere in this codebase yet. The
data already supports it; nothing here builds toward it prematurely.

### A real bug this surfaced: `Set.of()` throws on same-tenant conflicts

`ConflictQueryService.getForAdmin` built `Set.of(leftTenantId, rightTenantId)`
to batch-resolve company names for a single conflict. For a MEDIUM
(same-tenant) conflict those two values are identical, and `Set.of()`
throws `IllegalArgumentException: duplicate element` on a repeated
argument — every admin GET-by-id on a same-tenant conflict returned a
**400**, not the conflict. Nothing caught this earlier because every
existing test's admin-GET-by-id calls happened to use cross-tenant pairs;
the new MEDIUM auto-resolution test was the first thing to exercise that
path for a same-tenant conflict, and it failed immediately once run rather
than being caught by inspection. Fixed with `new HashSet<>(List.of(a, b))`
— `List.of` allows duplicates, `HashSet` then dedups. Swept the rest of the
codebase for the same shape (`Set.of(<two runtime values>)`); the two other
occurrences are safe — one is guarded by an explicit `isCrossTenant()`
branch already, the other pairs entity ids that a CHECK constraint
guarantees can never be equal.

### Editing an already-applied changeset requires reconciling every database that ran it — not just git

Editing 046/047 in place (rather than layering new changesets on top) was
fine from git's perspective — nothing had been committed. It was **not**
fine from Liquibase's perspective: the local dev database had already
executed the pre-edit version when the app was restarted earlier in this
slice, and Liquibase's own `validate` step compares each changeset's
recorded checksum against the current file content on every startup.
"Uncommitted" and "unapplied" are different facts, and only one of them is
what Liquibase tracks.

**Two ways to reconcile a database that already ran the old version, and
why the second is the right one**:

1. Hand-run the new `ALTER`/`CREATE OR REPLACE` SQL directly, then null the
   changeset's recorded `md5sum` so Liquibase recomputes and accepts it.
   Works, but risks transcription drift between what gets pasted and what
   the file actually says — and doesn't even apply cleanly here, since
   Postgres refuses `CREATE OR REPLACE FUNCTION` when a function's
   `RETURNS TABLE` columns change (`landvault_tenant_estate_conflicts`
   gained a column), forcing a drop-and-recreate anyway.
2. **Drop the objects the changeset created, delete its row from
   `databasechangelog`, restart.** Liquibase then treats the changeset as
   never having run and executes the *actual current file* itself —
   zero transcription risk, and it computes the correct checksum as a
   normal side effect of really running the SQL, rather than the checksum
   being asserted and hoped correct.

**The gotcha that would have silently broken permissions**: dropping a
function drops every grant on it too — grants belong to the object, not to
whichever changeset issued them. `047-grant-execute-on-conflict-functions`
wasn't itself edited, so its checksum still matched and Liquibase would
have skipped re-running it, leaving the *recreated* functions with no
`EXECUTE` grant for the app role at all — a silent permission-denied
failure on the very next call, not caught until runtime. Fixed by deleting
that changeset's row too, purely so it re-grants against the new function
objects, even though its own SQL never changed.

**The consequence for future changes here**: this reconciliation is what
makes 046/047 genuinely applied — not just to a disposable Testcontainers
instance, but to the one real database anyone is using. From this point on
they follow the same rule as every other changeset in this project: never
edited in place again. Any further change to conflict detection is a new
changeset (048+).

## A dismissal sticks unless the geometry changed — and `geometry_cleared_at` is what tells the two kinds of dismissal apart

Found walking the review flow live, right after the auto-resolution fix.
The unique index in changeset 046 deliberately ignores `DISMISSED` rows, so
a corrected boundary that is later broken again raises a fresh conflict.
Side effect: a Super Admin who looks at two estates sharing a strip along a
road, decides it's fine, and dismisses it **without anyone touching a
boundary** would have that decision silently undone on the very next
re-scan — the overlap is still there, there's no live conflict for the
pair, so detection raises a new one. Every re-scan, forever. Dormant today
(nothing re-detects an existing estate yet), live the day a
footprint-update endpoint ships. Fixed in changeset **048**.

**The rule**: detection does not raise a fresh conflict for a pair when its
dismissal was made against a *still-overlapping* boundary
(`geometry_cleared_at IS NULL`) **and** the overlap area is unchanged
within the sliver threshold. Everything else raises a new one.
`AUTO_RESOLVED` never suppresses — nobody decided anything.

**Why the `geometry_cleared_at` condition is load-bearing, not a detail.**
The obvious rule — "same overlap area → dismissal stands" — reopens the
hole the auto-resolution fix just closed. Walk it: a HIGH conflict's
boundary is corrected, a reviewer dismisses it (accepting the correction),
and the boundary is then put back. The dismissed record's stored area is
the last real overlap, which equals the current one. A naive rule would
suppress the new conflict. That is the revert-after-clearance attack, and
the live walkthrough (4d) would have quietly produced nothing. So
`geometry_cleared_at` does double duty: set, the dismissal accepted a
*correction* and blesses no overlap at all; null, the reviewer looked at
*that overlap* and judged it acceptable. Only the second kind stands.
`aDismissalOfAnUnchangedOverlapStandsAcrossReScans` and
`aLiveConflictIsListedAboveAClosedRecordForTheSamePair` pin the two cases
against each other.

**The threshold is reused, not a second knob.** "Unchanged" means within
`landvault.conflicts.min-overlap-sqm` — the same 1 m² that already defines
what counts as a real overlap. A pair whose overlap grew or shrank by more
than that is a different situation and gets looked at again
(`aDismissedOverlapThatChangesRaisesAFreshConflict`).

**Why a new changeset.** 047 is applied to a real database and is no longer
edited in place (see the reconciliation note above). 048 is
`CREATE OR REPLACE` for the two detection functions — same signature, so
the existing `EXECUTE` grants survive and no re-grant is needed, unlike
the drop-and-recreate that 047's own reconciliation required. Its rollback
restores 047's bodies verbatim the same way.

### Two smaller things the same walkthrough caught

- **The dismissed-conflict guidance said "closed as a false positive".** A
  confirmed duplicate dismissed after a correction is not a false
  positive, and "false positive" is a verdict the owner's copy never
  needed to assert. Now: "closed after the boundary was corrected and our
  team reviewed the update" when `geometry_cleared_at` is set, a neutral
  "our team reviewed this and closed it" otherwise.
- **Closed records outranked live conflicts.** Both views sorted by
  severity then area; a dismissed HIGH and a fresh open HIGH for the same
  pair tie on both, so the order was arbitrary, and the tenant saw history
  above the conflict actually pausing their listing. Live now sorts before
  closed in both the tenant view (a stable sort in Java — 047's `ORDER BY`
  is not edited) and the admin queue (a `CASE` in the specification).

## The public marketplace: a view is the RLS escape, and why it's narrower than a function

The first surface in this system with **no authentication**. An anonymous
request has no tenant scope and no platform scope, so every RLS policy
fails closed and every table returns zero rows — silently, with a 200. The
marketplace would "work" and show nothing. This is the fifth time this
codebase has met that shape (after tenant-staff login, the branch
switcher, conflict detection, and the inventory read policies), and the
third deliberate escape (after changesets 043 and 047).

**The escape is a set of Postgres views** (changeset 049), owned by the
migration role. A view reads its tables with the **owner's** privileges;
the owner is a superuser, and superusers bypass RLS even under `FORCE ROW
LEVEL SECURITY`. If migrations ever run as a non-superuser table owner,
`FORCE` applies to the owner and every view returns nothing — fail closed,
and `MarketplaceIT.anAnonymousRequestSeesAPublishedEstate` goes red.
That test was **proven to fail** by switching the view to
`security_invoker = true` (the caller's RLS instead of the owner's): the
feed came back empty.

**Why a view is narrower than the `SECURITY DEFINER` precedent.** A view's
column list is fixed. No caller can make it return a column it never
selected, and no refactor of calling code can widen it — which is exactly
MP-3's "a distinct projection, not tenant tables with a filter". What the
public can see is decided in one reviewed place: adding a column to a
`marketplace_*` view is publishing it to the internet.

**What the views must never select**: clients, finances, staff, documents,
directors, verification decisions, verification-check notes, title
numbers, estate addresses, the raw plot status, reserved or sold counts,
tenant or branch ids. `MarketplaceIT.thePublicPayloadContainsNothingTenantPrivate`
plants several of these and asserts on the serialized JSON of all three
routes.

**`security_barrier` on every view**, so a caller-supplied predicate built
on a leaky function can't run before the eligibility filter. **SELECT only**
for the app role: changeset 019's default privileges would otherwise grant
INSERT/UPDATE/DELETE on every new relation, views included, and a
single-table view is auto-updatable — a write through an owner-privileged
view would bypass RLS. None of these views is updatable today (all join),
which is why the guard test asks `has_table_privilege` directly: the first
version attempted an UPDATE, and Postgres rejected it for being
non-updatable *before* checking privileges, so that test would have passed
with the REVOKE missing. Proven red by granting INSERT.

**`marketplace` reads only views, never a tenant repository.** Under an
anonymous request a repository returns nothing, and the obvious "fix" —
elevating scope — is the leak MP-3 exists to prevent. A new buyer-visible
field goes in the view, where it's reviewed as a publication decision.
Hibernate accepts `@Immutable` entities mapped onto views under
`ddl-auto: validate`; the repositories extend `Repository`, not
`JpaRepository`, so no save method exists to call.

### Publication is intent; eligibility is current state

The five conditions live in exactly **one** place,
`marketplace_estate_eligibility`, and every public view joins through it,
so an ineligible estate is structurally unreachable rather than filtered
by a query someone might forget. Evaluated at read time: the `published`
flag records what the developer wants, and is **never cleared** when the
tenant stops qualifying. A suspended tenant's listings vanish and return
on reinstatement without republishing (PB-5); a new HIGH conflict pulls a
live estate with no extra mechanism (PB-6).

**Read-time eligibility checks `published = true` and nothing about who
set it** — the read is anonymous and has no caller. Who may publish is a
permission (`portal.estates.manage`), checked once at publish time.

**The publish endpoint and the public feed can't disagree.**
`POST /api/portal/estates/{id}/publish` reads the tenant conditions through
`MarketplaceApi` — the same eligibility view — and the conflict condition
through `ConflictDetectionApi.publicationCheckFor`, which calls the same
SQL function the view does. Two implementations of one gate would drift;
this way a refusal and an absence from the feed are one answer. It names
every failing condition (the code is the first), in a fixed order:
verification, entitlement, tenant active, conflict. A conflict refusal uses
`ConflictPublicationCheck.blockReason`, which carries no counterparty by
design. Conditions are checked even for an already-published estate: a
published estate can be off the marketplace, and republishing is how the
developer finds out why. Re-publishing an eligible published estate, or
unpublishing an unpublished one, is a no-op and writes no audit entry,
since nothing happened. MEDIUM conflicts ride along in the success response
(`warningConflictCount`) — they never refuse.

`inventory → marketplace` is the new edge (publish needs `MarketplaceApi`);
`marketplace` depends on `common` only.

### Public plot status is AVAILABLE / UNAVAILABLE, collapsed inside the view

`reserved` versus `sold` is internal sales information — it reveals sales
velocity to competitors. The collapse happens **in `marketplace_plots`**, so
the raw status never reaches the application, let alone a buyer.
Unavailable plots still appear on the map so the estate reads as a real
place rather than a sales sheet; the reason isn't disclosed. (Tier-level
`plotsRemaining` and `availability` — `available`/`low_stock`/`sold_out`,
the frontend's own rule — are published: MP-5 allows available counts.)

### Shared types moved to `common`

`TitleType`, `EstateIntent`, `VerificationCheckType`,
`VerificationCheckStatus` and `VerificationSource` moved from
`inventory.internal.enums` to `common`: marketplace needs their wire values,
and this file already says enums used across modules belong there. The
GeoJSON DTOs and `GeoJsonPolygonWriter` moved to `common.geojson` — a wire
format, not domain logic — so the public map and the portal map are
literally the same Java types, which is what "one component renders both"
needs. `GeoJsonPolygonParser` stayed in `inventory`: its Nigeria-bounds
check is domain logic.

### Rate limiting: in-memory, keyed on the remote address

`MarketplaceRateLimitFilter`, `/api/marketplace/` only, 120 requests per
client per minute by default (`landvault.marketplace.rate-limit.*`), 429
with `Retry-After` beyond it. Fixed windows.

- **Keyed on `request.getRemoteAddr()`, never `X-Forwarded-For`.** Anyone
  can send that header; trusting it lets a client claim a fresh address per
  request and turns the limiter into decoration.
  `MarketplaceRateLimitIT` sends a forged one after being throttled and
  asserts it stays throttled. **The assumption**: no reverse proxy sits in
  front of the app today, so the remote address is the real client, and
  `server.forward-headers-strategy` is deliberately unset. Behind a proxy
  every client would share its address — the fix then is Tomcat's
  `RemoteIpValve` with that proxy as the only trusted one, not reading the
  header here.
- **Per-instance.** Behind more than one server each keeps its own counts.
  That needs a shared store (Redis, or the database) before scaling out.
- The table of tracked clients is bounded (`max-tracked-clients`); past it
  expired windows are swept, and if it's still full it's cleared — erring
  towards letting requests through rather than failing closed on everyone.
- The test classpath raises the limit, since every IT browses from
  127.0.0.1.

### Public routes: explicit, and GET only

Listed one by one in `SecurityConfig.PUBLIC_GET_PATHS`, never
`/api/marketplace/**`: wishlist, enquiries and reservations will live under
that prefix and must require a login. Permitted for **GET only** — a step
stricter than the slice spec's "add to `ALWAYS_PUBLIC_PATHS`" — so a future
write on one of these exact paths doesn't inherit anonymous access either.
Reads are not audited: the audit log records actions, not page views.

### Known gaps, recorded rather than filled

- **Frontend contract**: it calls `/api/marketplace/listings` (this is
  `/estates`, per the slice spec), fetches plots from a paginated
  `/listings/{id}/plots` rather than `/geojson`, expects the internal plot
  status (now collapsed), and has `paymentPlans` — which nothing stores, so
  it's absent. The frontend's `paymentPlans.includes(...)` filter would
  need changing.
- **Price filters apply within one currency** (`currency`, default NGN).
  Comparing a ₦ "from" price with a $ one ranks land by a meaningless
  number. An estate whose tiers mix currencies has an ambiguous "from"
  price; the view takes the cheapest available tier as stored.
- **An estate with no boundary can be published** and can never raise an
  estate-level conflict, since there's nothing to intersect — a way to sit
  outside the fifth condition entirely. Not one of the five conditions as
  specified; flagged as an open decision, not silently added.

## The OpenAPI document describes what exists, and stays dev-only

`OpenApiConfig` (in `common`) carries the title, the JWT bearer scheme and
the tag order; every controller carries a `@Tag`, and every operation a
summary, description and documented failure responses. **No behaviour
changed** — annotations and configuration only.

**The dev-only gating is untouched and must stay that way**: the document is
generated and reachable under the `dev` profile alone
(`springdoc.api-docs.enabled` plus `SecurityConfig.DEV_ONLY_PUBLIC_PATHS`).
A publicly readable spec is free reconnaissance — the complete API surface
handed over before anyone authenticates.

**Security is declared globally and opted out of, never the reverse.**
`OpenApiConfig` adds the bearer requirement to the whole document, and the
handful of genuinely public operations carry `@SecurityRequirements` (empty).
An operation that says nothing is therefore documented as protected, which
is the safe direction to be wrong: the failure mode is a needless
"Authorize" prompt, not a reader believing a protected endpoint is open.

### The public-path cross-check

The slice asked for the documented public endpoints to match
`SecurityConfig` exactly, and to report any discrepancy rather than
silently pick one. Three findings, none of them a security gap:

1. **The marketplace routes are not in `ALWAYS_PUBLIC_PATHS`**, which is
   what the slice assumed. They are in a separate `PUBLIC_GET_PATHS` list
   permitted for **GET only**, added in the publication slice so a future
   write on one of those exact paths cannot inherit anonymous access. The
   effect matches the documentation (those GETs are public); the list they
   live in does not.
2. **`/actuator/health` and `/error` are public but are not documented**,
   because neither is a controller in this codebase. `/error` in particular
   is there so a wrong method or missing handler returns a real 404/405
   instead of a misleading 401 — an implementation detail, not API surface.
3. **`/api/auth/**` is no longer a wildcard.** `AuthController`'s own class
   comment still claimed it was; the 2FA slice replaced it with one entry
   per route so the 2FA management endpoints require a session. The stale
   comment was corrected while annotating.

Everything else lines up: the five `AuthController` routes and
`/api/auth/2fa/verify` are documented public and are public; the four other
`/api/auth/2fa/*` routes are documented protected and are protected.

### A correction to the slice's own brief

It said "TOTP secrets and password hashes never appear" in responses.
Password hashes never do. **The TOTP secret does** — once, in
`TwoFaSetupResponse`, deliberately, so a user can type it in when a QR code
cannot be scanned. Hiding that from the documentation would have made the
spec disagree with the API. It is documented as what it is instead: returned
here and nowhere else, encrypted at rest, never readable again.

### What the documentation is for

Not tidiness. These endpoints carry rules a path list cannot show, and they
are the ones an integrator gets wrong:

- **`[longitude, latitude]`**, called out on every route that takes or
  returns geometry, with a copyable Abuja example on `GeoJsonPolygonDto`.
  Transposing it raises no error.
- **Login returns one of two different 200 shapes**, and the second carries
  no tokens.
- **Setup does not enable 2FA**; confirmation does.
- **Identical responses are deliberate** — wrong password vs unknown email,
  and every password-reset failure — so nobody "fixes" them into helpful
  errors that enable account enumeration.
- **The tenant conflict view omits the counterparty on purpose**, so it is
  not filed as a missing field.
- **Absent is absent**: an unchecked verification renders as unchecked, a
  plot with no boundary has a null surveyed area, a masked ID number stays
  masked.

Where behaviour is deliberately limited, the documentation says so rather
than letting a reader infer capability: support-access grants are a record
and not a gate, reads are not audited, and the audit log has no write
surface by design.

## Reserving a plot: the third RLS escape, and the first one that writes

This is where browsing becomes buying, and it runs into row-level security
harder than anything before it.

A buyer's session is `tenant_id = ''`, `platform_scope = off`, so changeset
044's policies make `plots` **neither readable nor writable** to them.
Verified against the app role rather than inferred: with a buyer's exact
GUCs, `SELECT count(*) FROM plots` returns 0, and
`UPDATE plots SET status = 'RESERVED'` affects **0 rows with no error**.

**That second half is what makes this dangerous rather than merely broken.**
The correct atomic acquire is:

```sql
UPDATE plots SET status = 'RESERVED'
 WHERE id = ? AND status IN ('AVAILABLE_DEV','AVAILABLE_INV')
```

checking the affected row count — where **zero means another buyer won the
race**. RLS produces the identical zero. From inside the application the two
are indistinguishable, so every buyer would be told "that plot is no longer
available", forever, with nothing in any log to say why. And because
`@ServiceConnection` tests connect as the superuser, the whole suite would
have been green.

Changeset **054** is the escape: `landvault_reserve_plot(uuid, varchar)` and
`landvault_release_plot(uuid, varchar, varchar)`, `SECURITY DEFINER`, hardened
exactly like 043 and 047 — `SET search_path = public, pg_temp`, `EXECUTE`
revoked from `PUBLIC` and granted only to the app role. A view cannot help
here the way changeset 049's did for the marketplace: **views are not
writable**.

`ReservationCheckoutUnderRlsIT` wires the restricted role explicitly and was
**proven red** by flipping the functions to `SECURITY INVOKER` — the acquire
then runs under the caller's own policies and every reservation is refused as
`PLOT_NOT_AVAILABLE`. **Do not convert that class to `@ServiceConnection`**,
and do not "simplify" either function into a repository call.

### The release function validates its own argument

`landvault_release_plot` takes the status to restore, and rejects anything
that is not `AVAILABLE_DEV` or `AVAILABLE_INV`. Without that check, a definer
function that writes `plots.status` would be a way to mark any held plot
`SOLD` with no payment, from a buyer's own session — a worse hole than the
one it exists to bridge. **Any `SECURITY DEFINER` function that writes a
caller-supplied value has to validate it inside the function**; the caller is
by definition outside the privilege boundary.

## Postgres compare-and-swap, not Redis — and the TTL argument is what settles it

The slice specified Redis with a TTL, on the reasoning that a read-then-write
check cannot be atomic. The first half is right; the conclusion does not
follow, and this was reconciled before building rather than after.

A single-statement `UPDATE … WHERE status IN (…)` **is** an atomic
compare-and-swap: Postgres takes the row lock for the statement's duration, so
of two concurrent attempts exactly one updates and the other reports zero
rows. It is the same DB-level CAS this codebase already uses for refresh-token
rotation. The function implements it with `SELECT … FOR UPDATE` followed by
the update, because Postgres 16 has no `OLD.*` in `UPDATE … RETURNING` (that
arrived in 18) and the **previous** status has to be captured — see the
availability-variant rule below. Under `READ COMMITTED` a second caller blocks
on the row lock, then re-evaluates the qual against the committed row, finds
`RESERVED`, and gets nothing.

**Why not Redis:** a key expiring in Redis does not flip `plots.status` in
Postgres. Something must still write to the database on expiry, so the sweeper
has to exist regardless — at which point Redis is a *second source of truth*
for plot availability that can disagree with the first, with nothing
reconciling them. It is also not currently a dependency. Reach for it when a
lock genuinely must be shared across instances, which is the same point the
marketplace rate limiter needs it.

`twoBuyersReachingTheSamePlotAtOnceCannotBothHoldIt` releases two real HTTP
requests from a latch and asserts exactly one 201 and one 409.

## The expiry sweep is the first scheduled job, and it has to establish its own scope

AGENTS.md predicted this before anything could hit it: *"it cannot rely on 'no
context means see everything' … There is no ambient 'system' identity that
sees past RLS today."* `ReservationExpirySweeper` is the first job to meet it.

It sets a platform `TenantScope` in `TenantContext` for the duration of the
sweep and clears it in a `finally`, exactly as `TenantContextFilter` does for
a request. The scope has to be established **before** the transaction begins —
`TenantScopedDataSource` issues its `SET LOCAL` statements when the connection
turns off autocommit — which is why the sweeper is a thin trigger around a
`@Transactional` method **on another bean**, not a self-invoked method.

Exceptions are caught and logged rather than propagated: an exception escaping
a `fixedDelay` scheduled method cancels all future runs, turning one bad sweep
into holds that never expire again.

`sweep-interval` is 60s, and the test classpath sets it to an hour — a sweep
firing mid-assertion would pull a hold out from under a test. `ReservationIT`
calls `sweep()` directly instead, which is also the only way to test expiry
without waiting on a clock.

**Per-instance.** Behind more than one server every instance sweeps; that is
wasteful rather than wrong (the release is idempotent and a reservation closes
once), but it wants a shared scheduler lock before scaling out.

### Releasing restores the *variant*, never a hardcoded available status

`AVAILABLE_DEV` and `AVAILABLE_INV` are a real distinction, so the acquire
captures `previous_plot_status` on the reservation and the release writes that
back. A hold on an investment plot must not return it to the pool as a
development plot. Pinned by
`anExpiredHoldIsSweptAndTheOriginalAvailabilityVariantComesBack`.

## A hold cannot be extended

RS-5 asked for a decision: **no extension, ever.** One window for everyone is
the fair version — every minute added for one buyer is a minute taken from
whoever is waiting behind them, and an extendable hold is a way to park
inventory indefinitely. `hold-duration` is configuration so a test can shorten
it, not so it can be negotiated per buyer.

## Price is captured on the *reservation*, not on the transaction

TX-1 says the price is captured at reservation and never recomputed. The
transaction is opened by a **separate, later request**, so capturing it there
would recompute against whatever the tier says minutes afterwards.

So `reservations` carries `base_price`, `corner_premium_pct`, `total_price`
and `currency`, computed by `PlotPricing` from the same locked snapshot that
granted the hold, and the transaction **copies** them. A developer re-pricing
a tier mid-checkout cannot alter what the buyer already agreed to.
`repricingTheTierAfterAHoldDoesNotChangeWhatTheBuyerAgreedTo` pins it.

This is the one place in this schema where duplicating a value is correct
rather than drift: the tier answers "what does this cost today", the
reservation and transaction answer "what was agreed".

### No money ever comes from the client

The frontend's `initiateTransaction` currently posts `basePrice`,
`totalPrice`, `amountDue`, `cornerPremiumPct`, `sizeSqm` and `titleType` from
the browser. **None of them is accepted.** A client-supplied price is the same
class of hole as a client-supplied `tenantId` — it lets the payer name their
own figure. `CreateTransactionRequest` carries a reservation id, an intent and
a plan, and nothing else; `aPriceSentByTheClientIsIgnoredEntirely` posts raw
JSON with a ₦1.00 price and asserts the stored figure is the server's.

**Frontend reconciliation needed**: that call has to stop sending them, and
`POST /api/reservations` takes only `plotId` (the estate is derived from the
plot, never trusted from the request).

## Two new `client.*` slugs, and one `admin.*`

`client.checkout.reserve` and `client.kyc.manage`, granted to `buyer`;
`admin.kyc.review`, granted to `super_admin` and `compliance_officer` only
(reviewing identity documents *is* the compliance role; `platform_moderator`
is left out on the same narrower-is-reversible reasoning as
`admin.audit.view`).

Reusing `client.marketplace.view` was rejected: browsing and buying are
different acts, and folding the ability to take a plot out of circulation into
the slug everyone browsing holds leaves no way to restrict purchasing later.

**Frontend requirement**: `authService.ts` knows none of these three. The nav
and route gates render directly off these strings, so they must be added
there too.

## KYC: platform-level, and the NIN does not repeat the `directors` mistake

`kyc_records` and `kyc_documents` carry **no tenant** — a buyer verifies once
and transacts with every company (KY-4). That is the structural reason buyers
are not tenant-scoped, not a consequence of it.

Neither table is RLS-policied, for the same reason as `users`/`refresh_tokens`/
`otp_codes`: every row belongs to a buyer, a buyer's session has no tenant or
platform scope, and a fail-closed policy would hide a buyer's own record from
them and nothing else. Rows are reached only by `user_id`. This becomes a real
question the day a tenant-facing surface needs to see that a buyer is verified
(KY-5) — that view must expose the *outcome*, never these rows.

**`nin_number` is encrypted at rest** (AES-256-GCM, `KycNinConverter`, its own
`KYC_ENCRYPTION_KEY` — deliberately a different key from
`TOTP_ENCRYPTION_KEY`, so one leak does not yield both). A converter rather
than service-layer calls, for the same reason `@SQLRestriction` lives on the
entity: writing plaintext becomes structurally impossible. The column is
`text`, not `varchar(11)` — it holds ciphertext, and sizing it to the
plaintext would truncate. **No route returns it, not even masked.**

Of `directors.id_number`'s four standing obligations, this table closes the
first. **Three remain open and are recorded in the column's own Postgres
comment**: reads restricted to compliance staff, every read audited as an
event, and a defined retention policy. Nothing reads the column at all today,
which is why they are open rather than violated.

### The document set follows residence, and stops there

Local (`NG`) buyers submit an **NIN only** — never a utility bill on top.
Diaspora buyers submit a passport and proof of address. Derived from the
country captured at registration, never asked again, and `buyer_type` is
**stored** on the record: it says what this buyer was actually required to
produce at the time, and recomputing it from a later change of residence would
rewrite history.

### The review endpoint is beyond the stated scope, deliberately

`POST /api/admin/kyc/{userId}/decision` was not in the slice's endpoint list,
and is built anyway: without it nothing can ever reach `approved`, so
reservations would be permanently unreachable and every per-document rejection
column would be dead. "Manual review for now" needs a way for a human to
review.

A rejection **must name the documents that failed**, and approves every other
document on the submission — the same cascade a tenant's verification decision
already applies. Resubmission then reopens only what failed.

`UNDER_REVIEW` exists in the enum (the frontend's union has it) but nothing
produces it yet — the `channel` precedent, not the `otp.purpose` one: a legal
value recording a real state a reviewer workflow will reach, rather than a
fabricated one.

## Wire-value divergences from the slice spec, resolved toward the frontend

The task spec and the frontend's existing contract disagreed twice, and the
contract won both times (it already exists; the spec was describing it from
memory):

- **`released`, not `CANCELLED`**, for a hold the buyer gave up.
- **`approved`, not `VERIFIED`**, and **`unsubmitted`, not `NOT_STARTED`**,
  for verification status.

Constants are named for the wire value in both cases, so the two spellings
cannot drift apart.

Also recorded rather than silently accommodated: `ReservationDto` returns
`estateId` where the frontend's `Reservation` says `listingId`, consistent
with this backend's own vocabulary and with `MarketplaceListingDto.id`.

## `audit_log_entries.actor_user_id` is nullable now: the system can act

Found by the expiry sweep failing, not by inspection. Every audit entry until
now came from a request, so "a human did this" was a safe assumption and
`NOT NULL` encoded it. A hold expiring is nobody's action — TX-5 says the
actor is the system, not the buyer — and the insert failed outright.

Changeset **055** drops the constraint. Both alternatives were worse: writing
the buyer's id would state in the permanent record that they released a plot
they never touched, which is exactly the misattribution an audit trail exists
to prevent and exactly what someone would be reading during a dispute; a
sentinel "system user" row would put a login-shaped record in `users` that
nobody can log in as and that every user listing would then have to exclude.

The read path renders a null actor as **"System"**, distinct from the
**"Unknown user"** it already rendered for a deleted account — those are
different facts.

**Rollback caveat**: restoring `NOT NULL` only succeeds while no
system-authored entry exists, and this is an append-only table, so after the
first sweep it cannot be rolled back without deleting rows. Inherent to the
change, not a defect in it.

## `IdentityApi` had no implementation, and `identity.dto` is not actually exposed

Two related things found while wiring `kyc`, both worth knowing before the
next module tries to read a user:

- **`IdentityApi` was declared but never implemented.** Injecting it anywhere
  would have failed at startup, not at compile time. `IdentityApiImpl` now
  exists; `kyc` is its first caller.
- **`identity.dto` is not a Modulith named interface**, so `UserDto`'s
  accessors cannot be called from another module — `ModularityTests` rejects
  it with "depends on non-exposed type". AGENTS.md's own package-structure
  section describes `dto/` as "public — safe to share", and **that is not what
  the build enforces**: only the module's base package is exposed. So
  `IdentityApi.findById` is public but unusable across a boundary.

The fix taken was the narrow one — `IdentityApi.countryOf(UUID)`, returning
the single field `kyc` needs. Settle the broader question (annotate the `dto`
packages with `@NamedInterface`, or keep cross-module surfaces to narrow
methods) before the next module wants a DTO from another one; don't discover
it again at the verification test.

## `PlotIntent` and `PlotPricing` moved to `common`

`checkout` needs both — the buyer's intent on a transaction, and the one
place a plot's price is computed. Duplicating the pricing formula so that
`inventory` and `checkout` each own a copy is precisely how two figures
eventually disagree about what a corner plot costs, and the disagreement is
money. `PlotPricing` is a pure function over `BigDecimal` with no entity or
repository, which is what makes it shared-kernel material rather than domain
logic leaking into `common`.

`AesGcmCipher` (in `common`) is new for the same reason: two modules now
encrypt a column at rest, and two hand-rolled copies of a cipher is how one of
them quietly ends up with a reused IV. **`TwoFaSecretConverter` was left on
its own copy** rather than refactored mid-slice — 2FA is shipped and working,
and rewriting its cipher to prove a tidiness point is not a trade this slice
needed. Converging them is worth doing in a change whose tests are about that.

## Reservations and transactions are buyer-owned, and `seller_tenant_id` says so

Both tables leave the inherited `tenantId` **null** and carry an explicit
`seller_tenant_id` instead — the same split `Branch` makes for a different
reason. The generic column means "this row is isolated to that tenant", and a
reservation belongs to a buyer who has no tenant at all; populating it would
be false, and would hide a buyer's own reservation from them the day a generic
policy is applied to the table.

Neither table is RLS-policied today (same reasoning as `kyc_records`). When
finance needs the seller's side of a transaction, that wants a scoped
projection, not a generic policy bolted on.

## "That plot is no longer available" deliberately conflates several cases

A refused reservation returns one `PLOT_NOT_AVAILABLE` whether the plot is
held, sold, deleted, non-existent, or on an estate that is no longer eligible.
Distinguishing them would let anyone probe which plot ids exist on estates
they cannot see, and which companies are currently suspended — the same
non-disclosure reasoning as the 404-not-403 on someone else's reservation, and
the silently-ignored `X-Branch-Id`.

The one exception is `KYC_REQUIRED`, which is specific on purpose: the
frontend has to route the buyer into verification rather than show them a dead
end. It reveals nothing about the estate.

## Nothing here allocates a plot

`TransactionStatus.PENDING_PAYMENT` is the only status this module writes. A
reservation holds a plot; it never sells one. Allocation follows finance
verification — a human — and `finance` does not exist. The enum declares the
frontend's full union so the contract is documented, but nothing in this
module can advance past pending, and
`aTransactionIsPendingAndThePlotIsHeldNeverSold` pins it.

## Full cost disclosure: the platform moves the sequence, it does not regulate

Derived from four real Nigerian allocation letters (August 2026). The claim
this feature makes is narrow, and every comment, response and document should
keep it narrow:

**Double King Estate**: ₦6,000,000 of land, plus ₦10,000 application,
₦300,000 setting-out, ₦3,500,000 infrastructure and ₦200,000 supervision — a
true commitment of **₦10,010,000, 67% above the advertised price**, with an
annual facility fee on top. **Top Rank Platinum City**: ₦4,500,000 of land
against **₦7,000,000 of infrastructure alone — 156% of the land price** — for
**₦12,610,000**, nearly three times what was advertised.

**Both companies disclosed every term. Both appear to operate legally.** The
failure is entirely one of **sequence**: every charge arrives in a letter
issued after the buyer has already committed money. So the platform does not
prevent fraud here — it moves disclosure to the moment it can still affect the
decision.

**Nothing caps, warns on, or judges an amount.** A 156% infrastructure fee
computes exactly like a 5% one. The platform discloses; it does not regulate,
and no future "reasonableness" check belongs in this code.

### Two new publication conditions, not one — and default terms deliberately not a third

"Please disclose your fees" is a policy a developer ignores. **"You cannot
list until you have" is enforceable**, and it is enforceable here only because
the conditions live in exactly one place — `marketplace_estate_eligibility`
(changeset 059) — which both the publish endpoint and the public feed read.
A refusal and an absence from the marketplace stay the same answer.

`feesDeclared` (FD-1) and `refundTermsDeclared` (RF-4) are **separate
booleans** on `EstateEligibility` beside the other five, not folded into
`eligible`, for the reason PB-3 established: the publish endpoint must name
the condition that actually failed. They fail independently and carry
distinct codes (`PUBLICATION_FEES_UNDECLARED`,
`PUBLICATION_REFUND_TERMS_UNDECLARED`).

**Default terms are deliberately not required.** DF-1's own condition is
"where installments are offered", and nothing on an estate records whether
they are — a payment plan is chosen per transaction, at checkout. Requiring
them unconditionally would enforce more than the story asked; conditioning on
a fact that does not exist would be guesswork. Revisit if an estate ever
declares which plans it offers.

**This was caught by the full suite, not by inspection.** The first
implementation checked fees only while the refusal message named fees, refund
terms *and* default terms — a message describing a rule the code did not
enforce. Every pre-existing fixture that publishes an estate went red at the
same time, which is the feature working: `MarketplaceIT` and
`ReservationCheckoutUnderRlsIT` now declare an explicitly empty schedule plus
refund terms before publishing.

**Declaring an empty schedule counts; silence does not.** An estate with
genuinely no extra charges must be able to list, but by saying so. That is why
the condition reads `estates.fees_declared_at IS NOT NULL` rather than
counting fee rows — "nothing to declare" and "nobody asked" are different
facts, and only one of them is a disclosure.

### Grandfathering, and why not a backfill

Requiring a schedule retroactively would have **delisted every live listing**,
which is principle over usefulness. Changeset 056 sets
`estates.fees_declaration_exempt = true`, once, for estates already published.

Backfilling `fees_declared_at` for them instead was rejected: it would record
a declaration that never happened, which is exactly the fabrication this
feature exists to prevent. A grandfathered estate's `costDisclosure` is
**null**, not an empty schedule — an empty schedule would tell a buyer "no
extra charges" on the authority of nobody.

Same shape as the `state` precedent: enforced at the API for new writes,
nullable in the database for rows that predate it. **TODO** in the column
comment: backfill real schedules, clear the flag, drop the column and its term
in the view.

### Recurring charges are excluded from total commitment — the letters' own arithmetic says so

Not stated in the task spec, and required by its own expected figures.
Double King's ₦10,010,000 and Top Rank's ₦12,610,000 are both **land plus
one-off fees**; each letter names its annual facility fee separately. Folding
one year of a perpetual charge into a purchase total would be arbitrary (why
one year?) and would misstate both the purchase and the obligation.

So `DueTrigger.ANNUAL` is reported in `recurringFees`, outside
`totalCommitment`. Genuinely avoidable charges sit in `optionalFees`, also
outside — but note FD-3 means very little lands there: **a fee whose stated
condition is itself mandatory is mandatory.** Both letters charge setting-out
and supervision only "if you build", and both also *require* building (Double
King clause 1 mandates a 4-bedroom terrace duplex, Top Rank clause 2 a
2-bedroom). Reading those as conditional is the natural reading of the
documents and the wrong one.

### Total commitment is per tier, and sometimes deliberately not a total

An estate's tiers carry different prices, so one estate-level figure would
apply to nobody. `TierCommitmentDto` therefore hangs off each tier, with:

- **`totalCommitmentIfCorner`** — a corner plot really does cost more, and a
  buyer choosing one deserves the real number rather than a percentage to
  apply themselves. Null when the estate charges no premium.
- **A range** (`min`/`max`) wherever any contributing fee is a range, and
  **never a midpoint** — a midpoint is a figure nobody quoted and nobody is
  bound by.
- **`totalExcludesOtherCurrencyFees`** when a declared fee is in a different
  currency from the tier. Those are **never summed in** — adding a dollar fee
  to a naira price produces a number wrong in every currency — but the flag
  makes the omission visible rather than silent, and the charge still appears
  in the breakdown.

### Percentages are not disclosure

`CommitmentCalculator` (in `common`, beside `PlotPricing`, for the same
reason: one sum, not two that drift) computes everything in naira.
*"20% administrative charge"* is abstract; *"you receive ₦3,600,000 — a loss
of ₦910,000 — after about 90 days"* is something a person reacts to. Same
discipline the upgrade engine already applies to its signed delta.

Non-refundable fees are **reported as part of the loss**, never netted quietly
out of the refund: Double King's ₦10,000 application fee is explicitly one.

**The refund is computed against the headline price**, which is a limitation
stated rather than hidden. RF-3 — a refund against what has actually been paid
— needs payment records, and nothing tracks payments. It belongs to `finance`.
The published figure is therefore a **maximum exposure**, and
`ExitCostsDto`'s own description says so.

### DF-3: the trap is only visible when both exits are on one screen

Top Rank's buyer faces penalties escalating to 20% for falling behind, and a
20% deduction for withdrawing. **Neither clause is hidden individually** — a
buyer can read both and still not notice there is no affordable way out.
`ExitCostsDto` puts them together, and `bothPathsCarryACost` is arithmetic
(penalties exist *and* withdrawal forfeits something), not an opinion about
fairness.

Exit costs are computed against the **cheapest tier**, named in the response
(`basisTierId`, `basisLandPrice`) so the figures are never mistaken for a
particular buyer's position.

### What is declared but deliberately inert

- **`development_deadline_months`** (DF-4) is stored and published, and
  **nothing tracks the clock**. Surfacing it needs an attention surface
  (which does not exist backend-side) and a second scheduled job after the
  reservation sweeper. Its own slice.
- **`transfer_requires_consent`** (DF-5) is disclosed at purchase and
  **not enforced** — there is no resale module. The story claimed resale
  "assumes an owner can list freely"; **it does not.** The frontend's
  `ResaleTransferStage` already begins at `developer_approval` with an
  explicit decline path. The real problem is that consent lands at *transfer*
  time, after the seller has listed, negotiated, accepted an offer and sent a
  buyer through KYC — this epic's own sequence failure, one layer up. When
  resale ships, surface the restriction at listing time, not only at the end.

### Versioned, never overwritten

Every declaration writes a new version (`estate_fees.version`, and a new row
for refund/default terms); the previous one stays. A schedule that can be
quietly revised after a buyer has seen it is not a disclosure, and FD-5's
acknowledgement — **not built; it gates the transaction, not the hold, and
belongs with checkout where the commitment actually forms** — has to be able
to name the version it refers to.

Note the three tables version **independently**. An acknowledgement will need
to capture all three versions, not one number.

### Reads use `portal.estates.view`, writes `portal.estates.manage`

A deliberate deviation from the task spec, which put every disclosure route
behind `manage`. Reading the fee schedule is what a sales manager does all day
— they are the ones buyers ask — while defining it is what two roles do. That
split is exactly why the two slugs exist.

### The disclosure ships with its RLS policies, in the same changeset

A developer's fee schedule, refund policy and penalty terms are their
commercial position. Changeset 058 policies all four tables in the same slice
that adds a read endpoint — deliberately not deferred to "whoever reads them
next", which is the mistake inventory slice 3 had to correct after RLS was
listed out of scope twice while nothing could read the rows back.

## `./mvnw verify` used to migrate the developer's own database

`LandvaultApplicationTests` was a bare `@SpringBootTest` with no
Testcontainers, so it resolved `spring.datasource.*` to whatever the
developer's configuration pointed at — their real local Postgres. Every test
run applied the full changelog to it.

**Found the expensive way**: an unreleased changeset (059) reached a live
database without anyone starting the app, purely because the suite had run.
Editing that changeset afterwards — legitimate, since nobody believed it had
been applied anywhere — then failed checksum validation against a database it
was never meant to touch. Reconciled by deleting that one
`databasechangelog` row and letting the idempotent `CREATE OR REPLACE VIEW`
re-run, the same technique changeset 047 needed.

The class now owns a container, which makes it the cheapest real migration
test in the suite as a side effect: a fresh database on every build means the
whole changelog runs from nothing, so a changeset that only works against an
already-migrated database fails immediately.

**The rule**: a `@SpringBootTest` that starts a real context needs a
container, always. `ModularityTests` is the one exception and is safe — it
only names `@SpringBootTest` in its javadoc to say it deliberately isn't one,
and starts no context at all.

**A checking gotcha worth carrying**: verifying "has this changeset run?" with
`WHERE id LIKE '05[6-9]%'` silently returns nothing, because SQL `LIKE` has no
character classes — that is `SIMILAR TO` or `~`. It reads as a clean "not
applied" and is simply a query that matches nothing. Use `~ '^05[6-9]'`.
