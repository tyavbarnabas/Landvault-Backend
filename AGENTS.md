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

**`TenancyExceptionHandler` now reads the constraint name** (2026-10-06,
raised by the frontend): `uq_organizations_rc_number` →
`RC_NUMBER_ALREADY_REGISTERED`; `idx_users_email_lower` or
`uq_staff_invitations_open_email` (the first director's invitation, since
SI-3) → `EMAIL_ALREADY_REGISTERED`; anything else → `DUPLICATE_RECORD` naming
the constraint. It used to call every clash an RC duplicate, which sent the
tenant wizard back to the RC field for the wrong reason. Same pattern as
`InventoryExceptionHandler`; `TenancyExceptionHandlerTest` pins it (red with
the old behaviour).

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
already-*rotated* token (`replaced_by` set) is ever presented again — narrowed
from "any revoked token" by the cookie slice, see "Only a rotated token is a
theft signal" below — that's a signal the
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

**Amended by the cookie slice**: inside a short grace window, losing that
race (or presenting a just-rotated token) is no longer theft — see "The
refresh token lives in an `HttpOnly` cookie" below.

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

## The refresh token lives in an `HttpOnly` cookie, never a body

**The refresh flow had never worked before this.** The frontend posted to
`/api/auth/refresh` with no body expecting a cookie; the backend required
`{ refreshToken }` in the body; the frontend never stored what login
returned. Every refresh failed, so every session silently ended at the
15-minute access-token expiry.

**Why a cookie rather than browser storage.** A refresh token is a 30-day
credential. In `localStorage` any script on the page — a compromised
dependency, an analytics tag, an XSS flaw — can read it and use it from its
own machine for a month. In an `HttpOnly` cookie, JavaScript cannot read it
at all; XSS can still act as the user while the page is open, but nothing
persistent is stolen. The access token stays in the body (short-lived, a
different trade-off, out of scope).

- Set by **login, `/2fa/verify`, register and refresh**; `HttpOnly`,
  `SameSite=Strict`, `Path=/api/auth`, `Max-Age` = the refresh TTL.
  **It appears in no response body** — `AuthResponse`/`RefreshResponse` have
  no such field, and `RefreshTokenCookieIT` asserts on the raw JSON.
  The service hands the raw token to the controller through `IssuedSession`/
  `RefreshResult`, internal records that are never serialised.
- Attributes are configuration (`app.refresh-token.*`). **`cookie-secure` is
  `true` in the base config and `false` only in `application-dev.yml`** —
  Safari doesn't reliably treat `http://localhost` as secure, so dev turns it
  off explicitly rather than relying on the browser. A deployment that forgets
  to configure it gets the safe value.
- `/api/auth/refresh` takes **no body** and reads the cookie.
  `REFRESH_TOKEN_MISSING` (no cookie) and `INVALID_REFRESH_TOKEN` are distinct
  401s so the frontend can tell "never signed in" from "session ended".
- **Only the transport changed.** Hashing, rotation, `replaced_by` and the
  family revoke are exactly as before, apart from the grace window below.

### `SameSite=Strict`, same-site deployment assumed — and why that is not the whole CSRF story

The cookie is only ever sent by the app's own fetch calls, so `Lax` gains
nothing. **The deployment assumption is that frontend and API share a site**
(`landvault.com` + `api.landvault.com`, or one origin). Note that *site* is
the registrable domain, not the host: a Vercel frontend on a custom
`app.landvault.com` is same-site; one on `*.vercel.app` is not.

**`SameSite` alone is not a CSRF defence here, because every subdomain is the
same site.** A compromised `blog.landvault.com` on a hosted CMS, or any future
subdomain serving user content, passes it. So refresh and logout also run
**`OriginGuard`**: a request whose `Origin` is not in `app.cors.allowed-origins`
(the list CORS uses) gets 403 `ORIGIN_NOT_ALLOWED`.

- **Absent `Origin` is allowed** — browsers always send it on a cross-site
  POST, so its absence means a non-browser client (curl, Postman, a test),
  which cannot carry a victim's cookie. Present-but-unlisted is refused,
  including the literal `null` that sandboxed pages send.
- **Why an Origin check rather than CSRF tokens**: the realistic impact of a
  forged refresh is a rotated cookie in the victim's own browser (CORS stops
  the attacker reading the new access token), and of a forged logout a
  forced sign-out. A token mechanism would be disproportionate.
- **What it adds over CORS, verified rather than assumed**: Spring's CORS
  processor already rejects foreign origins server-side, so a foreign-origin
  test passes with the guard disabled. It treats the server's own origin as
  same-origin and lets it through, though. Only
  `aSameOriginRequestThatIsNotOnTheAllowedListIsRefusedByTheOriginCheckItself`
  goes red without the guard. The guard also means CSRF protection doesn't
  depend on the CORS config staying as strict as it is.
- **This makes the deployment question a config value.** If the frontend
  ever moves cross-site: `cookie-same-site: None` (which requires
  `cookie-secure: true`) and add that origin to the allowed list. The Origin
  check already covers CSRF in that case.
- **Consequence for a one-origin deployment**: the allowed list must then
  contain the API's own public origin. The same applies to Swagger UI, which
  is served by the app itself: `application-dev.yml` appends
  `http://localhost:${SERVER_PORT}` to the allowed origins so Swagger can call
  refresh and logout. Dev only — the base config never lists the API's own
  origin. Open Swagger at `localhost`, not `127.0.0.1`, or the Origin won't
  match.

### The rotation grace window: two tabs are not a thief

With a cookie, two tabs whose access tokens expire together send **the same
cookie**. One rotates it; the other presents an already-rotated token, which
theft detection read as a stolen copy and revoked every session. Signed out of
everything for having two tabs open, in ordinary use. The frontend's
single-flight refresh is per tab and cannot help; `navigator.locks` fixes the
common case client-side. The server side is **`app.refresh-token.reuse-grace`
(10s)**, which also covers a retry after a dropped response, which no
client-side lock can prevent.

**"Return the current token" was the spec and is impossible**: only hashes are
stored, and storing the raw value to allow it would undo the hashing. Instead
a qualifying request gets a **sibling** — a fresh refresh token and access
token, with the chain left exactly as the winner wrote it. The browser keeps
whichever `Set-Cookie` lands last; both are valid, and the other expires
unused. This deliberately loosens theft detection by the window's width: a
thief replaying within those seconds also gets a token. Auth0's reuse interval
and Okta's grace period accept the same trade-off. **Widening it widens the
replay window; it is a security parameter.**

**It applies only to tokens retired *by rotation*, and that condition is the
important part.** `revoked_at` is set five ways: rotation, family revoke,
logout, password reset, change-password. A check of "revoked in the last few
seconds" alone would let someone replay a token straight after the user
logged out or reset their password. So `countRotatedSinceWithLiveSuccessor`
requires all three: the token has a successor (`replaced_by`), was retired
within the window, **and that successor is still live**. Logout/reset/change
revoke the successor, so the old token stops qualifying. Proven red by dropping
the live-successor condition (both replay tests return 200).

It runs on both routes a duplicate arrives by — after the winner committed
(already revoked) and at the same instant (`rotateIfActive` returns 0, then
the grace query reads committed state as a fresh statement). The tenant-status
gate still runs before anything is written on the grace path.

### Logout ends one session

`POST /api/auth/logout` is public (it must work once the access token has
expired), Origin-checked, always 204, and always clears the cookie with the
same attributes it was set with (a browser only removes a matching cookie).
It revokes **only the presented token**, so a phone stays signed in when a
laptop logs out. Revoked with an atomic `UPDATE … WHERE revoked_at IS NULL`,
not load-then-save — Hibernate writes every column, so a load-then-save
racing a refresh would overwrite the rotation's `replaced_by` with null.

**"Family" means every one of the user's sessions**, not one device's chain:
`revokeTokenFamily` revokes all their live tokens. Real theft on one device
signs out all of them — correct, and worth knowing before reading "family" as
"device".

### Only a rotated token is a theft signal

Theft detection originally fired on **any** revoked token presented again.
That was wrong, and it surfaced when change-password started re-issuing the
current device's cookie (below): the user's phone, still holding its now-revoked
cookie, refreshes, "theft" revokes the family — including the fresh session on
the device that just changed the password. The same was already true after a
password reset (a phone refreshing killed every post-reset login) and after
logout.

**The rule now**: only a token retired **by rotation** (`replaced_by` set) and
presented outside the grace window revokes the family. That is the actual
signal — someone used the token after its owner had moved on. A token ended on
purpose (logout, reset, change-password, or an earlier family revoke; all leave
`replaced_by` null) just gets `INVALID_REFRESH_TOKEN`: a dead session, nobody
else signed out. The lost-race branch applies the same rule, reading
`replaced_by` fresh, since a concurrent logout can be what beat it. Decided
with the user; real theft is caught exactly as before, and what is given up —
treating a stolen copy of an already-dead token as an alarm — cost legitimate
users their sessions and protected nothing, since the token was already dead.
Pinned by `changingThePasswordKeepsThisDeviceSignedInAndEndsTheOthers` and
`logoutEndsOneSessionAndLeavesTheOtherDeviceSignedIn` (checks the logged-out
laptop first), both proven red without the rule.

### Refresh returns the user, and checks the account like login does

`/api/auth/refresh` returns `{ user, token }` — the same user object as
login, built by the same method (`buildUserResponse`), read fresh on every call.
**This is how the frontend restores a session on page load**: one call, and
`REFRESH_TOKEN_MISSING` means nobody is signed in. Storing the user
client-side was rejected (stale permissions/KYC/2FA flags, and one more thing
a script can read); a fuller `/api/me` was rejected (needs a live access token,
so an expired one costs three round trips).

**Refresh also refuses `SUSPENDED`/`DEACTIVATED` accounts**
(`ACCOUNT_SUSPENDED`/`ACCOUNT_DEACTIVATED`, 403), before any write. Found
while adding the user to the response: only login ever checked account status,
so a suspended user already signed in could keep renewing for the full refresh
TTL. Now bounded to the access token's 15 minutes, same as tenant suspension.

### Change-password keeps this device signed in

It revokes every refresh token, **then issues a fresh cookie for the calling
device**. Previously the current tab signed itself out at its next refresh,
while the response said only *other* sessions would. The caller has just
proved the current password on a live session — what login would ask for — so
re-issuing is safe. Bearer-authenticated, so no Origin check is needed.

### `device`, `user_agent`, `ip_address`

`user_agent` is now populated at issue time (read from the request context,
**truncated to the column's 255** so a long browser string can't turn a login
into a 500 — tested). `device` stays null (it would mean parsing that string),
and so does `ip_address` (personal data under NDPR; collecting it is its own
decision). A session list in Settings has a browser string to show, not a
device name.

### Cleanup: `RefreshTokenCleanupJob`

Rotation writes a row per refresh and nothing removed them. Hourly, rows
expired more than **`cleanup-retention` (30d)** ago are hard-deleted — kept
that long so a recent theft can still be investigated. These are dead
credentials, not domain history, so the never-hard-delete rule doesn't apply.

- **No platform scope**, unlike the reservation sweeper: `refresh_tokens`
  carries no RLS policy, so there is nothing for a scope to unlock.
- **The `replaced_by` self-FK is protected in the query, not by an ordering
  assumption.** "A successor always expires after its predecessor" is true
  only while the TTL never shrinks; after a shortening a live predecessor can
  point at an expired successor, and a plain cutoff delete then fails on the
  FK every run, forever. The `DELETE` carries `NOT EXISTS (a surviving row
  whose replaced_by is this one)`, and is one statement, so rows deleted
  together may reference each other. Proven red by removing the `NOT EXISTS`
  (FK violation). Deleting old rows cannot weaken theft detection: that reads
  the presented token's own row, and a deleted one is long expired anyway.

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
`CreateEstateRequest`), which is step one. **Step two is built — see "SB-1:
an estate's boundary must sit inside its state" below** — against real state
polygons rather than bounding boxes.

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

## Tenant self-service: decisions taken before building (2026-10-05)

From the TB/SI/EB stories. Two of their claims were wrong and are recorded so
nobody builds on them: **`staff_invitations` did not exist**, and **there was no
branch API for anyone** — Super Admin included; tests insert branches in SQL.

Decided with the user:
- **An invitation to an email that already has an account is refused**, and an
  accepted invitation creates a **fresh user holding only the invited role** —
  never `buyer`. A buyer who also held a staff role would silently lose their
  branch wall (see "A branch-scoped staff user with a leftover `buyer` role")
  and buyers must never be tenant-scoped. Staff use a work email.
- **Role scoping is data**: a column on `roles` — `executive_director`
  company-wide only, `branch_manager` must have a branch, the other tenant roles
  either.
- **Only company-wide roles invite**, via a new `portal.staff.invite`; branch
  managers don't invite at all in v1. Branches are created by holders of a new
  `portal.branches.manage`, company-wide only — a branch manager creating a
  sibling branch would carve out visibility for themselves.
- **"Double King staff create estates" means a surveyor scoped to Double
  King** — `branch_manager` does not hold `portal.estates.manage`.
- Invitations: a separate sender beside `OtpDeliveryService` (that interface is
  shaped for a code, not a link); the token travels after `#` in the link so it
  never reaches a server log or a referrer; refused while the tenant isn't
  active; the first Executive Director at tenant creation gets an invitation
  instead of an unusable random password.

### EB-1: an estate may belong to the company, with no branch

An organisation-wide caller who leaves `branchId` out now creates a
**company-level estate** (`branch_id` null) instead of a 400. A single-office
developer has no branch to name, and auto-creating a "Head Office" was
rejected as a fiction — the tenancy model already treats "belongs to the
organisation" as legitimate everywhere else. A branch-scoped caller's estates
still always go to their own branch. Nothing downstream assumed a branch: the
marketplace view `LEFT JOIN`s branches, and children copy the estate's
`branch_id`, null included.

### EB-2: company-level estates are visible to branch staff, and read-only for them

**Visible**: changeset 044's branch policy has `branch_id IS NULL`, so a branch
manager sees their branch's estates and the company's, never another branch's —
verified, the first time an estate exercised that clause.

**Read-only — and the database will not do this for you.** The same clause is
in `WITH CHECK`, so RLS permits branch-scoped *writes* to company-level rows.
`EstateWriteAccess.requireWritable` is the application rule
(`ESTATE_READ_ONLY_FOR_BRANCH`, 403), applied in every inventory write path:
the write lookups in `PortalEstateService` and `InventoryEditService`, the
three disclosure declarations, and plot import. **Proven**: without it, a
branch-scoped surveyor added a price tier to a company estate (201). **Any new
inventory write must go through it.**

**The branch switcher**: an Executive Director narrowed to one branch with
`X-Branch-Id` is acting inside that branch, so company-level estates are
read-only to them until they widen again — the lens is a lens.

### TB-1..TB-3: a tenant manages its own branches

`GET/POST /api/portal/branches`, `PUT /api/portal/branches/{id}`
(`PortalBranchService`, tenancy). **The first branch API there has been, for
anyone** — before this, branches only existed by SQL.

- **The company is always the caller's own**, from `TenantContext`, never the
  request; another company's branch id is a 404.
- **Create and rename are company-wide only, twice over.** The new
  `portal.branches.manage` (changeset 065) is granted to `executive_director`
  alone, **and** the service refuses any caller whose current scope is one
  branch (`BRANCHES_REQUIRE_COMPANY_WIDE_SCOPE`, 403) — including an Executive
  Director narrowed with `X-Branch-Id`. The second check is not redundant:
  changeset 021's `WITH CHECK` on `branches` deliberately omits the branch
  match (a new branch can't equal an existing branch id), so **the database
  lets a branch-scoped user insert a sibling branch**. Proven: without the
  service check, a branch-scoped holder of the permission got 201. Same lesson
  as EB-2 — RLS walls reads; some writes need an application rule.
- **Listing** needs `portal.branches.manage` or `portal.estates.view`;
  RLS shows company-wide staff every branch and branch-scoped staff only their
  own. A company with no branches gets an empty list — never an invented
  "Head Office".
- **Names are unique within the company, ignoring case.** The database
  constraint (`uq_branches_organization_id_name`) is case-sensitive, so the
  case-insensitive check is the service's (proven red without it); the
  constraint remains the backstop for a race. Its own exception handler —
  `TenancyExceptionHandler` reports every conflict as a duplicate RC number.
- Audit: `tenant.branch_created`, `tenant.branch_updated` (field-by-field old
  → new); an update that changes nothing records nothing.
- **Not exposed: `parent_branch_id` and `manager_user_id`.** The manager comes
  with invitations (SI). A parent branch is deliberately not offered: the
  branch wall compares exact branch ids, so a parent branch's manager would not
  see its sub-branches' estates — offering hierarchy would promise visibility
  that doesn't exist. No delete route either (not in the stories; a branch
  with estates needs a decision about them first).

### A branch's office (changeset 066)

A branch was only a name — enough for the access wall, but not for a buyer
who wants to visit or call the people they'd deal with, nor for state-level
registration (a Lagos branch may need its own LASRERA entry). Decided with the
user: branches now carry an optional **street, city, state, phone and email**.

- **All optional, never invented.** Existing branches have none; a missing
  office is `null`, and the portal never fills it from the company's own
  address.
- **State is standardised exactly like estates** (`state` canonical name,
  `state_code` ISO). The resolver moved to **`common.NigerianStates`** so
  tenancy can use it: tenancy → inventory would be a cycle, and the lookup is
  reference data, not domain logic. SB-1's `StateBoundaryService` delegates to
  it; behaviour unchanged.
- `PUT /api/portal/branches/{id}` is now a general update (left out =
  unchanged, blank clears, the name can't be cleared) rather than rename-only.
- **The office is published on the marketplace seller card**
  (`SellerDto.office`, from new columns appended to `marketplace_listings`).
  Adding a column to a `marketplace_*` view is publishing it to the internet —
  deliberate here, since these are business contact details entered to be
  shown, and the API docs tell developers so. `office` is `null`, not an
  object of nulls, when the estate has no branch or the branch has no details.
  The view's rollback drops and recreates it, so it re-grants SELECT-only.
- **Not added: map coordinates** — nothing shows offices on a map.

### SI-1..SI-7: staff invitations (changeset 067)

How tenant staff get an account at all. `POST/GET /api/portal/staff/invitations`,
`POST .../{id}/revoke`, `POST .../{id}/resend` (all `portal.staff.invite`), and
the public `POST /api/auth/invitations/preview` / `accept`. Decided with the
user before building:

- **An email that already has an account is refused** (`EMAIL_HAS_ACCOUNT`,
  409), checked at invite *and* at accept. Merging a buyer account into a
  staff one would hand a branch wall to someone holding a `buyer` role — the
  exact org-wide leak recorded under "A branch-scoped staff user with a
  leftover `buyer` role". An accepted invitation creates a user holding
  **only the invited role**, never `buyer`; the IT asserts the full role list.
- **`roles.scope`** (`COMPANY` / `BRANCH` / `EITHER`, null = not invitable)
  is data, not a hardcoded list: `executive_director` COMPANY,
  `branch_manager` BRANCH, other tenant roles EITHER, `buyer` and platform
  roles null (`ROLE_NOT_INVITABLE`). A branch is required, forbidden or
  optional accordingly (`ROLE_SCOPE_MISMATCH`), and must be the caller's own
  (`BRANCH_NOT_FOUND`).
- **Who invites**: `portal.staff.invite` (executive_director only) **and** a
  company-wide current scope (`INVITATIONS_REQUIRE_COMPANY_WIDE_SCOPE`, 403 —
  an ED narrowed with `X-Branch-Id` is refused too). Branch managers don't
  invite. **SI-4, never grant what you don't hold**: the role's permissions
  must all be in the caller's own authorities (`CANNOT_GRANT_ROLE`), so a
  future role carrying an `admin.*` slug can't be minted from the portal.
- **The token**: 32 random bytes, stored SHA-256 only (same reasoning as
  `otp_codes`), single-use, 72h. It goes **after `#`** in the link
  (`/accept-invitation#token=...`) — a fragment never reaches a server, so it
  never lands in an access log or a `Referer`. Unknown, expired, revoked and
  used links all get one `INVITATION_INVALID` with the same body.
- **Resend issues a new token** (the old link dies — never two live links),
  resets expiry, and is limited: 2 minutes apart, 5 sends total (429s). Same
  inbox-flooding reasoning as password reset.
- **Suspended/offboarded tenants** can neither invite nor have an invitation
  accepted (`TENANT_NOT_ACTIVE`) — accepting would be a login-shaped door
  around the login-time tenant check.
- **Accept signs the person in** (tokens + refresh cookie, like login), with
  `emailVerifiedAt` set — receiving the link proves the mailbox.
- **Names are snapshotted** onto the invitation (company, branch, role) so
  the email and preview need no cross-module read before any scope exists.
- **`staff_invitations` is not RLS-policied**, like `otp_codes`: preview and
  accept run before any scope exists. Rows are reached by token hash, or by
  `id` *and* the caller's `tenant_id` from `TenantContext` (another company's
  invitation is a 404). A tenant-facing policy would only block accept.
- **Delivery**: `InvitationDeliveryService`, separate from
  `OtpDeliveryService` (a different message, not a code). Email by default;
  `LoggingInvitationDeliveryService` (`landvault.invitations.delivery=log`) is
  **test-only** and is the one place an invitation link is logged — selecting
  it anywhere real writes account-takeover links into the log.
- Audit: `staff.invited`, `staff.invitation_resent`,
  `staff.invitation_revoked`, `staff.invitation_accepted`.

**The first Executive Director is invited too.** Tenant creation used to
create the ED's account with a random password nobody received (see "three
independent reasons" above — the reason nobody could ever log in as one).
`TenantStaffAccountListener` now creates an **invitation** instead, in the
same transaction, still via the synchronous `@EventListener`
(`TenantStaffAccountRequested` gained the organisation name and the
requesting admin's id for the snapshot and `invited_by`). An email that
already has an account still fails tenant creation with
`EMAIL_ALREADY_REGISTERED`. No `users` row exists until acceptance.

**Proven red** by breaking each guard: the permission-subset check (201
instead of 403), revoked-still-usable, the account-collision check, the
company-wide check, and expiry.

**Not built**: listing/removing *accepted* staff, changing a staff member's
role, and the frontend accept page. The `invited_by` FK means an inviting
admin's row can't be hard-deleted — fine, nothing hard-deletes users.

### Invitation requests from a branch (changeset 068)

A branch manager can't invite, but can **ask**: `POST
/api/portal/staff/invitations/requests` (new `portal.staff.request`), and
head office decides with `POST .../{id}/approve` or `.../{id}/reject`
(`portal.staff.invite`, company-wide). The requester can withdraw with
`.../{id}/cancel`. Decided with the user: the Executive Director approves,
the requester may cancel, requests lapse after **14 days**
(`landvault.invitations.request-ttl`), and approvers are emailed as well as
seeing the list.

- **A request is an invitation row with no link.** `token_hash` and
  `last_sent_at` stay null until approval; a CHECK
  (`chk_staff_invitations_link_only_when_approved`) makes a link before
  approval impossible even for code that tries — tested. Approval issues the
  ordinary invitation: fresh token, 72-hour clock, normal email.
- **The branch is always the requester's own** (from `TenantContext`; any
  other `branchId` is `ROLE_SCOPE_MISMATCH`), company-wide roles can't be
  requested, and SI-4 applies to the **approver, not the requester**: asking
  grants nothing, so a branch manager may ask for a `surveyor_project_manager`
  even though they don't hold `portal.estates.manage`. The grant happens at
  approval, and that is where the check runs. (Built requester-checked first;
  relaxed with the user once it meant branches couldn't ask for the surveyor
  that "Double King staff create estates" needs.)
- **Approval re-checks** that the approver holds the role's permissions, that
  the tenant is active, and that no account has appeared for the email while
  the request waited. A lapsed request can't be approved
  (`INVITATION_REQUEST_EXPIRED`) but can be rejected to close it.
- **`executive_director` also holds `portal.staff.request`**, never to use it:
  `branch_manager` now carries that permission, so SI-4 would otherwise stop
  the director inviting a branch manager at all (the full suite caught this).
  To stop a director narrowed with `X-Branch-Id` from requesting and then
  approving their own request, **anyone holding `portal.staff.invite` is
  refused at `/requests`** (`INVITATION_REQUESTS_REQUIRE_BRANCH_SCOPE`).
- **Cancel is the requester's only** — anyone else's request is a 404. A
  rejection needs a reason, which the branch manager sees; rejected and
  cancelled requests are history, so the same email can be requested again
  (the open-email unique index now also excludes `rejected_at`).
- **The list is scoped**: company-wide callers see everything, a
  branch-scoped caller sees only invitations into their branch. The table
  isn't RLS-policied, so this is the service's filter, by `scoped_branch_id`.
- **Approver email**: every active, company-wide Executive Director of the
  tenant, pointing at `landvault.invitations.review-url`. It carries no
  credential. No active approver → the request still stands and is logged.
- New statuses on `StaffInvitationDto`: `awaiting_approval`, `rejected`
  (plus `approvalRequired`, `requestedBy`, `approvedAt`, `rejectedAt`,
  `rejectionReason`). Audit: `staff.invitation_requested`,
  `staff.invitation_approved`, `staff.invitation_rejected`,
  `staff.invitation_request_cancelled`.

Proven red by removing each guard: requester-only cancel, own-branch only,
the no-self-approval check, lapse, and the branch-scoped list.

### Managing staff: list, change role, deactivate, reactivate

`GET /api/portal/staff` (`portal.staff.invite` or `portal.staff.request`),
`PUT /api/portal/staff/{userId}/role`, `POST .../{userId}/deactivate`
`{ reason }` and `POST .../{userId}/reactivate` (`portal.staff.invite`,
company-wide). `PortalStaffService`.

- **The company check is the only wall.** `users` carries no RLS policy, so
  `PortalStaffService.manageable` filtering on the caller's own `tenant_id` is
  what stops another company's director managing your staff — proven: without
  it, that was a 200. Another company's user is a 404, never a 403.
- **A role change replaces every assignment with one**, under exactly the
  invitation rules (`StaffRoleRules`, now shared by both services so they
  can't drift). Old assignments are **hard-deleted**, not soft: the unique
  constraint on `(user_id, role_id, scoped_branch_id)` counts soft-deleted
  rows, so a soft delete would make a role impossible to give back. The audit
  entry (`staff.role_changed`, old → new) is the history. This is also **the
  fix for "a leftover `buyer` role silently loses the wall"**: changing the
  person's role removes the buyer assignment.
- **SI-4 both ways**: the caller must hold the new role's permissions *and*
  every permission the person already holds (`CANNOT_MANAGE_STAFF_MEMBER`) — a
  director can't be demoted by someone weaker. A leftover `buyer` role is
  ignored for that comparison (its `client.*` slugs aren't power over the
  company, and replacing it is the point).
- **Never yourself** (`CANNOT_MANAGE_YOURSELF`), and **never the last active
  company-wide Executive Director** (`LAST_EXECUTIVE_DIRECTOR`) — a company
  must always keep someone who can manage it.
- **Sessions end on a role change or deactivation** (refresh tokens revoked).
  An already-issued access token rides out its 15 minutes, as everywhere
  else — documented on the route, never described as instant.
- Deactivation sets `DEACTIVATED` (login and refresh refuse it);
  reactivation is `DEACTIVATED → ACTIVE` only, role untouched. Audit:
  `staff.deactivated` (with the reason), `staff.reactivated`.

Proven red by removing each guard: self, other company, outranking, last
director, the branch-scoped list, and ending sessions.

### Roles: seeded today, company-made later — and permissions are always seeded

`GET /api/portal/roles` (`portal.staff.invite` or `portal.staff.request`)
lists every role a tenant may assign — `{ code, name, description, scope,
permissions, canGrant, custom }` — so the portal never hard-codes them.
`canGrant` is whether the *caller* holds every permission the role carries
(the SI-4 rule, computed by the same `StaffRoleRules` that enforces it);
a branch manager may still *request* a role they can't grant.

**Decided with the user (2026-10-06): companies should eventually create
their own roles, but not yet.** Two layers, deliberately different:

- **Permissions are always seeded.** A slug means something only because code
  checks it; a user-created `portal.payments.approve` that nothing checks
  would look like access control and do nothing — the same fabrication the
  `otp_codes.purpose` CHECK refuses.
- **Roles are just bundles of permissions**, so company-made roles are safe
  in principle. Not built now because only five `portal.*` permissions exist,
  so a custom role could barely differ from a seeded one. Build it with the
  first module (finance, sales, documents) that adds meaningful permissions.
  The intended shape: a tenant-scoped role (`roles` gains a nullable
  `tenant_id`, RLS-policied), a `portal.roles.manage` permission for the
  Executive Director, `portal.*` permissions only, SI-4 on the role's
  contents, no deleting a role someone holds, edits take effect at holders'
  next sign-in, all audited. `custom` on the DTO (from `roles.system_role`)
  is already there for it.

### Two loose ends settled

- **Staff log in with `"role": "client"` — deliberately, not a gap.** The
  frontend's `authService.ts` types `role` as `"client" | "super_admin"` and
  gates every portal menu on `permissions`; its own comments say tenant staff
  arrive as `"client"`. Don't add a staff role string without a frontend
  change.
- **A completed password reset verifies the email.** Using a code delivered
  to the address proves the mailbox, so a `PENDING_VERIFICATION` account
  becomes `ACTIVE` (and `email_verified_at` is set); `SUSPENDED`/`DEACTIVATED`
  are never touched. This is how accounts created before invitations existed
  (tenant creation's old random-password directors) get in: forgot-password,
  then reset — they then also count as approvers. Proven red.

## SB-1: an estate's boundary must sit inside its state

The coordinate-swap gap above, closed. Changeset **064**; `StateBoundaryService`.

**The data**: `nigerian_states` — the 36 states and the FCT as polygons, from
**GRID3's operational state boundaries via geoBoundaries** (gbOpen NGA ADM1,
commit `9469f09`, 2022). **CC BY 4.0 — attribution to "GRID3 / geoBoundaries"
is required** wherever it's used (see `src/main/resources/db/data/README.md`).
Chosen after research, decided with the user: GADM is non-commercial only,
Natural Earth too coarse at borders, OSM's ODbL is share-alike, the
simplified geoBoundaries file shifts borders. Verified in PostGIS before
choosing: all 37 valid, zero overlap between states, and transposed Abuja,
Kano and Port Harcourt land in Benue, Adamawa and Ondo respectively — all
inside Nigeria, all now caught. Loaded from a generated SQL file
(`scripts/generate-nigerian-states-sql.py`); a new dataset version is a new
changeset. The app role may only `SELECT` it — reference data changes by
migration, and 019's default privileges would otherwise grant writes (proven
by test, and red without the REVOKE).

**These are operational boundaries, not the legal record** — some state
borders are disputed. Treat the check as a mistake-catcher, not a ruling on
whose land it is.

**State names are standardised.** `estates.state_code` (ISO 3166-2, `NG-FC`)
is what the check keys on; `estates.state` is now always the canonical name —
the frontend's own list (`~/landvault/src/data/nigerianStates.ts`), which the
dataset matches exactly except the FCT. Input is resolved by name, code or
alias, case-insensitively, with a trailing " State" ignored ("FCT", "Abuja",
"Lagos State", "Nassarawa" all work); anything else is `UNKNOWN_STATE`.
Existing rows were mapped in the migration ("FCT" → `NG-FC`). **Wire change**:
an estate entered as "FCT" now reads back as "Federal Capital Territory
(Abuja)".

**The check** runs wherever an estate's boundary or state is written — create,
add-boundary, and an update that changes the state (which must re-check the
boundary the estate already has). The boundary must sit within the declared
state **buffered by 1 km** (`landvault.estates.state-check.margin-metres`), or
it's refused as `BOUNDARY_OUTSIDE_STATE`, naming the state it actually falls in
("sits in Benue, not Federal Capital Territory (Abuja)") — useful whether the
cause is a swap or a mislabelled state. The margin absorbs border-data
imprecision and estates on a border; a transposed boundary lands hundreds of
km away, so it never hides one. Plots aren't checked separately — they must
sit inside their estate already.

**This also makes the state honest for buyers**: many estates marketed as
"Abuja" (Karu, Mararaba, Masaka) are in Nasarawa, and title there is
registered with Nasarawa, not AGIS. The developer can keep "Abuja" in
`area`/`city`; `state` has to be true.

**The override, for disputed borders**: `POST/DELETE
/api/admin/estates/{id}/state-override` (`admin.marketplace.conflicts` — the
people who already rule on boundary disputes between companies), with a
required reason. Records who verified the state by hand, and why, and skips
the check for that estate. The flow: the developer creates the estate without
a boundary, support verifies, the developer adds the boundary. **Changing the
estate's state clears the override** — it verified the old state.

**Off in the general test suite** (`src/test/resources/application.properties`):
fixtures in many IT classes put estates on synthetic coordinates spread
across Nigeria with placeholder states, and moving them all is churn unrelated
to what those classes test. `StateBoundaryUnderRlsIT` switches it on, runs
under the restricted app role, and covers every rule above; each was proven
red by removing it (margin 0, check bypassed, no re-check on state change,
override ignored, REVOKE missing). State-name standardisation stays on
everywhere.

## `actual_area_sqm` is computed on write, and must be recomputed on edit

When a plot is created with a footprint, its surveyed area is computed as
`ST_Area(footprint::geography)` — square metres — and stored. It is **never
accepted from the request**.

Computed on write rather than per read: simpler, and it matches the column
that already exists. **The consequence is that editing a footprint must
recompute it**, or the stored area silently describes the old boundary.
**Closed for plots by IE-9** (`PUT .../plots/{plotId}/boundary` recomputes it). Estate boundaries can only be added once, never changed, so the estate side has nothing to recompute yet.

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

An estate is publicly listable only when **every** condition holds — five
originally, now nine (fees and refund terms from full cost disclosure, the
boundary from BG-1, at least one plot from changeset 063; see those sections):

1. `estates.published` is true (the developer's own opt-in switch)
2. the owning tenant's `verificationState` is `VERIFIED`
3. that tenant holds the `marketplacePublishing` entitlement
4. the tenant's `status` is **`ACTIVE`** — corrected from "not
   `SUSPENDED`", which would have left an **`OFFBOARDED`** company's land
   for sale on a platform it has left
5. **no conflict blocks it** — `ConflictDetectionApi.publicationCheckFor(estateId)`
   returns `blocked == false` (added by inventory slice 4; see the conflict
   detection sections below for why HIGH blocks and MEDIUM only warns)
6. a fee schedule is declared, and 7. refund terms are declared (full cost
   disclosure, changeset 059)
8. **the estate has a boundary** (BG-1, changeset 061)
9. **the estate has at least one plot**, in any status (changeset 063)

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

**For plots, this path is now reachable through HTTP** (IE-9: correcting a
plot boundary re-runs `detectForEstatePlots`, and
`InventoryEditUnderRlsIT.aCorrectionCanCreateAnOverlapAndAnotherCanClearIt`
proves a plot conflict auto-resolves end to end). **For estates it is
reachable too since FP-2's boundary correction** (`PUT .../boundary`), which
calls `detectForEstateBoundary`;
`EstateBoundaryCorrectionUnderRlsIT.aCrossCompanyOverlapFromACorrectionBlocksAndOnlyAHumanClearsIt`
proves a cleared HIGH conflict stays blocking until a human closes it.
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
- ~~**An estate with no boundary can be published**~~ — **closed by BG-1**
  (changeset 061, see "No boundary, no listing" below).

## Updating an estate's details: `PUT /api/portal/estates/{id}`

`portal.estates.manage`, `PortalEstateService.updateEstate`. Name,
description, area, city, state, address, corner premium, intent and
amenities. Every field optional — left out means unchanged; a blank text
field clears it, except `name` and `state`. An edit that changes nothing
writes nothing; otherwise one `estate.updated` audit entry lists each field's
old and new value.

- **Refused, never silently ignored** — the decision made when the boundary
  got its own route: `footprint` (`BOUNDARY_NOT_EDITABLE_HERE` — the boundary
  route runs overlap detection; a generic update with `footprint: null` would
  also be a way to *remove* a boundary and dodge BG-1), `published`
  (`PUBLICATION_NOT_EDITABLE_HERE` — publishing checks every condition) and a
  different `branchId` (`BRANCH_NOT_EDITABLE` — moving an estate would have
  to move every plot across the RLS branch wall; not supported). Boot's
  Jackson ignores unknown properties, so these exist on the request DTO only
  so they *can* be refused.
- **A rename regenerates the slug**, with creation's per-tenant uniqueness
  (409 on a clash). Keeping the old slug was rejected: a renamed estate would
  then silently block a new estate under its *old* name.
- **A corner-premium change reprices every corner plot at once**, live on the
  marketplace — prices are computed on read. Reservations keep the price
  captured on them; proven by test.
- **Amenities are replaced wholesale, removed ones hard-deleted.** An amenity
  is a description, not history, and `uq_estate_amenities_estate_name` counts
  soft-deleted rows — a soft-deleted amenity could never be added back.

## Inventory editing, slice 3: withheld, bulk status, retired tiers, withdrawn plots

Changeset **062**; `InventoryEditService` + `PlotStatusWriter`. All
`portal.estates.manage`.

### IE-7: `withheld` — a developer's way off the market

`PUT .../plots/{plotId}/status` with `withheld`, `available`,
`available-dev` or `available-inv`. **Never `reserved` or `sold`** — those
are reached only through checkout; the two old workarounds were both
dangerous (a hand-set `RESERVED` is a hold the sweep never releases; a
hand-set `SOLD` is a sale with no payment).

- **The available variant is remembered**, in `plots.withheld_from_status`,
  and `available` restores it — investment plots are never flattened to
  development, the same distinction the reservation sweep preserves. A
  CHECK constraint keeps the column set only while `WITHHELD` and only to an
  available variant — proven to be a real second line: letting the code
  withhold a *reserved* plot made the database refuse the write.
- **Returning to the market resyncs a land plot's size from its tier.** A
  tier resize reaches available plots only (IE-4), so a withheld plot can be
  carrying the old size — the same reason changeset 060 resyncs on release.
- Nothing else needed changing: the reserve function only acquires
  `AVAILABLE_*`, and the marketplace view already collapses every
  non-available status to `UNAVAILABLE`, so a withheld plot shows on the map
  as unavailable without revealing why.
- **Frontend**: `withheld` is a new value in the `PlotStatus` union.

### IE-8: bulk status — skip and report, compare-and-swap

`POST .../plots/status` `{ plotIds (≤500), status, reason, dryRun }`.
**Skip and report, never all or nothing** — one reserved plot must not block
a 200-plot launch (contrast file import, which is all or nothing).

Each transition is **one SQL statement** (`PlotStatusWriter`) whose `WHERE`
names the statuses it may move from — the same compare-and-swap the
reservation uses. A plot being reserved at that instant holds its row lock;
the update waits, re-evaluates against the committed row, and skips it.
`withholdingAndReservingTheSamePlotAtOnceNeverOverwriteEachOther` races the
two with a latch and asserts exactly one wins. `dryRun` reports without
writing, and is never trusted: the real request re-checks everything.
Skipped plots come back with a code (`RESERVED`, `SOLD`, `NOT_FOUND`,
`ALREADY`, `NO_RECORDED_AVAILABILITY`) and a reason. Another company's plot id
simply doesn't match under RLS and reports as `NOT_FOUND`. One audit entry
per change, with the reason.

### IE-5: retired tiers — `price_tiers.retired_at`, never `deleted`

`POST .../price-tiers/{tierId}/retire` and `/reinstate`. A soft-deleted tier
would vanish from every lookup and its plots would lose their price, so
retirement is its own column. A retired tier **accepts no new plots** —
refused at creation, import (`TIER_RETIRED` in the report, and the template
lists only open tiers) and tier moves — but **existing plots keep it and stay
sellable at its price**; withholding is how to take them off sale.

### IE-11: withdrawing a plot — untouched plots only

`DELETE .../plots/{plotId}`: a soft delete, and only for a plot with **no
history** — never reserved or bought in any state (an expired or cancelled
hold counts) and never part of a conflict record. Otherwise
`PLOT_HAS_HISTORY` (409): its records refer to it; withhold it instead.
Locked first, like every plot edit.

- **Purchase history** is answered by `checkout` through
  `inventory.PlotHistoryProbe` — the **fifth** inverted interface (inventory
  declares, checkout implements), because checkout is the module that will
  one day tell inventory a plot is sold, so the arrow has to run checkout →
  inventory.
- **Conflict history** goes through `landvault_plot_has_conflict_history`
  (`SECURITY DEFINER`, boolean only) — `listing_conflicts` is platform-scope
  only, so an ordinary tenant-scoped read would always answer "no history".
  **Proven**: as `SECURITY INVOKER` the check passed a plot with conflict
  history and the withdrawal went through.
- **A withdrawn plot's number can be reused.** `uq_plots_estate_block_number`
  is now a unique index over live rows only (`WHERE deleted = false`, still
  `NULLS NOT DISTINCT`), same name so the existing 409 mapping holds. Safe
  because only history-free plots are ever withdrawn.

## Plot import from a surveyor's file (FU-1..FU-3)

`GET .../plots/import/template` (`portal.estates.view`),
`POST .../plots/import/preview` and `POST .../plots/import` (both
`portal.estates.manage`, multipart `file` plus optional property-name
parameters). `PlotImportService`.

**GeoJSON only.** A surveyor's CAD or shapefile is converted once in QGIS;
parsing shapefile/DXF here would be a large, edge-case-heavy job for no gain.
A single-part `MultiPolygon` is accepted (QGIS exports single shapes that
way); a multi-part one is refused — a plot is one shape. Upload limit 10MB
(`spring.servlet.multipart`), 500 features per file (the same ceiling as
`CreatePlotsRequest`, since the import goes through that path).

**The coordinate-system trap is the one the stories didn't mention.**
Nigerian survey software defaults to UTM metres on the Minna datum (zones
31N–33N). Two places now say so instead of "outside Nigeria", which sends a
surveyor looking for the wrong mistake: a file whose `crs` member names a
non-4326 system is refused as `PROJECTED_COORDINATES`, and
`GeoJsonPolygonParser` recognises metre-sized numbers (|lng| > 180 or
|lat| > 90) as projected coordinates. The parser change applies to every
route that takes a boundary, not just import.

**The parser also rejects self-crossing polygons now** (JTS `IsValidOp`), on
every route: a "bow-tie" — two corners in the wrong order — is closed but not
a real area, and PostGIS would compute a meaningless surveyed area and overlap
for it. Only existing rectangles were in the fixtures, so nothing relied on
the old leniency (full suite green after the change).

**Preview reports everything at once; import is all or nothing.**
- One report per file: `errors` (block the import), `warnings` (don't),
  `blocksToCreate`, `plotsPerTier`. Codes include `UNKNOWN_TIER`,
  `AMBIGUOUS_TIER`, `DUPLICATE_PLOT_NUMBER`, `PLOT_NUMBER_EXISTS`,
  `OUTSIDE_ESTATE`, `INVALID_GEOMETRY`, `MISSING_PLOT_NUMBER`.
- The preview is **not a promise** — the import validates again rather than
  trusting it, since the estate can change in between.
- An import with any error creates **nothing**, not even the valid plots or
  new blocks, and returns the report with **HTTP 422** (the report as the
  body, deliberately not the usual error shape — it already lists every
  problem). Half a 450-plot estate imported is worse than none. Contrast
  bulk status changes (IE-8), which will skip-and-report.
- Valid files go through `PortalEstateService.createBlock`/`createPlots`, so
  tier sizes, containment, area and overlap detection are the existing rules,
  not a second copy of them. Missing blocks are created (matched
  case-insensitively, so "a" and "A" are one block).

**Tier mapping** is by the `tier` property: a tier's label
(case-insensitive) or, failing that, a land tier's size in sqm — so a
surveyor's `size: 500` column maps with no extra configuration. Which
property carries plot number, block, tier and corner is configurable per
upload. Imported plots are `available-dev` or `available-inv` only.

**Overlaps are warnings**, found in memory with a JTS envelope index and
measured by the database (geography, m²) only for pairs that genuinely
intersect — so neighbours sharing an edge are never flagged, matching the
detector's 1 m² threshold (`landvault.conflicts.min-overlap-sqm`). After
import, `detectForEstatePlots` records them for review, exactly as for plots
added one at a time.

**A useful finding from the tests**: coordinates transposed near Abuja stay
inside Nigeria, so the national check can't see them (see the coordinate-swap
section) — but inside an estate that has a boundary, the containment check
catches them as `OUTSIDE_ESTATE`. One more reason BG-1 matters.

**A bug the IT caught before anyone saw it**: the template's file-name lookup
ran outside a transaction, so `TenantScopedDataSource` never set the tenant
GUCs, RLS returned no estate, and the download 404'd. The fix returns name and
content from one `@Transactional` call. Same fail-closed mechanism AGENTS.md
describes for scheduled jobs: **any repository read needs a transaction to
carry the tenant scope.**

**FU-3's template** is built per estate: its real tier labels, two example
plots inside its real boundary (placeholder coordinates, said so, when it has
none), and an `instructions` array (a GeoJSON foreign member, ignored by GIS
tools) stating EPSG:4326 and the property names. The IT proves it previews
clean and imports as downloaded.

**Tier mapping (FI-6 follow-up)**: an optional `tierMapping` form field — a
JSON object from the file's own values to tier ids,
`{"A": "<tier id>", "B": "<tier id>"}` — for files whose tier column uses a
surveyor's codes rather than the estate's labels or sizes. Keys match ignoring
case and spaces; values not in the mapping still match by label or size; a
mapped retired tier is still `TIER_RETIRED`. A malformed mapping, or one
pointing at another estate's tier, is a 400 for the whole request — one
mistake in the request, not one per plot. Proven red.

**Not built**: a per-feature size override for unit-type tiers, and a
per-feature status. Both are one property each if needed.

## Inventory editing, slice 2: a plot's boundary (IE-9) and its tier (IE-10)

`PUT /api/portal/estates/{id}/plots/{plotId}/boundary` and
`PUT /api/portal/estates/{id}/plots/{plotId}/tier`, both `portal.estates.manage`,
both in `InventoryEditService`.

**Available plots only, enforced under a row lock.** Both load the plot with
`PlotRepository.findForUpdate` (`SELECT … FOR UPDATE`, the same lock
`landvault_reserve_plot` takes) and check the status *after* the lock is held,
so an edit and a reservation of the same plot serialize: either the
reservation commits first and the edit is refused (`PLOT_NOT_EDITABLE`, 409),
or the edit commits first and the buyer reserves the corrected plot. A
check-then-write without the lock would let a buyer's hold be edited under
them. **Reserved and sold plots are deliberately not editable** — their
boundary, price and size are what a buyer agreed to; correcting a sold
plot's survey is a matter for a person and a legal process, a stated gap
rather than an oversight.

### IE-9: correcting a boundary
- Must sit inside the estate's boundary when there is one (`PLOT_OUTSIDE_ESTATE`).
- A boundary can be **replaced, never removed** — `footprint` is required.
  A plot that never had one can be given one through the same route.
- **`actual_area_sqm` is recomputed** (geography cast, square metres). A
  stale surveyed area looks authoritative, which is worse than none.
- **Plot overlap detection re-runs for the estate** before and after, and
  the response reports the overlapping-pair count both times — a correction
  that clears an overlap, and one that creates one, are both reported. This
  is what finally lets plot-conflict auto-resolution fire (CD-9). Plot
  overlaps are same-company, so they warn and never block publication.
- Audit `estate.plot_boundary_corrected` with old and new area.

### IE-10: moving to another tier
- Target tier must be on the same estate (another estate's tier is a 404)
  and in the **same currency** (`TIER_CURRENCY_MISMATCH`, 400) — otherwise a
  naira plot silently becomes a dollar plot.
- **Size rule, decided with the user**: to a `LAND_SIZE` tier, the plot takes
  that tier's size and `nominalSizeSqmOverride` is *refused* (not ignored —
  the tier is authoritative, same as creation). To a `UNIT_TYPE` tier, the
  plot keeps its current size unless an override is given — so a move never
  silently clears a size or leaves a land plot sizeless.
- The response reports price and size before and after; price is computed by
  `PlotPricing`, corner premium included. A move to the same tier with the
  same size is a no-op and writes no audit entry.
- Audit `estate.plot_tier_changed` with tier labels, price and size.

### A plot's property type follows its tier (found in the IE-10 walkthrough)

IE-10 as first built let a `LAND` plot move to a `UNIT_TYPE` tier, leaving
it marked bare land but priced as a 3-bedroom terrace. Creation had the same
hole one step earlier: `propertyType` defaulted to `LAND` whatever the tier.
The rule now, in one place (`PortalEstateService.expectedPropertyType`):
**`LAND_SIZE` tier ↔ `LAND` plot, `UNIT_TYPE` tier ↔ `BUILT` plot.**

- **At creation** (single, batch and file import, which goes through the
  same path) the type is **derived from the tier when left out** and
  **refused when it contradicts it** (`PROPERTY_TYPE_MISMATCH`, 400).
- **On a tier move** the target tier must be of the same kind; crossing is
  refused. Turning bare land into a built unit is a change to what the plot
  physically is, not a re-categorisation, and has no route yet.
- It cannot be a database constraint — it spans two tables. Rows written
  before this rule may still disagree (the dev database has one, from the
  walkthrough that found it); nothing corrects them automatically.

### Conflict copy matches what the portal can actually do

Estate-level guidance once promised a correction the portal didn't offer
(caught in the BG-1 walkthrough), so it said "contact support". Since FP-2,
estate boundaries can be corrected, so the copy says so again — carefully:
a **cross-company** conflict says "correct it — our team reviews the
correction before publication can resume", never that a correction clears
it (that would teach the shaving exploit); a **same-company** one says
correcting it clears it, which is true. The plot message is unchanged.

## No boundary, no listing (BG-1) — and no grandfathering

**The hole**: conflict detection compares *estate* boundaries across companies,
but *plot* boundaries only within one estate (`landvault_detect_plot_conflicts`
filters both sides on `estate_id`). An estate with no footprint therefore never
meets another company's land at all, even if every one of its plots has a
shape. Publishing without a boundary was a way around the anti-fraud check the
whole gate exists for.

**The fix** (changeset 061): `marketplace_estate_eligibility` requires
`e.footprint IS NOT NULL` for `eligible`, and exposes it as its own
`has_boundary` column. The publish endpoint refuses with
`PUBLICATION_BOUNDARY_MISSING`, checked **just before** the conflict condition
because it is what makes that check meaningful — with no boundary, "no
conflict" is an absence of evidence. `EstateEligibilityDto.hasBoundary` puts it
on the portal's readiness read.

**No grandfathering, deliberately unlike the fee rule.** Exempting existing
listings from *disclosure* (changeset 056) traded principle for usefulness on a
nicety. Exempting existing listings from *this* rule would keep the anti-fraud
hole open for exactly the estates it catches — the local dev database had one
such published, non-exempt, boundary-less estate when this was decided. An
estate already live without a boundary drops off the marketplace with its
`published` flag untouched (PB-5's intent/eligibility split) and returns, without
republishing, as soon as a boundary is added and passes detection. Decided with
the user; if leniency is ever wanted it should be a per-estate Super Admin
decision, never an automatic exemption.

### At least one plot to be listed (changeset 063)

Found in the BG-1 walkthrough: an estate with no tiers and no plots published
cleanly and sat on the marketplace with nothing for sale. `has_plots` is now a
condition (`PUBLICATION_NO_PLOTS`), checked after the boundary and before the
conflict. Every plot has a tier, so this also means "has a price".

**Any live plot counts, deliberately not an *available* one.** Requiring an
available plot would pull a sold-out estate off the marketplace, and make an
estate flicker off while every plot sat in a 45-minute checkout hold —
revealing exactly the sales velocity `marketplace_plots` collapses
RESERVED/SOLD to UNAVAILABLE to hide. `MarketplaceIT.aSoldOutEstateStaysListed`
pins this and was proven red against the available-only variant. An estate
whose plots are all *withdrawn* does drop off — withdrawn means "never really
for sale" — and returns without republishing when a plot is added.

No grandfathering, like BG-1.

### Adding a boundary later: `POST /api/portal/estates/{id}/boundary`

BG-1 on its own stranded every estate created without a boundary: a boundary
could only be set at creation, so such an estate could never be published.
That applies in production too, not just to legacy rows, because the boundary
is optional at creation. Decided with the user: keep it optional (a developer
can set an estate up before the survey is ready) and add this route, rather
than make it required at creation.

- **Only when there is no boundary yet** (409 `BOUNDARY_ALREADY_SET`).
  *Changing* an existing one is its own route — see "Correcting an estate
  boundary" below.
- **Every plot that already has a boundary must sit inside the new one**,
  checked in one statement for the whole estate (`GeometryCalculator.plotsOutside`),
  never per plot. Refused with `PLOT_OUTSIDE_ESTATE` naming each plot
  ("Block A, Plot 7"); nothing is saved.
- **Runs `detectForEstateBoundary` in the same transaction**, then reports the
  result (`publicationBlocked`, `blockReason`, `warningConflictCount`) — never
  the counterparty (CD-11). This is the step that keeps the route from being
  a back door around BG-1: a boundary-less estate is compared against other
  companies' land the moment it gains one. Proven red by removing the call.
- An already-published estate returns to the marketplace on its own when it
  now passes every condition; a HIGH overlap keeps it off.
- Audit entry `estate.boundary_added`.

**Verified, not assumed**: no existing fixture published a boundary-less estate,
so the full suite stayed green with the rule added — nothing was covering the
hole. `MarketplaceIT.anEstateWithNoBoundaryCannotBePublishedAndSaysWhy` and
`aLiveEstateWithoutABoundaryLeavesTheFeedAndReturnsWhenOneIsAdded` were each
proven red by removing their half of the rule (the endpoint check, the view
term).

## Correcting an estate boundary (FP-2): option C

`PUT /api/portal/estates/{id}/boundary` `{ footprint, reason }`
(`portal.estates.manage`), `GET .../boundary-changes` (`portal.estates.view`),
`POST .../boundary-changes/{changeId}/withdraw`; for Super Admins
`GET /api/admin/boundary-changes?status=pending`, `POST .../{id}/approve`
`{ note? }`, `POST .../{id}/reject` `{ note }` (`admin.marketplace.conflicts`).
`EstateBoundaryCorrectionService`, changeset **069**.

**Decided with the user — option C**: a correction applies at once **unless
the estate is published and more than 5% of its land changes**
(`landvault.estates.boundary-correction.review-threshold-pct`), in which case
it waits for a Super Admin and the current boundary stays live (202). The
reasoning: real survey corrections are small; a big change to a live listing
is exactly what a human should see before buyers do — and it's the one case
detection can't police, because a boundary moved onto land *no other company
has listed* raises no conflict. Option A (never review) left that open;
option B (review every change to a published estate) queued trivial fixes.

- **"Changed" is the symmetric difference** — land added plus land removed,
  as a share of the old area (`GeometryCalculator.changedArea`, geography
  cast) — so a boundary that slides sideways counts even if its area doesn't.
  An area comparison alone would wave a relocation through.
- **Same checks as adding one**: inside the declared state, and every mapped
  plot still inside (`PLOT_OUTSIDE_ESTATE` names them). **Approval re-checks
  both**, because a plot can be mapped near the edge while a request waits —
  proven red.
- **Applying re-runs `detectForEstateBoundary`**, so CD-9's rules finally
  reach estates: a same-company overlap that clears auto-resolves; a
  cross-company one that clears gets `geometry_cleared_at` and **stays
  blocking until a Super Admin dismisses it** (the shaving defence); a new
  cross-company overlap blocks publication. Never names the other company.
- **One pending correction per estate** (partial unique index, plus a
  service check with its own code `BOUNDARY_CHANGE_PENDING`); withdrawing
  frees it. `BOUNDARY_UNCHANGED` for an identical shape, `BOUNDARY_NOT_SET`
  when there's nothing to correct (use `POST .../boundary`).
- **History, never overwrite**: every correction is an
  `estate_boundary_changes` row with both shapes (GiST-indexed, as every
  geometry column must be), status `APPLIED` / `PENDING` / `APPROVED` /
  `REJECTED` / `WITHDRAWN`, the reason, and who decided. RLS-policied in the
  same changeset (tenant + branch, platform for reviewers) — not deferred.
- Audit: `estate.boundary_corrected`, `estate.boundary_change_requested`,
  `estate.boundary_change_approved`, `estate.boundary_change_rejected`,
  `estate.boundary_change_withdrawn`, each with old/new area and % changed.
- **No notification** reaches a reviewer yet — pending corrections sit in the
  admin queue. Worth an email (like invitation approval) once someone relies
  on it.

Proven red: the threshold, published-only review, the approval re-check, and
re-running detection.

### Itemised conflicts: what a boundary change raised and cleared

Every route that changes a boundary — `POST .../boundary` (add), `PUT
.../boundary` (estate correction, and its approval), `PUT
.../plots/{plotId}/boundary` (IE-9) — now returns `conflictChanges`:
`raised`, `resolved`, `awaitingReview` (overlap gone, but a cross-company
conflict needs a person to close it — still blocking), and `stillOpen`.
Counts alone (`plotOverlapsInEstateBefore/After`, `warningConflictCount`)
couldn't tell a developer *which* overlap cleared, which the frontend raised.

- Built from **two snapshots of the tenant's own view**
  (`ConflictDetectionApi.conflictsOf`, which reads through
  `landvault_tenant_estate_conflicts` — the CD-11 function), compared by
  `ConflictChanges.between`. So the other company is never named, by
  construction: nothing in a snapshot could name it.
- **The "before" snapshot is taken against current state**: IE-9 already
  re-runs plot detection first, so its snapshot comes after that; the estate
  routes snapshot before the footprint is swapped.
- `ConflictItem` / `ConflictChanges` live in the conflicts module's **base
  package**, not `conflicts.dto`: `dto` packages aren't Modulith named
  interfaces (the `identity.dto` finding), so `inventory` couldn't carry
  `TenantConflictDto` in its own responses.
- For a conflict between two of the **same** company's estates, both sides are
  the caller's, so the item may name either estate.

`ConflictChangesTest` pins the four buckets; the boundary ITs pin them over
HTTP. Proven red: the awaiting-review bucket, and a missing "before" snapshot.

## Plots without a boundary are for sale, listed, and flagged — not hidden

Found from the frontend side (2026-10-07): the public map draws only plots
with a surveyed boundary, so a plot without one was **invisible to buyers and
therefore unsellable, with nothing telling the developer**. Three options
were weighed with the user — list them, require a boundary to sell, or both —
and **listing them was chosen**: in Nigeria individual plots are often
surveyed and allocated after an estate is set up, so requiring a boundary
would block legitimate sales.

- **`GET /api/marketplace/estates/{id}/plots`** (public, GET-only, rate
  limited like the rest) lists every plot of a published estate from
  `marketplace_plots` — boundary or not — with `hasBoundary`, the same fields
  as a map feature, availability still collapsed to AVAILABLE/UNAVAILABLE,
  and no price (client computes, as for the map). `available=true` filters to
  reservable plots. Paged (`limit` ≤ 500), ordered by block then plot number.
- **The honest cost, stated to the buyer**: a plot without a boundary is
  outside double-allocation detection (there's nothing to intersect) and its
  position within the estate isn't confirmed. The frontend must say so beside
  any `hasBoundary: false` plot — never present it like a surveyed one.
- **The developer is told**: the portal estate detail carries
  `plotsWithoutBoundary` (live plots with no footprint), so the portal can
  warn "N plots aren't on the map".
- If buyer protection ever needs to be stronger, the rejected alternatives
  were: no sale without a boundary, or listed but not reservable. Both are
  small changes on top of this.

The `/estates` grid page in the frontend is the other half of this
discussion: it is being retired in live mode in favour of `/marketplace`,
and **the backend will not add grid positions** (row/column) — a made-up
layout that could disagree with the real map.

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

**A trap this created, found in a live walkthrough (2026-09-27):**
`AuthController` carries a class-level empty `@SecurityRequirements`,
because every route in it was public when it was written. `change-password`
was later added to the same class, inherited that opt-out, and was
documented as public — so Swagger UI never sent the token even after
Authorize, and every try-it-out call came back `UNAUTHENTICATED`. The
server was correctly protected throughout; only the document was wrong.
Fixed with a method-level `@SecurityRequirement(name = BEARER_SCHEME)`.
**Any authenticated route added to `AuthController` needs the same
override**, or it silently inherits "public" in the docs.

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

## Eligibility is a portal read, not something you learn by failing to publish

`EstateEligibility`'s booleans were kept separate specifically so the publish
endpoint could name the condition that failed (PB-3) — but the record sat on
no read DTO, so the only way to learn a condition was outstanding was to
attempt a publish and catch `PublicationRefused`. A readiness screen built on
that has nothing to render, and cannot guess: showing an unverified condition
as met would be fabricated data.

`EstateDetailDto.eligibility` now carries all eight booleans.

**It is `inventory`'s own DTO, not `marketplace`'s record**, because
`noBlockingConflict` comes from a third module. The eligibility view folds
the conflict check into `eligible` alone, and `marketplace` has no dependency
on `conflicts` to expose it separately — so `inventory`, which already
depends on both, assembles it.

**`noBlockingConflict` is explicit precisely because it was inferable.** A
client could reason "every named condition passes but `eligible` is false, so
it must be a conflict" — correct today, and silently wrong the moment a ninth
condition is added and not exposed, with the failure mode being a developer
told the wrong reason. An inference standing in for a fact is what the
separate-booleans design existed to prevent.

The conflict check is real polygon work, so it runs on the **single-estate
detail read only**, never per row of a list.

## Login reports the caller's own scope, resolved by the filter's own code

`AuthUserResponse` now carries `tenantId` and `branchId`. **This is not a
security change** — branch scoping is enforced by RLS either way. It is so
the portal can label a screen honestly: *"your branch's estates"* versus
*"every estate across your company"*. Previously `branchId` was available
only from `GET /api/me/tenant-scope`, whose own Javadoc calls it a debug
surface for observing the tenant-context filter.

**`branchId` null means organisation-wide, not unknown**, and that
distinction must not be flattened — it is the same one
`TenantScopeResolver` already makes load-bearing.

`TenantScopeResolver.resolveBaseScope` gained an overload taking the parts
rather than a parsed token, and `AuthService` calls it. **Deliberately one
implementation**: if login computed a branch one way and the filter another,
the portal would label its screens with a scope the server does not apply,
and the two would drift apart with nothing failing. The class is now public
*within* `identity.internal` — still invisible to every other module, since
the whole package is `internal`.

## Payments (Paystack): built one step at a time

Stories PY / FV / TR. Built **manually, step by step, with the user** —
each step explained, decided, built, tested and tried before the next.
Paystack's docs site blocks automated readers; the facts here come from
Paystack's own GitHub: the official OpenAPI description
(`PaystackOSS/openapi`) and the code snippets behind their docs pages
(`PaystackOSS/doc-code-snippets`). Not confirmed from those: the webhook
retry schedule, Paystack's IP addresses, the test card numbers.

### Step 1 — naira ↔ kobo, in one place
`payments.internal.paystack.PaystackAmounts`. The app keeps **exact
`BigDecimal` naira everywhere** (never `double`); Paystack takes and reports
**whole kobo** (`40333` = ₦403.33). Converting only at the Paystack edge was
chosen over switching the app to integers: our sums are already exact, and
percentages (corner premium, refunds, penalties) need more precision than a
kobo mid-calculation.

- **The agreed price is rounded to the kobo once, at reservation**
  (`ReservationService`, half up): ₦20,000,001 × 1.125 = ₦22,500,001.125 →
  agreed as ₦22,500,001.13. The price list keeps 4 decimals; only what the
  buyer agrees to pay is rounded, so the figure shown, stored and charged agree.
- `toKobo` is **exact and refuses** a sub-kobo amount rather than rounding
  again — one reaching it means a bug upstream. Zero/negative refused.
- **NGN only** (`CURRENCY_NOT_SUPPORTED`): dollar tiers exist, but diaspora
  rails are out of scope and Paystack USD needs separate approval.

### Step 2 — the Paystack client
`PaystackClient` (`initialize`, `verify`) over a `RestClient` built in
`PaystackConfig`. **`PAYSTACK_SECRET_KEY` comes from the environment only**,
goes out solely as `Authorization: Bearer` to `api.paystack.co`, is never
logged or put in an error; startup logs only "TEST"/"LIVE" mode, and a missing
key fails startup. 5s connect / 15s read timeouts.

- **The top-level `status` is not the payment.** It means the API call
  worked; whether money arrived is `data.status`. The client exposes only the
  latter (`paymentStatus`, `succeeded()`), so nobody can read the wrong one.
- Refusals carry Paystack's own message (`GatewayRefused`); a 5xx or no
  connection is `GatewayUnavailable` (retryable). An unknown reference on
  verify is `found = false`, not an error. Only a card's `last4` and `bin` are
  ever read; the raw reply is kept for disputes (PY-10, stored later).
- The injected `RestClient.Builder` is Spring Boot's prepared one (app JSON
  settings, metrics); `clone()` keeps the key header on our copy only — Spring's
  docs call builders stateful and recommend exactly this. Timeouts go through
  Boot's own `HttpClientSettings` + `ClientHttpRequestFactoryBuilder.detect()`
  (it picks the HTTP library and applies settings consistently), not a
  hand-built factory. Tests use a plain builder with `MockRestServiceServer` as
  a stand-in Paystack.
- **RestClient, not WebClient or RestTemplate**: this is a Spring MVC app;
  WebClient is for reactive WebFlux apps, RestTemplate is legacy and no longer
  auto-configured.

### A test-scoped dependency hid a startup failure
`spring-boot-starter-restclient` was in `pom.xml` with `<scope>test</scope>`
(added for the tests' HTTP calls). In Boot 4 that module is what provides the
auto-configured `RestClient.Builder`. So **all 378 integration tests passed —
the test classpath had the builder — while the real app could not start**
("required a bean of type RestClient$Builder"). Found by starting the app, not
by the suite. Fixed by making it a main dependency. **The lesson: a library the
app uses at runtime must never be test-scoped**, and a green suite is not proof
the app boots — start it after adding anything that depends on
auto-configuration.

### Step 3 — starting a payment (PY-1, PY-9)
`POST /api/transactions/{id}/payments` (`client.checkout.reserve` — decided
with the user: paying is part of the purchase flow), `PaymentService`,
changeset **070** (`payments`: one row per attempt). Decided with the user:
**outright only** (`PAYMENT_PLAN_NOT_SUPPORTED` for installments — they need
saved-card charging), and **pressing pay again within 30 minutes returns the
same open link** (200) instead of opening a second payment (201).

- **The amount is the transaction's agreed price**, never the request; the
  transaction must be the caller's own (404 otherwise, via the new narrow
  `CheckoutApi.transactionForBuyer`) and `pending_payment` (409
  `TRANSACTION_NOT_PAYABLE`). The buyer's email comes from the new
  `IdentityApi.emailOf`.
- Our reference `LV-PAY-…`; `metadata` carries payment, transaction,
  reservation and plot ids so any later webhook traces back (PY-9).
  `callback_url` is the **frontend** page `/payments/return`
  (`PAYSTACK_CALLBACK_URL`) — **arriving there proves nothing**; a payment
  counts only once verified with Paystack (step 4).
- **The row is saved before Paystack is called**, so its database-assigned id
  can go into the metadata; if Paystack then fails, the transaction rolls back
  and no payment is left behind (tested). Setting the id by hand instead made
  Spring treat the new row as an existing one — the base entity generates ids.
- The Paystack call runs inside the DB transaction (a connection is held up to
  the 15s read timeout) — acceptable at today's volume; revisit with load.
- `payments` is buyer-owned like `transactions`: **not RLS-policied**,
  `seller_tenant_id` records whose sale it is.

Proven red: the ownership check (another buyer reached Paystack without it),
and the open-link reuse.

### Step 4 — confirming a payment (PY-3, PY-4, PY-6, PY-8)
`POST /api/payments/{reference}/verify` (`client.checkout.reserve`, own
payment only — 404 otherwise), `PaymentConfirmationService`. The frontend's
return page calls it; **it claims nothing** — LandVault asks Paystack itself
(`verify`), so calling it any number of times can't fake a payment. The
webhook (step 5) will call the same `confirm`.

- **Locked, then decided once.** The payment row is read `FOR UPDATE`, so the
  return page and the webhook confirming at the same moment take turns; a
  payment already `succeeded`/`failed`/`mismatched` is returned untouched
  without calling Paystack (a duplicate is a no-op, not an error). Moving the
  transaction is a second line of defence: one `UPDATE … WHERE status =
  'PENDING_PAYMENT'`, so it can only ever move once.
- **`success` must match exactly what we asked for** — kobo, NGN and our
  reference. Otherwise **`mismatched`**: not paid, not failed, transaction not
  moved, audited for a person (decided with the user: money arrived but not
  what was agreed, so neither label is honest).
- **`failed`/`reversed` → `failed`**, keeping Paystack's own reason
  (`gatewayResponse`, e.g. "Insufficient Funds") for the buyer (PY-6); paying
  again opens a new attempt.
- **Anything else (`pending`, `abandoned`, `ongoing`) leaves it
  `initialized`** (decided with the user): a bank transfer can confirm minutes
  later — seen live in the first test payment. The sweeper (step 6) settles
  what never finishes.
- **The transaction goes straight from `pending_payment` to
  `awaiting_finance`** (decided with the user — nothing happens in between,
  so `payment_received` is not used as a resting state). The plot **stays
  `RESERVED`**: paid is not sold; finance verifies first (FV-1, step 7).
  `CheckoutApi.recordPaymentReceived` is the only write `payments` makes into
  checkout. Audit entries for verdicts are system entries (no person decided;
  Paystack did), shown as "System".

Proven red: the amount check, the finished-is-a-no-op return, the ownership
check (another buyer made Paystack calls about someone else's payment), and
treating `pending` as final.

### Step 5 — Paystack's webhook (PY-7, PY-10, PY-4)
`POST /api/payments/webhook/paystack` (`PaystackWebhookController`,
`PaystackWebhookService`), changeset **071** (`payment_gateway_events`).
Public — Paystack has no login — and listed in `SecurityConfig` as one exact
path, **POST only** (`PUBLIC_POST_PATHS`), never a wildcard.

- **The signature is the only proof of origin.** `x-paystack-signature` is an
  HMAC-SHA512 of the **raw body** with the secret key (Paystack's own code
  samples). The controller takes `byte[]`, and the check runs over those exact
  bytes before anything is parsed — re-serialising changes the bytes and so
  the signature (proven: checking a re-serialised copy refused a genuine
  call). Constant-time comparison (`MessageDigest.isEqual`). A missing or
  wrong signature → 401 `WEBHOOK_SIGNATURE_INVALID`, a warning logged, and
  **nothing stored** (decided with the user: a public URL must not be a way to
  fill the database).
- **Stored exactly as received** (PY-10): `raw_body` is `text`, not `jsonb`
  (jsonb would reformat it). Every delivery is kept, duplicates included.
  **Append-only is the database's rule**: the app role is revoked UPDATE,
  DELETE and TRUNCATE (proven: without the REVOKE the app could rewrite events).
- **Stored in its own transaction first** (`GatewayEventRecorder`,
  `REQUIRES_NEW`), then acted on; if acting fails (Paystack's verify down) the
  reply is 503 and Paystack retries (decided with the user). Today `handle` has
  no transaction of its own, so the two are separate anyway; `REQUIRES_NEW` is
  what keeps it so if someone wraps `handle` in one — proven both ways.
- **`charge.success` is a prompt, not proof**: it runs the same
  `PaymentConfirmationService.confirm` as the return page, which asks
  Paystack's verify API itself and never trusts the webhook's own amounts. Same
  row lock, so webhook and return page can't double-confirm; a duplicate
  delivery is recorded but confirms nothing new.
- `transfer.*` events are stored for payouts (step 8); unknown references and
  other events are stored and otherwise ignored. Processed in the request
  (decided with the user); a background queue is a later optimisation.
- **Localhost can't receive it**: register the ngrok URL in the Paystack
  dashboard. Paystack's IP addresses were not confirmable (their docs block
  automated readers), so no IP allow-list — the signature is the control.

Proven red: signature bypassed (a forged call got 200), re-serialised bytes,
the missing REVOKE, and `REQUIRES_NEW` (with `handle` made transactional, the
event was lost on failure).

### Step 6 — the payment sweep (PY-5, PY-2)
`PaymentSweeper` (every 5 minutes, platform scope set and cleared like the
reservation sweeper) → `PaymentSweepService.settle`, one purchase per
transaction. Dev-only manual trigger: `POST /api/dev/payments/sweep`
(`@Profile("dev")`, Super Admin).

**Why it must exist**: Paystack sends no webhook for an abandoned payment, and
a hold with a transaction behind it is never released by the reservation sweep
(the double-sale fix) — so without this, **every abandoned checkout kept its
plot off the market forever.**

- **When**: a purchase still `pending_payment` whose hold ended more than
  **15 minutes** ago (`abandon-grace`; decided with the user — a Paystack
  transfer account lasts 30 minutes, so a transfer started late can land).
- **Ask Paystack first**: every open payment is confirmed through the same
  `confirm` as steps 4–5 — the webhook may have been lost. Paid → confirmed
  and moved on, never abandoned (proven red: without this a paid purchase was
  abandoned and its plot released). A `mismatched` payment leaves the plot alone
  for the person reviewing it. Paystack down → that purchase rolls back and is
  retried next run.
- **Nothing paid**: open payments → `abandoned`; the transaction → new status
  **`abandoned`** (decided with the user; backend-added — the frontend's
  `TransactionStatus` union needs it); the reservation ends `expired` and the
  plot returns to sale with its original availability, through the existing
  `ReservationService.endHold`. The transaction moves by the same once-only
  `UPDATE … WHERE status = 'PENDING_PAYMENT'` shape, under the reservation's row
  lock. "Buy" pressed but never "pay" is abandoned too.
- **Late money is real money** (decided with the user): `abandoned` is **not a
  final payment state** — it means we stopped waiting, not that Paystack said
  no — so a later webhook still confirms it (proven red: treating it as final
  lost the money). The payment becomes `succeeded`; the transaction stays
  `abandoned`; `payments.review_reason` (changeset **072**) flags it for finance
  to re-allocate or refund; the buyer sees `underReview: true`. The plot is
  never automatically snatched back from whoever may have reserved it since.

Proven red: the grace period, ask-Paystack-first, and abandoned-is-not-final.

### Step 7 — finance verification and allocation (FV-1..FV-3)
`GET /api/portal/finance/transactions`, `POST …/{id}/verify`,
`POST …/{id}/reject` `{ reason }` — `portal.payments.verify` (changeset
**073**: `finance_officer`, `executive_director`). `FinanceVerificationService`
(payments) over `CheckoutApi.awaitingFinance` / `verifyAndAllocate` /
`rejectPayment`.

- **Decided with the user: the developer's own finance staff verify.** They
  know their buyers and the plot is theirs; LandVault still holds the money
  until payout, which only follows verification.
- **The queue sets the agreed price beside Paystack's record** (amount paid,
  channel, paid time, card last 4, message) with `amountsMatch`. Company and
  branch scoping come from the database: the queue joins plots/estates/blocks
  under the caller's own RLS, so a branch-scoped officer sees only their
  branch's sales with no branch condition in the query.
- **Allocation is all or nothing (FV-3).** Under the reservation's row lock:
  status checked, then the plot `RESERVED → SOLD` (a once-only `UPDATE`; not a
  definer function — the caller is the selling company's own staff, so the
  plots policy permits it and still walls the branch), then the transaction
  `awaiting_finance → verified`, the reservation → `converted`, an audit entry
  naming the person. A refusal returns before anything is written; a failure
  after the sale throws and rolls the sale back (proven red: letting it carry
  on verified a transaction whose plot wasn't sold). Verifying twice → 409.
- **The human check backs the gateway**: verify also requires a `succeeded`
  payment for exactly the agreed amount and currency (`PAYMENT_NOT_CONFIRMED`
  otherwise; proven red).
- **Reject (decided with the user)**: transaction → `rejected`, the hold
  ends `released` and the plot goes back on sale (existing `endHold`), and every
  succeeded payment gets `review_reason` "Refund due: …" — refunds are manual
  for now, but can't be forgotten.
- **A diagnostic must never act.** The first draft found out *why* a purchase
  wasn't in the caller's queue by calling `verifyAndAllocate` — which would
  have sold the plot, skipping the payment check, had it been allocatable.
  Replaced by the read-only `CheckoutApi.financeStatusOf`.
- **Portfolio entry and deed don't exist yet** (decided with the user:
  allocation now is sold + verified + converted). **When the portfolio and
  documents modules are built, they must join `verifyAndAllocate`'s same
  transaction** — a plot sold with no deed, or a deed for a plot nobody
  allocated, is exactly the corruption FV-3 exists to prevent.
- Late-money (`underReview`) payments on abandoned purchases are not in this
  queue yet; resolving them (re-allocate or refund) is its own follow-up.

### Step 8 — payouts: the decisions (TR-1..TR-3)
Decided with the user, one at a time, before building:
1. **A Super Admin presses "pay out", per verified sale** (`admin.payouts.manage`).
   Money leaving can't be undone; the developer approved the sale (step 7),
   LandVault — which holds the money — releases it, so no one side can both
   approve and pay itself. Automation (a daily batch with the same skip rules)
   can come once it has run cleanly.
2. **The full agreed price is paid out**; LandVault absorbs Paystack's fees
   for now (₦2,000 on a ₦15M payment, read from the stored `charge.success`
   event — the `fees` field). A commission is an undecided business question,
   never an invented one.
3. **The Executive Director submits the bank account; a Super Admin approves
   it.** Payment diversion — swapping the account on file — is the cheapest
   theft of a developer's money, so approval sits outside the company.
4. **Paystack's transfer OTP stays ON** (first recommended off, changed): with
   it off, the secret key alone can empty the balance from anywhere, bypassing
   every control in this app. With a person already pressing each payout, the
   code costs almost nothing. Revisit only when automating.
5. **A failed or reversed payout keeps its row, flags the sale, and a person
   retries as a new attempt.** Never retry while the outcome is unknown
   (`pending`/`awaiting_otp`); at most one live payout per sale, enforced by
   the database.

### Step 8a — the payout account (changeset 074, 075)
`GET /api/portal/settlement/banks`, `GET/POST /api/portal/settlement/account`,
`POST …/account/{id}/withdraw` (`portal.settlement.manage` — Executive
Director only, company-wide); `GET /api/admin/settlement-accounts?status=`,
`GET …/{id}`, `POST …/{id}/approve`, `POST …/{id}/reject` `{ reason }`
(`admin.payouts.manage` — Super Admin). `SettlementAccountService`.

- **The request carries only a bank code and the 10-digit number.** The
  account name is what **the bank** returns (Paystack's resolve) — a typed name
  proves nothing. A number the bank can't find is `ACCOUNT_NOT_RESOLVED` (400),
  nothing saved.
- **`settlement_accounts`, one row per submission, never overwritten.**
  `pending` → `approved` / `rejected` / `withdrawn`; an approved account becomes
  `superseded` when replaced. At most one pending and one approved per company
  (partial unique indexes); only `approved` carries a recipient code, and an
  approved row must have one (CHECKs). RLS-policied in the same changeset; the
  app role can't DELETE or TRUNCATE.
- **The Paystack recipient is created at approval**, never at submission, so a
  rejected or junk submission never reaches Paystack. Paystack down → 503 and
  nothing changes. The old approved account keeps receiving payouts until the
  new one is approved.
- **Two warnings for the reviewer, never blocks (TR-2)**: `nameMatchesCompany`
  (the bank's name against the registered and trading names, after dropping
  case, punctuation and LTD/LIMITED/PLC-type words — `SettlementAccountNames`)
  and `matchesOnboardingAccount` (against `organization_financial`, null when
  nothing was declared). **`organization_financial` is never paid to** — nothing
  ever verified it — and is never written to here (decided with the user).
- **Every active company-wide Executive Director is emailed on submission**
  (`SettlementAlertSender`; `landvault.payments.alerts.delivery=log` in tests
  only), so a director who didn't ask can object before approval. The email
  carries the last 4 digits only.
- **Bank list**: fetched from Paystack (cursor-paged, active NUBAN banks) and
  kept in memory for a day (`PaystackBankDirectory`); a failed refresh falls
  back to the older list.
- One submission waits at a time (`SETTLEMENT_ACCOUNT_PENDING`, 409 — withdraw
  first); resubmitting the approved account is `SETTLEMENT_ACCOUNT_UNCHANGED`.
- Audit: `settlement_account.submitted|approved|rejected|withdrawn`.

Proven red: superseding removed (the unique index refused the second approval),
the pending check removed, and company isolation — with the service's own
company filter removed the other company still got 404 (the database walls it);
with the row security removed as well it went red.

### Step 8b — sending a payout (changeset 076)
`GET /api/admin/payouts/ready`, `GET /api/admin/payouts?status=`,
`POST /api/admin/payouts` `{ transactionId }`, `POST …/{id}/otp` `{ otp }`,
`POST …/{id}/resend-otp`, `POST …/{id}/cancel` — `admin.payouts.manage`.
`PayoutService` + `PayoutRecorder`. Decided with the user: A, A, A, A below.

- **Only a verified sale is owed** (`CheckoutApi.verifiedSales`); the amount
  is the transaction's agreed price and the destination the company's approved
  account — neither is ever in the request. Each attempt records the account
  and recipient it used, so a later account change doesn't rewrite history.
- **The row is committed as `sending` BEFORE Paystack is asked.**
  `PayoutRecorder` methods each run in their own short transaction and
  `PayoutService.send` deliberately has none, so a reply lost in transit leaves
  a record (proven red: wrapping `send` in one transaction lost the row).
- **A lost reply (decision 1)** → 503 `PAYOUT_OUTCOME_UNKNOWN`, the payout stays
  `sending`. Sending again **asks Paystack about that reference first**
  (`/transfer/verify/{reference}`) and adopts its answer; only if Paystack has
  never seen it is the transfer started again — **with the same reference**,
  which Paystack refuses to accept twice. Never a fresh reference (proven red:
  without asking first, a second transfer was started).
- **At most one live payout per sale** (`sending`, `awaiting_otp`, `pending`,
  `success`): the service checks under a row lock, and a partial unique index
  backs it (proven: with the code check removed the index still refused; with
  both removed the sale was paid twice).
- **The OTP (decision 4 of step 8)**: Paystack replies `otp` → `awaiting_otp`
  with its `TRF_…` code; the Super Admin enters the code Paystack sent the
  account owner → usually `pending`. The code goes straight to Paystack: never
  stored, never logged (`PayoutOtpRequest.toString` masks it; a test searches
  every payout and audit row for it). If finalising fails or gets no answer,
  Paystack is asked for the transfer's state, so a code that went through
  before the reply was lost isn't reported as wrong; a genuinely wrong code is
  400 `OTP_REJECTED` and the payout keeps waiting. Resend calls `resend_otp`
  with reason **`transfer`** — Paystack's OpenAPI also lists `resend_otp` as a
  reason, but the live API refuses it ("Reason is invalid. ['disable_otp' or
  'transfer']"). Found in the first live test; the spec is not the API.
- **Sending requires the sender's own confirmed 2FA (decision 2)** —
  `TWO_FACTOR_REQUIRED` otherwise (`IdentityApi.hasConfirmedTwoFactor`, both
  flags). Proven red. Cancelling doesn't need it — cancelling moves no money.
- **Held, not paid (decision 3)**: a company not `ACTIVE`
  (`COMPANY_NOT_ACTIVE`) or with no approved account
  (`NO_APPROVED_PAYOUT_ACCOUNT`); both show as `blockers` on the ready list.
- **Cancel (decision 4)**: only `awaiting_otp`; nothing was sent, since
  Paystack moves money only once the code is entered. The sale is owed again.
- **A refusal** (e.g. balance too low) → 502 with Paystack's message, the
  attempt `failed` and kept; the sale returns to the ready list with
  `lastAttempt`, and a retry is a new row with a new reference.
- Paystack status mapping (`PayoutStatus.fromPaystack`): otp, success, failed,
  reversed map directly; **anything unrecognised is `pending`** — "not
  finished", never a failure that would free the sale for a second payment.
- Audit: `payout.started`, `payout.awaiting_otp|pending|success|failed|reversed`,
  `payout.otp_resent`, `payout.cancelled`.
- `payouts` is RLS-policied (platform staff all; a company may read its own
  for a future portal view), and the app role can't DELETE or TRUNCATE.
- **Not yet (8c)**: the final outcome — `transfer.success|failed|reversed`
  webhooks, a check with Paystack, and a sweep for payouts stuck `pending`.

### Paystack's per-transfer cap: a sale is paid in equal parts (changeset 077)
Found live, not from the docs: the first real payout (₦15,000,000) was refused
with "The maximum you may send in a single transfer at this time is:
10,000,000.00 NGN". Paystack's own article on raising it
(support.paystack.com/en/articles/2169474) gives no figures; it lists what they
check before raising a limit — industry and volume, a clear use case,
**app-based 2FA for every dashboard user**, **transfer approval by OTP/URL**
(why keeping the OTP on was right), and **IP whitelisting for API access**.
**TODO at deployment**: whitelist the server's IPs in the Paystack dashboard —
it also makes a stolen secret key useless off the server.

Decided with the user — split, never move money outside Paystack:
- **`landvault.payouts.max-transfer-amount`** (₦10,000,000). A sale above it is
  paid in the fewest parts that fit, **split evenly**, leftover kobo on the last
  (`PayoutSplit`): ₦15M → 2 × ₦7.5M, ₦30M → 3 × ₦10M, ₦31M → 4 × ₦7.75M. The
  parts always add up exactly to the sale (unit-tested with odd kobo).
- Each part is its own payout row (`part_number`/`part_count`), its own
  reference, its **own OTP**. "Pay out" sends the next unpaid part; one part in
  flight at a time per sale.
- **The split is fixed by the first part paid** and copied by every later part,
  so a cap raised halfway can't change what the parts add up to (proven red by
  always following the current cap). While nothing has been paid, it follows
  the current cap.
- **Never pay twice moved from the sale to the part**: a part can be live or
  paid once (`uq_payouts_one_live_per_part`, replacing 076's per-sale index).
  Proven: with the code's in-flight check removed the index still refused;
  with both removed the part was paid twice.
- A failed part is retried alone; a paid part is never sent again. The sale is
  paid when every part has succeeded (`SALE_ALREADY_PAID` after that); the ready
  list shows `amountOwed`, `partsPaid`, `nextPart`, `nextPartAmount`.
- `GatewayRefused.paystackMessage()` carries Paystack's words without our
  prefix — the first live refusal showed "Paystack refused the request:" twice.

### Step 8c — how a payout actually ended (TR-3, changeset 078)
`PayoutOutcomeService.refresh(reference)`, prompted two ways: a signed
`transfer.success|failed|reversed` webhook, and `PayoutSweeper` (every
`sweep-interval` 15m: `sending` older than `stuck-sending-after` 5m, `pending`
not updated for `stuck-pending-after` 30m). Plus `GET /api/portal/payouts`
(`portal.payments.verify`, company-wide, read-only). Decided with the user:
A, A, A, A.

- **The webhook is a prompt, never proof** (decision 1): refresh asks
  Paystack's verify and records its answer. Proven red: taking the event's word
  turned an unconfirmed "success" into a recorded one.
- **Paystack's latest verified word wins** (decision 2): `pending → success`,
  and `success → failed/reversed` too — that part is owed again (back on the
  ready list) and every active Super Admin is emailed. Proven red by treating
  success as final. An ambiguous reply never steps a success back to pending.
- **The rare one**: a payout we closed (failed/reversed/cancelled) that
  Paystack says succeeded. With no other live attempt for that part, the truth
  is recorded (`success`) and flagged; with one, the old row stays as it is (one
  live attempt per part) and `review_reason` says the part **may have been paid
  twice** — both alert the Super Admins. `payouts.review_reason` (078) holds it.
- **Never reached Paystack**: `sending` past the threshold and unknown to
  Paystack → `failed`, nothing moved, the part owed again.
- **Both paths set the platform scope**, because `payouts` is platform-scope
  only under RLS: the sweeper like the other sweepers, and the webhook for
  exactly the refresh call, after the signature check (proven red without it —
  the webhook silently found no payout).
- **The sweep's query runs in a transaction** (`stuckReferences`). First built
  calling the repository straight from the sweeper — outside a transaction no
  tenant/platform GUC is set, RLS hid every row, and the sweep found nothing,
  silently. Caught by the restricted-role IT; the same trap the plot-import
  template hit. **Any repository read on an RLS table needs a transaction.**
- Audit entries are system entries (`payout.success|failed|reversed`, actor
  "System"): Paystack decided, no person did.
- **Seen live**: Paystack's finalize reply says "Transfer has been queued" with
  status `success`, and the `transfer.success` webhooks arrived seconds later.
- **Seen live (test mode)**: `transferred_at` is **null** even on a successful
  transfer, in the webhook and in verify, and `fee_charged` is 0. `transferredAt`
  is therefore left null — deliberately not filled from Paystack's `updatedAt`,
  which records when Paystack changed the row, not when money landed. Recheck in
  live mode; the transfer fee is only knowable there.

### `payments.last4` (changeset 079)
Was `card_last4` / `cardLast4`. For a bank-transfer payment Paystack reports
the paying account's last digits (seen live: "X890"), so "card" was false
whenever the buyer didn't use a card. Read it with `channel`. `card_bin` was
left as is (internal only; same caveat).

### Refunds (changeset 080)
`GET /api/admin/refunds/due`, `GET /api/admin/refunds?status=`,
`GET …/{id}`, `POST /api/admin/refunds` `{ paymentReference }`,
`POST …/{id}/send-to-account` (`admin.payouts.manage` + the sender's 2FA);
buyer: `GET /api/payments/{reference}/refund`, `GET /api/payments/banks`,
`POST /api/payments/{reference}/refund-account` `{ bankCode, accountNumber }`
(`client.checkout.reserve`, own payment only). `RefundService`,
`RefundRecorder`, `RefundOutcomeService`, `RefundSweeper`. Decided with the
user: A, A, A, A.

- **What is owed is explicit**: `payments.refund_requested_at`, set when
  finance rejects a paid purchase (late money will set it too). Existing
  "Refund due" flags were carried over in the changeset.
- **A Super Admin sends it** (decision 1) — LandVault holds the money; the
  developer's rejection only asks.
- **The full amount paid** (decision 2): these buyers never got the plot;
  LandVault absorbs Paystack's collection fee. Estate refund terms (e.g. a 20%
  withdrawal deduction) belong to a buyer who OWNED the plot — portfolio's job.
  Proven red by refunding half.
- **Once**: one live refund per payment (unique index, `status <> FAILED`); a
  failed refund frees the payment for another try. Paystack itself also never
  refunds more than was paid — which is what makes resending a `sending` refund
  safe (a duplicate is refused, not paid).
- **Bank-transfer payments come back `needs-attention`** (decision 3): Paystack
  can't return them on its own. The buyer is emailed and gives an account in
  their app; the bank supplies the name, and the reviewer sees
  `accountNameMatchesBuyer` (every word of the buyer's registered name in the
  bank's name, any order — a warning, never a block). The Super Admin then sends
  it (`retry_with_customer_details`, which takes Paystack's numeric **bank
  `id`**, not the code — the bank list now keeps both). Only the buyer can
  submit it for their own payment (proven red).
- **The payment stays `succeeded`** (decision 4); the refund row says what came
  back. When Paystack reports `processed`, `review_reason` is cleared
  (`refund_requested_at` stays as history) and the buyer sees it.
- **Outcome**: `refund.*` webhooks name the PAYMENT (`transaction_reference`),
  not our refund, so the refund is found through its payment; then Paystack's
  refund is fetched and its answer recorded — the webhook's word is never taken
  (proven red). `RefundSweeper` (15m) fetches refunds pending/processing past
  `landvault.refunds.stuck-after` (30m). A failed refund emails the Super Admins.
- `refunds` is buyer-owned like `payments`: not RLS-policied; the app role can't
  DELETE or TRUNCATE it.
- **Seen live (test mode)**: a bank-transfer refund came back `pending`, not
  `needs-attention`, with `expected_at` 11 days out — and Paystack sent
  `refund.processed` 15 minutes later (with a real `refunded_at`). The
  needs-attention path is covered by tests only; watch for it in live mode.

### Late money: allocate or refund
`GET /api/portal/finance/late-payments`, `POST …/{transactionId}/allocate`,
`POST …/{transactionId}/refund` `{ reason }` (`portal.payments.verify`).
`LatePaymentService`; `CheckoutApi.abandonedSales` / `allocateAbandoned`.
Decided with the user: A, A, A, A.

- A late payment is a `succeeded` payment still flagged (`review_reason`), not
  yet sent to refund, whose purchase is `abandoned` (step 6 put it there).
- **The selling company's finance decides** (decision 1); the refund itself is
  still sent by LandVault's Super Admin.
- **Allocate is one all-or-nothing step** (decision 2): Paystack's confirmed
  amount must equal the agreed price, then the plot goes `AVAILABLE_* → SOLD`
  (`PlotLockGateway.sellAvailable`, a once-only update under the officer's own
  RLS) and the purchase `abandoned → verified`. If anyone reserved, bought or
  withheld the plot meanwhile, nothing changes (`PLOT_NO_LONGER_AVAILABLE`) —
  proven red by letting a reserved plot be sold. The old hold stays `expired`
  as history. The sale then joins "ready to pay out" like any verified sale.
- **The price the buyer agreed and paid is honoured** (decision 3), even if the
  tier was repriced since; a developer who won't accept it refunds instead.
- **Refund** sets `refund_requested_at` and the reason; the payment joins the
  Super Admins' refunds-due list (proven red without it). The plot is untouched.
- **The buyer is emailed either way** (decision 4).
- Another company's finance can neither see nor act on it (the seller filter,
  and the RLS-joined read behind it).

## One password rule, wherever a password is set (`PasswordPolicy`)

`identity.internal.service.PasswordPolicy` is the only place the rule lives,
called from register, reset, change and invitation accept — never from login,
so existing weak passwords keep working and nobody is forced to reset.
**It must stay identical to the frontend's `src/lib/passwordPolicy.ts`**: same
checks, same order, same sentences. Changing one means changing both, or the
screen accepts what the server refuses (or the reverse).

The rule, first failure reported: 8+ characters; at most 64 characters; at
most 72 **bytes** (BCrypt can't hash more, and Spring Security 7 throws — a
500 — rather than truncating); a letter `[a-zA-Z]` and a digit; not in the
common list, compared whole and as its "core" (leading/trailing non-letters
stripped, so `password123!` is `password`); not one repeated character; not
starting `0123`/`1234`/`abcd`; not containing the email's local part, first
name or last name (each 3+ characters). Refusal: 400 `WEAK_PASSWORD` with the
sentence as `message` and under `fieldErrors.<field>` (`password` for register
and accept, `newPassword` for reset and change). No forced symbols or capitals.

- **One deliberate addition to the frontend's sentences**: a password under 64
  characters but over 72 bytes (40 accented letters) gets "That password is
  too long — use fewer accented or special characters." "Use at most 64
  characters" would have been false for it. The frontend never produces this
  case today (its input stops at 64 characters, and ordinary text stays well
  under 72 bytes).
- **Reset checks the code first, then the rule.** The rule's "don't use your
  name" answer would otherwise let anyone without a code probe an account's
  owner by email. Nothing is written before the rule passes, so a weak attempt
  leaves the code usable. Invitation accept is the same: refused before the
  token is consumed.
- **Change** verifies the current password first (401 as before), then the
  rule, then refuses the current password reused ("Choose a password
  different from your current one.").
- **Bootstrap**: a password breaking the rule only logs a warning (the account
  must change it at first sign-in) — but one over 72 bytes fails startup
  clearly, since BCrypt can't hash it and the account could never exist.
- The test suite's shared password became `correct horse battery staple 9` —
  the old one had no digit.

Proven red: the rule removed from reset and from accept, and the
same-as-current check.

## Change password verifies a password; reset verifies a mailbox

`POST /api/auth/change-password` is authenticated and takes
`{ currentPassword, newPassword }`. It exists because completing a change via
`forgot-password` + `reset-password` checks the wrong thing:

- A change-password screen means *"prove you know the password you are
  replacing"*. A reset proves control of an inbox, which is a different
  claim — and the right one only when someone is locked out.
- **A bootstrapped Super Admin needs working email before they can retire a
  temporary password.** On a fresh deployment with no mail configured, the
  first admin would be stuck on a credential that has been through an
  environment variable and a shell history.

`newPassword` carries the same `@NotBlank` as registration, reused rather
than restated — a password acceptable when an account was created does not
become unacceptable when it is changed, the same reasoning
`ResetPasswordRequest` already records.

On success: every other session's refresh tokens revoked, a fresh cookie for
this device (see the cookie section), `mustChangePassword` cleared, an audit
entry written. A wrong current password returns the same
`InvalidCredentials` a bad password gets at login — nothing about it warrants
its own code. No timing-equalisation dummy hash is needed here, unlike
`login()`: the caller is already authenticated, so there is no account to
enumerate.

**"Sessions revoked" means refresh tokens**, as everywhere else: an
already-issued access token rides out its remaining lifetime, up to 15
minutes. `AuthenticationIT` asserts on refresh rather than on `/api/me` for
exactly that reason — asserting the access token died would be asserting
something untrue.

This also closes the open TODO recorded under "The first Super Admin is a
deployment step": there is now an endpoint to change the bootstrap password.
**Login is still not blocked on `mustChangePassword`** — that remains
deliberate, since blocking both it and `mustSetUpTwoFa` while neither can be
satisfied is an unrecoverable account.

## A pending purchase keeps its hold — the double-sale bug, and why not `converted`

Found while reviewing the inventory-editing stories, not by a test: opening a
transaction left its reservation `ACTIVE`. Forty-five minutes later the expiry
sweep treated the hold as abandoned and **returned the plot to the pool while
its purchase was still pending** — a second buyer could then reserve and start
buying the same plot. The buyer's own `DELETE /api/reservations/{id}` had the
same hole. Every existing test passed, because none of them expired or
cancelled a hold *after* opening a transaction.

**The fix: a hold with a transaction behind it is never released** — the
sweeper skips it, and a buyer's cancel is refused with `PURCHASE_IN_PROGRESS`
(409). Abandoning a pending purchase is a transaction-level action, and no
such route exists yet.

**Why not mark the reservation `converted` instead**, which was the first
idea: the frontend already defines `converted` as *finance verified the
payment and the plot is sold* (`reservationService.ts`, `convertReservation`).
Using it for an unpaid transaction would report a plot as sold that isn't —
the enum's own Javadoc says nothing in `checkout` sets it, and that stays
true.

**Row locks, not a check-then-act.** Opening a transaction, the buyer's
cancel and the sweep all take `PESSIMISTIC_WRITE` on the reservation row
before deciding, and the "does a transaction exist?" check is a separate
statement run *after* the lock is held — under `READ COMMITTED` that sees
anything committed while it waited. A transaction and an expiry racing for
the same hold therefore serialize: either the transaction commits first and
the sweep skips the hold, or the sweep closes it first and the transaction
is refused as expired.

**Known cost, accepted:** such a reservation stays `ACTIVE` with a past
`expires_at`, so the sweep's candidate query keeps selecting (and locking)
it every minute until finance exists to move it on. Harmless at today's
volume; revisit when finance lands, which is also when these holds finally
get a real terminal state.

`aHoldWithAPurchaseInProgressIsNeverSweptBackIntoThePool` was proven red by
removing the sweeper's check.

## Inventory editing, slice 1: tier price, label and size; block names

`PUT /api/portal/estates/{id}/price-tiers/{tierId}`,
`GET .../price-tiers/{tierId}/impact`, `PUT .../blocks/{blockId}`. Plot
footprint correction and tier reassignment are slice 2; status changes, bulk
updates, plot withdrawal and tier retirement are slice 3. Every field in the
PUT bodies is optional — left out means unchanged.

### A reservation captures price but not size — so a size change reaches available plots only

`reservations`/`transactions` store `base_price`, `corner_premium_pct`,
`total_price` and `currency`, and **no size**. `plots.nominal_size_sqm` is
copied from the tier at creation and read live after that — and it is what
appears on a deed. Pushing a tier size change to every plot would leave a
buyer who reserved a "500 sqm" plot at a locked price holding a "450 sqm" one.

So a size change is **one native `UPDATE` restricted to `AVAILABLE_DEV`/
`AVAILABLE_INV`** (`PlotRepository.resizeAvailablePlotsOnTier`). One
statement, not load-and-save, because that is the concurrency guarantee: a
plot being reserved at the same instant holds its row lock, and the update
waits, re-checks the status and skips it. The response reports how many
plots changed and, by status, how many kept the previous size.

**The follow-on problem, and changeset 060.** A plot skipped because it was
held would later return to the pool still carrying the *old* size while its
tier says the new one. `landvault_release_plot` now resets a `LAND_SIZE`
plot's size from its tier as it releases it (`UNIT_TYPE` plots keep their
override). It reads the tier `FOR SHARE`: a size edit holds the tier row's
lock (the tier is saved and flushed *before* the plots are touched), so a
release racing an edit waits and reads the new size rather than the old.

**Follow-up, not built:** capturing size on the reservation itself would be
more correct, but it touches the reservation model for one edge case.

### `tierType` and `currency` are immutable

A different type or currency is a different tier, not an edit. The PUT
accepts the *current* value (so a client can echo a tier back) and refuses a
different one with `TIER_TYPE_IMMUTABLE`/`TIER_CURRENCY_IMMUTABLE` (400) —
refused, never silently dropped, because an ignored field reads as success.
To fix a tier that is wrong in that way: create a new tier and move the plots
(slice 2's IE-10).

### Price changes need no new protection

A buyer's price is captured on the reservation and copied to the transaction;
nothing recomputes it. The edit does not touch either table.
`aBuyerWhoReservedKeepsTheirCapturedPrice` proves it through the real
endpoint (the older `repricingTheTierAfterAHold...` test used raw SQL).

### Audit, and the no-op rule

Every edit writes one audit entry (`estate.price_tier_updated`,
`estate.block_updated`) with the previous and new values in the free-text
`detail` column — there is no structured previous-value field, so the text is
the record. An edit that changes nothing writes nothing, the same rule
publish/unpublish follow.

### Permission follow-up

Edits sit behind `portal.estates.manage` (Executive Director, surveyor).
**A surveyor can therefore reprice an estate while a sales manager cannot.**
Splitting price editing into its own slug needs a seeded permission and a
frontend change — not done here. The impact preview is a read and uses
`portal.estates.view`.

### Note for slice 3: retiring a tier cannot reuse `deleted`

Tiers are soft-deleted via `@SQLRestriction`, so a "deleted" tier vanishes
from every lookup and its plots lose their price entirely. Retirement needs
its own column (e.g. `retired_at`), with the existing plots keeping their
reference.
