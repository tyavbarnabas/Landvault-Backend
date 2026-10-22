# API contract (stub, derived from the frontend)

**Status: not yet implemented anywhere.** This is a reverse-engineered contract read off every exported function in `~/landvault/src/services/*.ts` — the frontend's service layer already calls these shapes conditionally (`if (apiClient.isMockMode) { …mock… } else { …real fetch, mostly unfilled… }`), so this doc is the target for that "else" branch, not a description of something live. Treat paths as proposed, not fixed — adjust freely, but update the corresponding service file in the frontend repo (and this doc) together so the two never drift apart silently.

Base path convention below: `/api/...`. Auth: `Authorization: Bearer <token>` on everything except login/register. Pagination envelope and error body are defined in `AGENTS.md`.

## Auth — `authService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/api/auth/login` | `{ email, password }` | `{ user: AuthUser, token }` |
| POST | `/api/auth/register` | `RegisterInput` | `{ user: AuthUser, token }` |
| POST | `/api/auth/refresh` | **no body** — the `lv_refresh` cookie | `{ user: AuthUser, token }` + a new cookie |
| POST | `/api/auth/logout` | **no body** — the `lv_refresh` cookie | 204, cookie cleared |
| POST | `/api/auth/forgot-password` | `{ email }` | `{ message }` — always 200, always the same body |
| POST | `/api/auth/reset-password` | `{ email, code, newPassword }` | `{ message }` |
| POST | `/api/auth/2fa/setup` | — (authenticated) | `{ secret, otpAuthUri }` — does **not** enable 2FA |
| POST | `/api/auth/2fa/confirm` | `{ code }` (authenticated) | `{ recoveryCodes }` — shown once, enables 2FA |
| POST | `/api/auth/2fa/verify` | `{ challengeToken, code }` | `AuthResponse` — the second login step |
| POST | `/api/auth/2fa/disable` | `{ code }` (authenticated) | `{ message }` |
| POST | `/api/auth/2fa/recovery-codes/regenerate` | `{ code }` (authenticated) | `{ recoveryCodes }` |

### Register: the phone is E.164 (built)

`RegisterInput.phone` must be **E.164** — a plus, the country code, then the
national number without its domestic 0, no spaces: `+2348030000000`,
`+447700900123` (`^\+[1-9]\d{7,14}$`). Anything else, or a missing phone, is
400 `{ code: "VALIDATION_ERROR", fieldErrors: { phone: "Enter your number with
its country code, e.g. +2348030000000." } }`. Stored exactly as sent and printed
as stored on allocation letters. Accounts registered earlier keep whatever they
stored; sign-in and buying are unaffected. Register is the only route where a
person sets their own phone (no profile update exists; invitations take none).

### The buyer's address (built, changeset 097)

| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/me/address` | — | `Address`, or 204 when none yet |
| PUT | `/api/me/address` | `{ line1, line2?, city, state, postalCode?, country }` | `Address` |

Both need `client.checkout.reserve`. `Address` is `{ line1, line2, city, state,
postalCode, country, countryName, updatedAt }`; the sign-in, register, 2FA-verify
and refresh `user` carries it as **`address`** (null when none). For `country:
"NG"` the state must be one of the 36 states or the FCT ("Lagos State", "FCT" and
"Abuja" are accepted) and comes back as the canonical name; otherwise 400
`UNKNOWN_STATE` with `fieldErrors.state`. Other countries' states are free text.
An unknown two-letter code is 400 `UNKNOWN_COUNTRY`; missing parts are
`VALIDATION_ERROR` with `fieldErrors`. Sign-up doesn't ask for it: checkout does
(see `ADDRESS_REQUIRED` under Checkout). It prints on the allocation letter.

### Refresh token: `HttpOnly` cookie only — frontend changes required (built)

The refresh token is set by login, register, `/2fa/verify` and refresh as an `HttpOnly; SameSite=Strict; Path=/api/auth` cookie named `lv_refresh`, and **appears in no response body**. See AGENTS.md for why.

- **`authService.ts`**: remove `refreshToken` from the login/verify response type; never store one. Every auth call needs `credentials: "include"` so the browser keeps the cookie (login, register, 2FA verify — not just refresh).
- **Restoring a session on page load**: call refresh once at startup. `{ user, token }` back means signed in, with the same `user` login returns. `REFRESH_TOKEN_MISSING` means nobody is signed in on this browser. Don't start as the mock user when connected to a real backend.
- **Refresh can also return 403** `ACCOUNT_SUSPENDED` / `ACCOUNT_DEACTIVATED` / `TENANT_NOT_ACTIVE`: sign out and show the reason.
- **Change-password sets a fresh cookie** for this browser, so the user stays signed in here. Other devices are signed out at their next refresh.
- **`apiClient.ts`**: refresh already sends no body with `credentials: "include"` — keep that. Remove the TODOs that assumed a body.
- **Two 401s from refresh**: `REFRESH_TOKEN_MISSING` (never signed in on this browser) vs `INVALID_REFRESH_TOKEN` (session ended — including theft detection having revoked every session). Either way: **sign out cleanly, do not retry, do not loop.**
- **Serialise refresh across tabs with `navigator.locks`** (e.g. `navigator.locks.request("lv-refresh", …)`), and take the same lock around logout. The per-tab single-flight isn't enough on its own. The server's 10-second grace window keeps two racing tabs signed in, but the lock is the primary fix.
- **Logout**: call `POST /api/auth/logout` (Origin-checked, needs no access token), then drop the access token locally. It ends **this** browser's session only; the access token still works server-side for up to 15 minutes, so discard it.
- **Login, `/2fa/verify` and change-password return 401 as an answer, not an expired session.** Only change-password passes `skipAuthRefresh` today; login and 2FA verify must too, or a typo triggers a refresh attempt and can sign the user out.
- **Origin**: requests must come from an origin in the backend's `CORS_ALLOWED_ORIGINS` (default `http://localhost:8443`). The Vite dev server matches. A different port or host gets 403 `ORIGIN_NOT_ALLOWED` on refresh/logout.

`AuthUser`: `{ name, email, phone, country, currency, kycStatus, kycType, twoFAEnabled, role: "client"|"super_admin", permissions: string[] }`

The two password-reset routes are **built** (unlike most of this document), and back the frontend's existing `/forgot-password` route, which previously pointed at nothing. Two things the frontend must not assume:

- `forgot-password` reveals nothing about whether the account exists — same status, same body, every time. Don't render "no account with that email"; there is no such response to render.
- `reset-password` returns one generic `400 INVALID_OR_EXPIRED_CODE` for every failure (wrong code, expired, already used, attempt limit exhausted, no such account). Don't try to distinguish them in UI copy — the backend deliberately doesn't tell you which it was. Prompt the user to request a new code.

See AGENTS.md for the neutral-response rule and the honest scope of "sessions revoked" on reset.

**`/api/auth/login` now has two possible success shapes.** With 2FA off it returns `AuthResponse` exactly as before — no change. With 2FA on it returns `{ twoFactorRequired: true, challengeToken, expiresAt }` and **no tokens of any kind**; the client then posts that challenge plus a TOTP or recovery code to `/api/auth/2fa/verify` to get the real `AuthResponse`. The two shapes share no field names, so branch on `twoFactorRequired` rather than probing for `token`.

`AuthUser` gains two fields: `mustSetUpTwoFa` (platform staff who haven't confirmed 2FA — route them to setup; login is deliberately not blocked, or the bootstrapped admin would be stranded) and `recoveryCodesRemaining` (0 when 2FA is off). Recovery codes are returned in plaintext only by `2fa/confirm` and `2fa/recovery-codes/regenerate`, once — show them and tell the user to store them, because they cannot be retrieved again.

## Estates — `estatesService.ts` (mockData.ts: `Estate`, `Plot`)
| Method | Path | Response |
|---|---|---|
| GET | `/api/estates` | `Estate[]` |
| GET | `/api/estates/{id}` | `Estate` |
| GET | `/api/estates/{id}/price-tiers` | `PriceTier[]` |
| GET | `/api/estates/{id}/construction-progress` | `ConstructionProgress` \| 204 |
| GET | `/api/estates/{id}/reviews` | `Review[]` |
| POST | `/api/estates/{id}/reviews` | `{ author, rating, comment }` → `Review` |
| POST | `/api/reviews/{id}/like` | `{ liked }` → `{ likes }` |

`Estate` is the one canonical model (tenant/branch-owned; a `published` flag plus tenant verification state gates whether it appears on the marketplace) — see `~/landvault/src/data/mockData.ts` for the full field list, including `footprint: GeoPoint[]` (real polygon, backs PostGIS conflict detection) and `agisRegistration`/`encroachmentStatus` (`VerificationCheck`, optional — absent means "never checked," not "failed").

### Estate creation (tenant portal) — **built**

Creation lives under `/api/portal/estates` and requires `portal.estates.manage`. The *buyer-facing* read endpoints above are still unbuilt; the tenant portal's own reads are built — see the next section.

| Method | Path | Request |
|---|---|---|
| POST | `/api/portal/estates` | **`name` and `state` are required**; description, area, city, address, `cornerPremiumPct`, intent, amenities, `branchId`, optional `footprint` |
| POST | `/api/portal/estates/{id}/blocks` | `{ name, label }` |
| POST | `/api/portal/estates/{id}/price-tiers` | `{ tierType, sizeSqm?, price, currency, label }` |
| POST | `/api/portal/estates/{id}/plots` | `{ plots: [...] }` — a **batch**, max 500 |

The title and due-diligence checks have their own sections: see "Estate titles" below. Developers no longer record verification checks.

Four things the frontend must get right:

- **`footprint` is GeoJSON with `[longitude, latitude]` coordinates** — *not* Leaflet's `[latitude, longitude]`. The frontend owns that conversion. A ring must be closed (first position repeated last) and have at least 4 positions. Coordinates outside Nigeria are rejected, but note that guard cannot catch every transposition — see AGENTS.md.
- **Never send `tenantId`** — it's taken from the authenticated session and any value in the body is ignored. `branchId` is required only when the caller's role is organization-wide.
- **`state` is required.** Beyond being location data, it's the precondition for validating a boundary against that state's bounding box — the only way to catch a transposed coordinate that still lands inside Nigeria. Send the canonical name from `nigerianStates.ts`.
- **Don't send a plot's nominal size** — it comes from the plot's price tier. The one exception is `nominalSizeSqmOverride` on a `UNIT_TYPE` tier, which has no size of its own.

### Estate reads (tenant portal) — **built**

Reads require `portal.estates.view`, a **separate** slug from `portal.estates.manage` — neither implies the other, so a sales or finance user can browse the inventory without being able to define it.

| Method | Path | Returns |
|---|---|---|
| GET | `/api/portal/estates` | `Page<EstateSummary>` — filters `state`, `published`, `intent`, `branchId`, `q`, plus `limit`/`cursor` |
| GET | `/api/portal/estates/{id}` | `EstateDetail` — estate + blocks + price tiers + title + verification checks + plot counts |
| GET | `/api/portal/estates/{id}/plots` | `Page<PlotDetail>` — filters `status`, `blockId`, `priceTierId`, `isCorner`, `propertyType`, `listingIntent` |
| GET | `/api/portal/estates/{id}/plots/{plotId}` | `PlotDetail` |
| GET | `/api/portal/estates/{id}/geojson` | GeoJSON `FeatureCollection` — the estate boundary and every plot boundary |

What the frontend should know:

- **Which rows come back is decided by the database, not a query parameter.** A branch manager sees only their own branch's estates and gets a **404** — not a 403 — for a sibling branch's estate id, because the row is removed before the query runs. Same for another tenant's estate. There is no parameter that widens this.
- **A plot's price is computed, never stored.** `PlotDetail` returns `basePrice` (the tier's price), `cornerPremiumPct` (null unless this plot is a corner) and `price` (the result) together — render `price`, but `basePrice`/`cornerPremiumPct` are there so a corner plot's figure can be explained rather than appearing to match no tier on the price list.
- **`pricePerSqm` is `null`, not `0`, for a `UNIT_TYPE` plot.** An apartment has no exclusive land area, so it has no rate. Don't fall back to `actualAreaSqm` to manufacture one.
- **`plotCounts.byStatus` only contains statuses that actually occur.** An absent key means zero; render your own zero for a chip you always want shown. `total: 0` with an empty map is a real estate with no plots.
- **GeoJSON coordinates come back as `[longitude, latitude]`** — the same order they were posted in, byte for byte. Convert for Leaflet on the frontend, as with input.
- **Features with no boundary are omitted from the `FeatureCollection`**, never emitted with a null geometry (which most clients draw as a point at `[0, 0]`). A plot missing from the GeoJSON hasn't been surveyed yet; check `hasFootprint` on the plot list if you need to show that state.
- `footprintAreaSqm` is genuine **square metres** (geography cast), and `null` when the estate has no boundary — never zero.

### Listing conflicts (Super Admin) — **built**

Requires `admin.marketplace.conflicts` (seeded since changeset 014, granted to `super_admin`).

| Method | Path | Returns |
|---|---|---|
| GET | `/api/admin/listing-conflicts` | `Page<ListingConflict>` — filters `severity`, `status`, `conflictType` (comma-separated for the first two), plus `limit`/`cursor` |
| GET | `/api/admin/listing-conflicts/{id}` | `ListingConflict` |
| POST | `/api/admin/listing-conflicts/{id}/status` | `{ status, reason }` → the updated `ListingConflict` |

- **Sorted worst-first and not re-sortable** — severity (`high` before `medium`), then largest overlap. A queue whose job is "look at genuine fraud risk first" should not be sortable into an order that buries it.
- **`status` transitions**: `open` → `investigating` → `confirmed_duplicate` | `dismissed`. A decision (the last two) **requires `reason`**; `investigating` does not. `auto_resolved` is rejected as a manual transition — the system sets it when geometry stops overlapping. Closed conflicts cannot be re-decided.
- **`severity`**: `high` = two different companies claim the same ground. `medium` = one company's own boundaries overlap (a survey error).
- Every transition writes an audit entry **against each company involved** — two for a cross-tenant conflict, one for a same-tenant one.

Four differences from the frontend's current `listingConflictsService.ts`, all recorded in AGENTS.md:

- The review call is `POST .../{id}/status` with `{ status, reason }`; the frontend currently sends `POST .../{id}/review` with `{ decision, note }`.
- `auto_resolved` is a **fifth** `ConflictStatus` the frontend's union doesn't have yet.
- `conflictType` is new: `estate_overlap` | `plot_overlap`. The frontend models estate conflicts only. **For a plot conflict, `estateAId`/`estateBId` hold plot ids** and `estateId` holds the containing estate — check `conflictType` before labelling them.
- `estateAFootprint`/`estateBFootprint` are **not** returned. Fetch geometry from the estate's own `/geojson` route if the map needs it.

### Estate conflicts (tenant portal) — **built**

`GET /api/portal/estates/{id}/conflicts` → `TenantConflict[]` (a plain array; one estate's conflicts are a bounded list). Requires `portal.estates.view`.

**This response deliberately cannot identify the other party.** It carries `yourEntityId`, `yourEntityLabel`, `overlapAreaSqm`, `overlapPctOfYours`, `severity`, `status`, `blocksPublication`, `guidance` and `detectedAt` — and no field for the counterparty's estate, company or id, because both sides believe they are right and the platform stays the intermediary. Don't build UI that implies the other company can be looked up; it can't.

- `guidance` is ready-to-display copy, written to be factual rather than accusatory — most conflicts are survey errors. Render it as-is rather than composing your own warning.
- `blocksPublication` is `true` for a `high` conflict or a `confirmed_duplicate`. A `medium` conflict warns and does not block.
- Another tenant's estate id returns `[]`, not a 403 — same non-disclosure reasoning as the 404s elsewhere.

### Publishing (tenant portal) — **built**

Requires `portal.estates.manage`.

| Method | Path | Returns |
|---|---|---|
| POST | `/api/portal/estates/{id}/publish` | `{ estateId, published, publishedAt, warningConflictCount, warning }` |
| POST | `/api/portal/estates/{id}/unpublish` | same shape, `published: false` |

- **Refusal is a 409** naming every failing condition in `message`; `code` is the first of, in this order, `PUBLICATION_VERIFICATION_PENDING`, `PUBLICATION_ENTITLEMENT_MISSING`, `PUBLICATION_TENANT_NOT_ACTIVE`, `PUBLICATION_FEES_UNDECLARED`, `PUBLICATION_REFUND_TERMS_UNDECLARED`, `PUBLICATION_BOUNDARY_MISSING`, `PUBLICATION_NO_PLOTS`, `PUBLICATION_NO_PHOTO`, `PUBLICATION_LETTER_NOT_APPROVED`, `PUBLICATION_CONFLICT_OUTSTANDING`. A conflict refusal never identifies the other company. The estate detail's `eligibility` carries each condition as its own boolean (`hasPhoto` and `letterApproved` included), so a readiness screen never has to attempt a publish to find out.
- **A MEDIUM conflict doesn't refuse** — publishing succeeds with `warningConflictCount > 0` and a `warning` to show.
- Publishing an estate that's already published re-checks everything. That's the way to learn why a published estate isn't on the marketplace (tenant suspended, a conflict arrived since).
- **`published` is the developer's intent and is never cleared by the platform.** If the company stops qualifying, its listings disappear from the marketplace and return on reinstatement, with no republishing.

### Estate photos (tenant portal) — **built**

All under `/api/portal/estates/{estateId}/photos`. Reads need `portal.estates.view`, everything else `portal.estates.manage`, on the caller's own company's estates (another company's is a 404). Branch staff can't change a company-level estate's photos (`ESTATE_READ_ONLY_FOR_BRANCH`, 403), as for every inventory write.

| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/uploads` | `{ fileName, contentType, sizeBytes }` | 201 upload ticket `{ fileId, uploadUrl, method, headers, expiresAt }` |
| POST | `/{fileId}/confirm` | `{ caption? }` (body optional) | 201 `EstatePhoto` (200 with the same photo if already confirmed) |
| GET | *(the base path)* | — | `EstatePhoto[]` in order |
| PATCH | `/{photoId}` | `{ caption? }` | `EstatePhoto` |
| PUT | `/order` | `{ photoIds }` | `EstatePhoto[]` in the new order |
| PUT | `/cover` | `{ photoId }` | `EstatePhoto[]` |
| DELETE | `/{photoId}` | — | 204 |

`EstatePhoto`: `{ id, url, caption, position, cover, fileType, sizeBytes, uploadedBy, uploadedAt }`.

- **Limits**: JPG, PNG or WebP (`FILE_TYPE_NOT_ALLOWED`); at most **5,242,880 bytes** (`FILE_TOO_LARGE`); at most **20 photos** per estate (409 `ESTATE_PHOTO_LIMIT_REACHED`, checked at `/uploads` and again at confirm). `sizeBytes` must be exact; storage refuses any other size.
- **Confirm removes the image's metadata** (GPS position, camera, dates), keeping only its orientation so phone photos stay upright. The cleaned file is stored under a new address, so the photo's `url` and `sizeBytes` differ from the upload's. A file that can't be read as the image it claims to be is refused (400 `IMAGE_UNREADABLE`) and deleted — upload it again. Only the person who uploaded a file can confirm it (`FILE_NOT_FOUND` otherwise).
- **Order and cover**: a confirmed photo joins the end. The first photo becomes the cover; exactly one photo is the cover whenever there are any. `/order` needs every photo id exactly once (400 `PHOTO_ORDER_MISMATCH` otherwise — usually a stale list; reload) and doesn't move the cover. Deleting the cover passes it to the next in order.
- **`caption`**: at most 200 characters, shown with the photo on the marketplace — use it as alt text. An empty string clears it.
- **Delete** removes the file from storage; a copy a CDN or browser already cached may linger for a while.
- **Photos are public**: while the estate is listed, the photos and captions are on the marketplace. The file's own name and who uploaded it never are.
- **An approved allocation letter is a publication condition** (`PUBLICATION_LETTER_NOT_APPROVED`, changeset 095), with **no exemption** for live estates: finance can't verify a sale without one. If an approval lapses (fees, conditions, template or letterhead changed), the estate leaves the marketplace and stops taking reservations, `published` untouched, and returns without republishing once the Executive Director approves the letter again. Link `letterApproved: false` to the letter-approval screen.
- **A photo is a publication condition** (`PUBLICATION_NO_PHOTO`). Estates that were already published when this arrived are exempt and report `hasPhoto: true`. Deleting a listed estate's last photo takes it off the marketplace; it returns, without republishing, when a photo is added.
- Errors: `ESTATE_NOT_FOUND`, `PHOTO_NOT_FOUND` (404), plus the storage codes below.

### Estate titles: developers declare, HomeTrue verifies — **built**

A developer's own staff used to be able to mark their land verified. Now a title is **declared** by the developer (`pending`) and only HomeTrue moves it to `verified` or `rejected`. Changeset 087.

**Developer side**, under `/api/portal/estates/{estateId}/title`. GET routes need `portal.estates.view`, the rest `portal.estates.manage`; own company only (another's is a 404); branch staff on a company-level estate get `ESTATE_READ_ONLY_FOR_BRANCH` (403).

| Method | Path | Request | Response |
|---|---|---|---|
| GET | *(the base path)* | — | `EstateTitle`, or 404 `TITLE_NOT_RECORDED` |
| POST | `/documents/uploads` | `{ fileName, contentType, sizeBytes, kind }` | 201 upload ticket (same shape as photos) |
| POST | `/documents/{fileId}/confirm` | — | 201 `TitleDocument` (200 if already confirmed). Not part of the title until submitted. |
| GET | `/documents/{fileId}/download` | — | `{ url, expiresAt }`, a 5-minute link. Any of this estate's title documents. |
| PUT | *(the base path)* | see below | 200 `EstateTitle` with `status: "pending"` |

- **Documents**: `kind` is `title_document` (the C of O, R of O, consent or Gazette page), `survey_plan` or `deed`. PDF, JPG or PNG, at most **10,485,760 bytes**. **Private storage**: never a public URL. Kept exactly as uploaded.
- **Submit** body: `{ titleType, titleNumber, issuedDate?, titleDocumentFileId, surveyPlanFileId, deedFileId? }`. `titleNumber` at most 100; `issuedDate` `YYYY-MM-DD`, not in the future. Each file must be a confirmed title document of **this** estate, of the matching kind.
- **Create or replace** (the old `POST .../title`, which refused a second call, is gone). Every submission, including one replacing a verified title, comes back `pending` with the decision cleared — the verification was of the old documents. The estate's `title_verification` check goes back to `pending` at the same moment, so buyers stop seeing "Land checked". Resubmitting exactly what is already pending changes nothing.
- Errors: `TITLE_DOCUMENT_REQUIRED`, `SURVEY_PLAN_REQUIRED`, `TITLE_DOCUMENT_NOT_FOUND` (wrong estate, wrong kind, or not confirmed), `ISSUED_DATE_IN_FUTURE`, `INVALID_ISSUED_DATE` (all 400), `ESTATE_READ_ONLY_FOR_BRANCH` (403), plus the storage codes.
- `POST /api/portal/estates/{id}/verification-checks` **no longer exists**.

**HomeTrue side**: `admin.titles.review` (Super Admin).

| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/admin/title-reviews?status=pending\|verified\|rejected` | — (default `pending`) | `TitleReview[]`; pending oldest first, the others newest first |
| GET | `/api/admin/title-reviews/{titleId}/documents/{fileId}/download` | — | `{ url, expiresAt }`. Only the documents of the submission under review. |
| POST | `/api/admin/title-reviews/{titleId}/verify` | `{ verificationSource, note?, submittedAt }` | `TitleReview` |
| POST | `/api/admin/title-reviews/{titleId}/reject` | `{ note, submittedAt }` | `TitleReview` |
| POST | `/api/admin/estates/{id}/verification-checks` | `{ checkType, status?, verificationSource?, notes? }` | 201 check |

- **`submittedAt` is required on verify and reject** — send back the `submittedAt` of the title you reviewed. **This is an addition to the frontend's brief**: without it, a developer resubmitting while the reviewer reads would get documents nobody opened marked verified. A mismatch is 409 `TITLE_CHANGED`: reload and review again.
- `verificationSource`: `manual_review` (a reviewer compared the documents) or `registry_search` (an official search at the land registry, report in hand). `agis`, `qoreid` and `cac_registry` are reserved for integrations that don't exist and are refused (400 `VERIFICATION_SOURCE_NOT_ALLOWED`) on both routes.
- A rejection's `note` is required and **the developer sees it**.
- Verify sets the estate's `title_verification` check to `verified` with the same source and `lastVerifiedAt` = now; reject sets it to `failed`.
- Only a `pending` title can be decided (409 `TITLE_NOT_PENDING`). A title recorded before review existed has no documents and can't be verified (409 `TITLE_DOCUMENTS_MISSING`): reject it and ask for them.
- The checks route records AGIS registration and encroachment only; `title_verification` follows the review (400 `TITLE_CHECK_FOLLOWS_REVIEW`).
- Audit: `estate.title_submitted`, `estate.title_verified`, `estate.title_rejected`.

Shapes (wire values lowercase):

```ts
EstateTitle { id, estateId, titleType: "C of O" | "R of O" | "Governor's Consent" | "Gazette",
  titleNumber, issuedDate: string | null, status: "pending" | "verified" | "rejected",
  documents: TitleDocument[],            // title page, survey plan, deed — in that order
  submittedAt, submittedBy: string | null, decidedAt: string | null, decidedBy: string | null,
  verificationSource: "manual_review" | "registry_search" | null, decisionNote: string | null }
TitleDocument { fileId, kind: "title_document" | "survey_plan" | "deed", fileName, fileType, sizeBytes, uploadedAt }
TitleReview = EstateTitle & { estateName, estateState, companyName }
```

`submittedBy` and `decidedBy` are user ids. `submittedBy` is null for a title recorded before review existed. The portal estate detail's `title` is this same `EstateTitle`.

### Public marketplace — **built, no authentication**

No `Authorization` header needed (or sent). Rate-limited: 429 with `Retry-After` beyond the limit.

| Method | Path | Returns |
|---|---|---|
| GET | `/api/marketplace/estates` | `Page<Listing>` — filters `q`, `state`, `city`, `minPrice`, `maxPrice`, `currency` (default `NGN`), `minSize`, `maxSize`, `titleType` (**verified titles only** — a declared or rejected title of that type doesn't match), `intent`; `sort` = `newest` (default) \| `price_low` \| `price_per_sqm` \| `plots_remaining`; `limit`/`cursor` |
| GET | `/api/marketplace/estates/{id}` | `Listing` |
| GET | `/api/marketplace/estates/{id}/geojson` | GeoJSON `FeatureCollection`, same shape as the portal's |

- Only estates passing all five conditions appear. Anything else, including an id that exists but isn't eligible, is a plain **404**, with no hint that it exists.
- **`imageUrl` is the cover photo's URL**, and `photos: [{ url, caption }]` the gallery, cover first then in the developer's order. An estate exempt from the photo requirement may have `imageUrl: null` and `photos: []`; render a placeholder.
- `Listing` follows the frontend's shape: `priceTiers` (each with `price`, `pricePerSqm` — null for a unit-type tier — `plotsRemaining`, `availability`), `cornerPremiumPct`, `seller: { branchName, companyName }`, `verified: true`, plus `fromPrice`/`fromPriceCurrency`, `plotsRemaining`, `hasMap`, and `verificationChecks`.
- **`titleType` comes with `titleStatus`** (`pending` | `verified` | `rejected`, null when no title) and `titleVerifiedAt` (set only while verified). Never show the type without the status: "C of O (declared)", "C of O, verified by HomeTrue", "C of O (declared, not confirmed)".
- **`verificationChecks` carry their `verificationSource`** — `manual_review` versus a registry is a real difference in front of a buyer. A check type that isn't listed was **never checked**: render it as unchecked, never as verified.
- **Plot `availability` on the map is only `AVAILABLE` or `UNAVAILABLE`.** Reserved and sold are deliberately indistinguishable. Plots carry `priceTierId` and `isCorner` but no price: compute it from the tier and `cornerPremiumPct`, as `priceForPlot()` already does.
- **A price filter only matches listings priced in the requested `currency`.**

Differences from the frontend's current services, all in AGENTS.md: the path is `/api/marketplace/estates`, not `/listings`; plots come from `/geojson`, not a paginated `/listings/{id}/plots`; plot status is collapsed; there is no listing-level `paymentPlans` list of plan types — each `priceTiers[]` entry carries its own declared `paymentPlans` with figures (see "Instalment plans" below), so the frontend's `paymentPlans.includes(...)` filter should check whether any tier has plans.

### Full cost disclosure (tenant portal) — **built**
| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/portal/estates/{id}/fees` | — | `FeeSchedule` |
| PUT | `/api/portal/estates/{id}/fees` | `DeclareFeesRequest` | `FeeSchedule` |
| GET | `/api/portal/estates/{id}/refund-terms` | — | `RefundTerms` (404 if undeclared) |
| PUT | `/api/portal/estates/{id}/refund-terms` | `DeclareRefundTermsRequest` | `RefundTerms` |
| GET | `/api/portal/estates/{id}/default-terms` | — | `DefaultTerms` (404 if undeclared) |
| PUT | `/api/portal/estates/{id}/default-terms` | `DeclareDefaultTermsRequest` | `DefaultTerms` |

Reads need `portal.estates.view`, writes `portal.estates.manage`. Every write
**versions rather than overwrites**, so a buyer's future acknowledgement can
name the version they were shown.

There is **no frontend service for any of this yet** — these are new
endpoints with no counterpart in `~/landvault/src/services/`. The public half
arrives inside the existing marketplace payload (below), so a listing page
gets it without a new call.

### Instalment plans (portal + marketplace) — **phases 1–3 built** (changesets 099–103)

| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/portal/estates/{id}/payment-plans` | — | `PaymentPlans` (`portal.estates.view`) |
| PUT | `/api/portal/estates/{id}/payment-plans` | `{ plans: [{ name, depositPct?, depositAmount?, instalmentCount, frequency, premiumPct? }] }` | `PaymentPlans` (`portal.estates.manage`) |

- The developer declares **terms only**; HomeTrue works out every figure. **The premium applies to the whole price; a
  percentage deposit is a share of that total**; the instalments split the rest evenly, leftover kobo on the last.
  Example: ₦15M, 30%, 12 monthly, 10% premium → total ₦16.5M, deposit ₦4.95M, 12 × ₦962,500.
- Exactly one of `depositPct` (1–99.99) and `depositAmount` (fixed, in the tier's currency). `instalmentCount` 1–60,
  `frequency` `monthly` | `quarterly`, `premiumPct` 0–100 (none when left out). At most 10 plans; names unique.
  Errors: `VALIDATION_ERROR`, `INVALID_REQUEST`.
- A PUT replaces the set as a new `version` (like fees); `[]` means outright only. No HomeTrue review.
- `PaymentPlans`: `{ version, plans: [{ id, name, depositPct, depositAmount, instalmentCount, frequency, premiumPct,
  quotes: [{ tierId, tierLabel, currency, price, planTotal, premium, deposit, instalmentCount, instalment,
  lastInstalment }] }] }` — quoted per open tier, for a non-corner plot; a tier where a fixed deposit leaves nothing
  to pay is left out.
- **Marketplace**: each `priceTiers[]` entry now has **`paymentPlans`**:
  `[{ planId, name, frequency, deposit, instalmentCount, instalment, lastInstalment, planTotal, premium,
  ifAnInstalmentIsLate: [{ monthsLate, penaltyPct, amount }] }]` — empty for an outright-only estate. Replace the
  checkout plan picker's hard-coded 20% deposit with these figures.
- **Change to `costDisclosure.exitCosts.ifYouFallBehind`**: penalties are now a share of **one instalment** (of the
  estate's first plan at the basis tier — named in the new `penaltyBasisPlan` and `penaltyBasisInstalment`), not of
  the whole price. It is **empty when the estate has no plans** (an outright buyer has nothing to fall behind on).
- **Phase 2 built** (changeset 100): choosing a plan at checkout and the plan on the offer letter — see Checkout.
  **Phase 3a built** (changeset 101): the deposit. Pay (`POST /api/transactions/{id}/payments`) charges an
  instalment purchase's **deposit** from its schedule. The finance queue item gains `paymentFor` (`full_price` |
  `deposit`) and `planName`, with `expectedAmount` the deposit. Verifying a deposit **doesn't sell**: the purchase
  becomes **`on_plan`** (new `TransactionStatus`), the plot **`on-plan`** (new `PlotStatus`; public map: unavailable),
  the portfolio entry `installment_active` (`totalPrice` = plan total, `paidAmount` = deposit, `installmentMonths`,
  `installmentsPaid`, `nextDueDate`/`nextDueAmount`), and a **provisional allocation letter** is issued — Document
  type **`provisional_allocation_letter`**, number `HT-PAL-…`. The verify response answers **`status: on_plan`** with
  that letter's `allocationLetterId`/`allocationLetterNumber` (it said `verified` until 3c — don't show "sold").
  The buyer's email says the plot is held on the plan. Payouts to the developer: see escrow payouts (3c). Late money on a deposit can only be refunded: allocate answers 409
  `LATE_DEPOSIT_NOT_SUPPORTED`.
  **Phase 3b built** (changeset 102): once `on_plan`, `POST /api/transactions/{id}/payments` charges the **next
  instalment**; `?balance=true` charges **everything still owed** in one payment. One at a time: 409
  `INSTALMENT_AWAITING_VERIFICATION` while the last one waits for finance; a paid-off plan is
  `TRANSACTION_NOT_PAYABLE`; `balance=true` without an active plan is `NOTHING_TO_PAY_OFF`. The finance queue lists
  each awaiting instalment (`paymentFor: instalment | balance`, `instalmentSequence`, `instalmentCount`, `dueDate`)
  and the same verify/reject routes act on it (verify answers `status: on_plan`; reject refunds just that payment and
  the plan carries on). **Verifying the last instalment, or a balance**, sells the plot, makes the purchase
  `verified`, the portfolio entry `completed`, and issues the final `allocation_letter` (answer `status: verified`).
  Portfolio plans: `paidAmount` is what finance verified, `installmentsPaid`, `nextDueDate`/`nextDueAmount` the first
  unpaid instalment, and `payments[]` every payment (`type` `deposit`/`instalment`/`balance`, `status` `confirmed` or
  `pending_verification`). The buyer is emailed per verified instalment. The developer is paid out of escrow: see
  "Escrow payouts" under Payments.
  **Phase 4 built** (changeset 104): arrears, penalties and reminders. Nothing is stored — every status is worked out
  on read for today in Lagos. Each instalment is `upcoming`, `due_today`, `overdue` (1–7 days late), `in_arrears`
  (from day 8), `awaiting_verification` (paid; finance hasn't verified it — **never late**) or `paid`. The purchase
  carries `standing: { status, instalments: [{ sequence, dueDate, amount, status, daysLate, penalty }], arrears }`
  once `on_plan` (plan `status` `on_track` | `due_today` | `overdue` | `in_arrears` | `paid_off`; `arrears` while an
  instalment is late: `instalmentsLate`, `amountOverdue`, `penaltyAmount`, `totalDue`, `overdueSince`, `graceEnds`,
  `daysLate`). Penalties: the developer's declared steps **as copied onto the purchase** when it opened, highest step
  reached, a % of the late instalment; none declared = none. Paying a late instalment charges it **plus its penalty**
  (fixed when Pay is pressed; `PaymentDto.penaltyAmount`); `?balance=true` adds every late instalment's penalty.
  The finance queue shows `penaltyAmount` and `penalties[]` inside `expectedAmount`. Default terms take
  `penaltiesRefundable` (required when `penaltyTiers` is non-empty); the listing shows it as
  `exitCosts.penaltiesRefundable` and the offer letter states it. Reminder emails go 7 days and 1 day before, on the
  first day overdue and on entering arrears. Developers: `GET /api/portal/finance/plans?status=` (see Portal finance).
  **Not built yet (phase 5)**: withdrawal, revocation, restructure and penalty waivers.

### Publication now has SEVEN conditions, not five

An estate is publicly listable only when: `published` is true, the tenant is `verified`, the tenant holds `marketplacePublishing`, the tenant is **`active`** (so an offboarded company's land isn't for sale either), **no conflict blocks it**, **its fee schedule has been declared**, and **its refund terms have been declared**.

The last two are what make disclosure structural rather than a request: "please declare your fees" is a policy a developer ignores; "you cannot list until you have" is enforceable. They fail separately, with `PUBLICATION_FEES_UNDECLARED` and `PUBLICATION_REFUND_TERMS_UNDECLARED`.

**Declaring an empty fee schedule counts** — an estate with no extra charges may list, by saying so. Silence does not.

**Default terms are not a condition.** Their story requires them only "where installments are offered", and nothing on an estate records whether they are.

**Estates already published when this shipped are grandfathered** and keep their listing; their `costDisclosure` is `null` rather than a fabricated empty schedule.
- **`actualAreaSqm` is computed**, never sent: square metres derived from the footprint. Null means no boundary yet, never a fallback to the nominal size.

A plot's `footprint` must sit inside its estate's. `published` always starts false.

## Marketplace plots — `marketplacePlotsService.ts`
| Method | Path | Response |
|---|---|---|
| GET | `/api/marketplace/listings/{listingId}/plots?cursor&limit` | `Page<ListingPlot>` |
| GET | `/api/marketplace/listings/{listingId}/plots/{plotId}` | `ListingPlot` |

### What the public listing now carries — **built**

`MarketplaceListing` gains two things, both unauthenticated:

- **`priceTiers[].commitment`** — the true cost of that tier: `landPrice`,
  `oneOffFees`, `totalCommitment`, `totalCommitmentIfCorner`,
  `recurringFees`, `optionalFees`, and `totalExcludesOtherCurrencyFees`.
  Every amount is a `{ min, max, isRange }`: show the range when `isRange`,
  **never a midpoint**.
- **`costDisclosure`** — `fees[]` (the full breakdown) and `exitCosts` (what
  withdrawing returns, what falling behind costs, and the revocation terms),
  all computed in naira. `null` only for an estate grandfathered in before
  disclosure was required.

`totalCommitment` is land plus **one-off** mandatory fees. Recurring charges
(an annual facility fee) are deliberately outside it — that is how the real
allocation letters state their own totals.

The frontend's `MarketplaceListing` type needs both fields added; nothing
today reads them.

## Marketplace listings (public, derived) — `marketplaceService.ts`
| Method | Path | Response |
|---|---|---|
| GET | `/api/marketplace/listings?<ListingFilters>` | `Page<Listing>` |
| GET | `/api/marketplace/listings/{id}` | `Listing` |
| GET | `/api/marketplace/listings/{id}/similar?limit` | `Listing[]` |

`Listing extends Estate` — server-projected, never a second independently authored catalogue; compute it from `Estate` + publication gate, same as `marketplaceService.ts`'s `projectListing()` does client-side today.

## Unified marketplace feed (primary + resale) — `marketplaceFeedService.ts`
| Method | Path | Response |
|---|---|---|
| GET | `/api/marketplace/feed?<UnifiedListingFilters>&cursor&limit` | `Page<MarketplaceListing>` |

`MarketplaceListing = PrimaryMarketplaceListing | ResaleMarketplaceListing` (discriminated union — server should tag which).

## Wishlist — `marketplaceService.ts` (`WishlistItem`) — **built** (changeset 096)
A buyer's saved listings, kept on the account. **Needs a login** (`client.marketplace.view`), unlike the
rest of `/api/marketplace/`; anonymous → 401. A buyer only ever sees and changes their own list.

| Method | Path | Response |
|---|---|---|
| GET | `/api/marketplace/wishlist` | `WishlistItem[]`, newest first (at most 200, so a plain array) |
| PUT | `/api/marketplace/wishlist/{listingType}/{listingId}` | `WishlistItem` — 201 saved, 200 already saved (unchanged). No body |
| DELETE | `/api/marketplace/wishlist/{listingType}/{listingId}` | 204, whether or not it was saved |

```
WishlistItem {
  listingType: "primary"            // "resale" is refused until resale exists
  listingId: string                 // primary: the estate id
  name: string                      // current name while listed; the name when saved once it isn't
  savedAt: string
  priceAtSave: number | null        // the from-price when saved, worked out by the backend
  currency: "NGN" | "USD" | null
  available: boolean                // on the marketplace now; false never says why
  currentPrice: number | null       // from-price now; null when not available
  currentCurrency: "NGN" | "USD" | null
  plotsRemaining: number | null     // 0 = listed but sold out; null when not available
}
```

- **`priceAtSave` is only a marker** for "↓ ₦1.0M since saved" — never show it as the price. Compare it with
  `currentPrice` **only when `currency` equals `currentCurrency`**; a listing whose cheapest price moved to
  another currency has no meaningful change to show.
- **`available: false`** — the estate left the marketplace (unpublished, company suspended, a blocking
  conflict, …). The item stays so the buyer sees "No longer available"; nothing says why.
- Errors: `WISHLIST_TYPE_NOT_SUPPORTED` (400, `resale`, on PUT and DELETE), `WISHLIST_TYPE_UNKNOWN` (400),
  `INVALID_PARAMETER` (400, an id that isn't a UUID), `LISTING_NOT_FOUND` (404, PUT on a listing not on the
  marketplace now), `WISHLIST_FULL` (409, 200 already saved).
- Removing hard-deletes: saving the same listing again starts fresh (`savedAt`, `priceAtSave`).
- Frontend: `WishlistItem` gains `name`, `currency`, `available`, `currentPrice`, `currentCurrency`,
  `plotsRemaining`; `priceAtSave` becomes nullable. No need to re-fetch each listing for the page.

## Checkout / transactions — `marketplaceCheckoutService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/api/checkout/transactions` | `{ reservationId, intent, plan, planId? }` | `Transaction` |
| GET | `/api/checkout/transactions/mine?cursor&limit` | — | `Page<Transaction>` — the buyer's own, newest first (limit default 20, max 100) |
| GET | `/api/checkout/transactions/{id}` | — | `Transaction` (another buyer's is 404) |

**Instalment purchases name a declared plan**: `{ reservationId, intent, plan: "installment", planId }`, the
`planId` from the listing's `priceTiers[].paymentPlans[].planId`. **Don't send `installmentMonths`** (400
`INVALID_PAYMENT_PLAN`; it now comes from the plan); `planId` on outright/milestone is 400 too; a plan the estate
doesn't offer now is 409 `PLAN_NOT_OFFERED`. The response's **`schedule`** is `{ planId, planName, frequency,
planTotal, premium, deposit, instalments: [{ sequence, dueDate, amount }] }`, worked out from the agreed price and
**fixed** — due dates count from today (Lagos), and the developer redeclaring plans later changes nothing. The offer
letter states the whole plan, every due date, the total commitment on the plan (one-off fees included) and the
late-payment rule. Paying it charges the deposit first (see "Instalment plans" above).

**Opening a purchase issues the offer letter** (see Documents): the response's **`offer`** is `{ id, number, issuedAt, acceptedAt }`. Show it and let the buyer accept it (`POST /api/documents/offer-letters/{offer.id}/accept`) before the Pay button; paying is refused with 409 `OFFER_NOT_ACCEPTED` until then. If the estate's letter isn't approved and current, nothing is opened: 409 `LETTER_NOT_APPROVED` (the hold stays). `offer` is null on purchases opened before offer letters.

**Opening a purchase needs an address**: 409 `ADDRESS_REQUIRED` until the buyer has saved one
(`PUT /api/me/address`) — route them there and back, like `KYC_REQUIRED`. The address is copied
onto the purchase as **`buyerAddress`** (`{ line1, line2, city, state, postalCode, country }`) and
never changes afterwards; it's what the allocation letter prints. Null on purchases opened before
addresses were collected.

Paying, confirming and finance verification are **not** checkout routes — see "Payments" below
(`POST /api/transactions/{id}/payments`, `POST /api/payments/{reference}/verify`, and the developer
portal's `/api/portal/finance/...`). The old `/payment`, `/confirm` and `/finance-verify` routes in
this table never existed and are gone.

`TransactionStatus`: `pending_payment → awaiting_finance → verified`, or `rejected`, or `abandoned`.
On an instalment plan: `pending_payment → awaiting_finance → on_plan` (deposit verified) `→ verified` (last
instalment or balance verified),
(the hold ended with nothing paid). The backend never produces `payment_received`: a confirmed
payment moves straight to `awaiting_finance`. **Only `verified` means the plot is the buyer's** — `on_plan` never does.

Every `Transaction` carries **`payment`** — null until the buyer first presses pay:
`{ reference, status, amountPaid, paidAt, underReview, reviewKind, refundStatus, receiptId, receiptNumber }`. It is the
successful attempt if there is one, otherwise the most recent. `reviewKind`: `late_payment`
(paid after the reservation ended — the developer will allocate the plot or refund it),
`refund_due` (the money is being returned), or null. `refundStatus`: the refund's status once one
has started (`sending | pending | processing | needs-attention | processed | failed`), else null.
Use `payment.reference` for `/api/payments/{reference}/refund` and friends. `receiptId`/`receiptNumber`: HomeTrue's
receipt, issued the moment Paystack confirms the payment (null before) — see Documents.

**`InitiateTransactionInput` is deliberately not the accepted body.** The
frontend currently posts `basePrice`, `totalPrice`, `amountDue`,
`cornerPremiumPct`, `sizeSqm` and `titleType` from the browser; none is read.
A client-supplied price is the same hole as a client-supplied `tenantId`. The
price is computed server-side and **captured when the plot was held**, so a
tier re-priced mid-checkout cannot change what the buyer agreed to. The
frontend must stop sending them.

## Reservations — `reservationService.ts` — **built**
| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/api/reservations` | `{ plotId }` | `Reservation` (45-minute hold) |
| GET | `/api/reservations/mine` | — | `Reservation[]` (active only) |
| DELETE | `/api/reservations/{id}` | — | 204 |
| POST | `/api/reservations/{id}/convert` | — | **not built** — conversion follows finance verification |

Gated on `client.checkout.reserve` **and** an approved KYC record. Divergences
from the frontend's current call, both needing a frontend change:

- **`listingId` is not accepted** — the estate is derived from the plot. A
  client-supplied estate could only ever disagree with the stored one.
- The response says **`estateId`** where the frontend's `Reservation` says
  `listingId`, and **`released`** where it says a hold was cancelled;
  `secondsRemaining` is added for the countdown so it never depends on the
  client's clock.

The hold is acquired by a database-level compare-and-swap inside a
`SECURITY DEFINER` function (changeset 054): two buyers can never both hold
one plot, and a buyer cannot read or write `plots` directly at all. Expiry is
a scheduled sweep, not a client timer. **Holds cannot be extended.**

## Portfolio (owned plots + payments) — `portfolioService.ts` — **outright and instalment purchases built** (changesets 088, 101–102)

All need `client.portfolio.view` (every buyer has it). Read-only: **ownership only ever comes from finance verifying a sale**.

| Method | Path | Response |
|---|---|---|
| GET | `/api/portfolio/plots?sort=urgency&cursor&limit` | `Page<OwnedPlot>`: newest first, or most urgent first with `sort=urgency` (applied before paging). `limit` default 20, max 100. Any other `sort` is 400 `INVALID_PARAMETER`. |
| GET | `/api/portfolio/plots/{id}` | `OwnedPlot`. Takes an owned-plot id **or a transaction id**; another buyer's is 404 `OWNED_PLOT_NOT_FOUND`. |
| GET | `/api/portfolio/summary` | `{ byCurrency: [{ currency, totalValue, totalPaid, count }], awaitingVerification }` |

- **What is listed**: every plot the buyer owns, across every company, plus paid purchases finance hasn't verified yet (`pending_verification`, with `id` = the transaction id). An unpaid hold is **not** listed (it's a checkout, not ownership), nor is a rejected or abandoned purchase.
- **When finance verifies**, the purchase becomes an owned plot with its own `id`; `transactionId` stays the same, and `GET /plots/{transactionId}` keeps working, so the frontend can follow it.
- **Statuses today**: `pending_verification`, `installment_active` (deposit verified, plan running), `in_arrears` (a plan instalment more than 7 days late — worked out on read) and `completed` (verified and paid in full — outright, or the plan's last instalment or balance). `allocated` waits for issued documents; the upgrade and resale statuses wait for those flows. Nothing fakes them.
- **No money arithmetic in the frontend**: `paidAmount` is what finance verified (**zero while pending** — equity doesn't advance before verification), and `/summary` gives totals per currency, never added across currencies, counting only plots still owned (`transferred`/`superseded` are listed as history but never counted), with pending purchases counted separately. Use it instead of `groupPlotsByCurrency`, which only sees the page loaded.
- **Title**: `titleType`, plus **`titleStatus`** (`pending` | `verified` | `rejected`, live from HomeTrue's review) and `titleVerified` (true only while verified). The frontend's mock always set `titleVerified: true`; that is no longer true.
- `basePrice` is always the agreed pre-premium price; `cornerPremiumPct` only when a premium was applied. `sqm` is what it was sold as; `actualSqm` the survey, null when unsurveyed — never reconcile them.
- `payments`: the purchase's payment, `pending_verification` then `confirmed`. **`receiptId` is HomeTrue's receipt id** (`/api/documents/receipts/{id}`), null until Paystack confirms the payment. *(Changed: it used to be the payment reference.)*
- **Plans**: `plan` is `installment`, `totalPrice` the plan total, `paidAmount` what finance verified, `installmentMonths`, `installmentsPaid`, and `nextDueDate`/`nextDueAmount` the first unpaid instalment (null once paid off). `payments[]` lists every payment with `type` `deposit` | `instalment` | `balance`. `arrears` (phase 4) is filled from the first day an instalment is late — `status` (`overdue`/`in_arrears`), `amountOwed`, `overdueSinceDate`, `gracePeriodDays`, `graceEndsDate`, `penaltyAmount`, `penaltyReason`, `instalmentsLate`, `totalDue`, `daysLate` — and the entry's `status` reads `in_arrears` past the 7-day grace. `paidAmount` excludes penalties; each payment carries `penaltyAmount`. `restructureStatus` and `supersedes` are still null (phase 5, upgrades).

**Not built, and the frontend's live calls to them will 404/405**: `POST /api/portfolio/plots` and `PUT /api/portfolio/plots/{id}` (**never will be** — a client must not be able to create or change ownership), and `restructure-requests` (phase 5). The schedule is on the transaction (`schedule`); instalments are paid with `POST /api/transactions/{id}/payments` and verified in the finance queue — there are no portfolio routes for either.

## Documents — `documentsService.ts` — **receipts (089), allocation letters (095) and offer letters (098) built**

All need `client.documents.view` (every buyer has it) except verify, which is public, and accepting an offer, which needs `client.checkout.reserve`. Documents are issued by events, never by a client; accepting an offer is the one thing a buyer does to one.

| Method | Path | Response |
|---|---|---|
| GET | `/api/documents?cursor&limit` | `Page<Document>`, newest first. `limit` default 20, max 100. |
| GET | `/api/portfolio/plots/{id}/documents` | `Document[]` for one portfolio entry — the same ids `/api/portfolio/plots/{id}` takes (owned-plot or transaction id). Another buyer's: 404 `OWNED_PLOT_NOT_FOUND`. |
| GET | `/api/documents/receipts/{id}` | `Document`; another buyer's: 404 `DOCUMENT_NOT_FOUND`. |
| GET | `/api/documents/receipts/{id}/pdf` | The PDF (A4), as an attachment named `HomeTrue-<number>.pdf`. |
| GET | `/api/documents/receipts/{id}/jpeg` | The same page as a JPEG (150 dpi), for sharing. |
| GET | `/api/documents/allocation-letters/{id}` | `Document`; another buyer's: 404 `DOCUMENT_NOT_FOUND`. |
| GET | `/api/documents/allocation-letters/{id}/pdf` | The letter (A4, as many pages as it needs), as an attachment named `HomeTrue-<number>.pdf`. |
| GET | `/api/documents/allocation-letters/{id}/jpeg` | Every page stacked into one JPEG, for sharing. |
| GET | `/api/documents/offer-letters/{id}` | `Document` (`status`: `issued`, `accepted` or `lapsed`; `acceptedAt`); another buyer's: 404. |
| POST | `/api/documents/offer-letters/{id}/accept` | No body. `Document`, now `accepted`. Again: the same answer. Another buyer's: 404. Purchase no longer waiting for payment: 409 `OFFER_NOT_OPEN`. |
| GET | `/api/documents/offer-letters/{id}/pdf` | The offer (A4), as an attachment named `HomeTrue-<number>.pdf`; once accepted it prints when. |
| GET | `/api/documents/offer-letters/{id}/jpeg` | Every page stacked into one JPEG. |
| GET | `/api/documents/verify/{code}` | **Public**, rate limited (30/min per connection). `DocumentVerification`, or 404 `NOT_A_HOMETRUE_DOCUMENT` (unknown or malformed code — one answer for both). |

- **A receipt is issued the moment Paystack confirms a payment** (return page, webhook or sweep), in the same database step, by **HomeTrue** — it received the money — naming the developer as seller. Never for a failed, abandoned or mismatched payment.
- **An allocation letter is issued the moment the developer's finance verifies the sale** (or allocates late money), in the same database step as the plot becoming sold — never before. It's built from the estate's approved setup: letterhead, signature and seal, the template's wording, the HomeTrue-approved conditions, then HomeTrue's disclosure clauses, with the buyer's name and phone, the plot and its size filled in. Number `HT-ALC-2026-000123`. Sales verified before this shipped have no letter.
- **An offer letter is issued the moment the buyer opens a purchase** ("Proceed to buy" — suggested label "Get my offer letter"), in the same database step, from the estate's approved letter setup: the developer's letterhead and signatory, **HomeTrue's fixed wording**, the price and total commitment, then the same conditions and disclosure clauses the allocation letter would print. Number `HT-OFR-2026-000123`. The buyer must **accept it before paying**: `POST /api/transactions/{id}/payments` answers 409 `OFFER_NOT_ACCEPTED` until then. Accepting records who (the session), when and the browser string. It gives **no right to the plot** and doesn't extend the hold; when the purchase is rejected or abandoned the offer shows `lapsed`. **The offer fixes the terms**: the allocation letter issued later copies its conditions from the accepted offer, not the estate's current ones. Purchases opened before this have no offer and stay payable.
- `Document`: the frontend's shape — `id, type ("receipt" | "allocation_letter" | "offer_letter"), title, date, status, qrCode, size, plotId, transactionId, supersedes` — plus `number` (`HT-RCT-2026-000123`), `issuedAt`, `verifyUrl`, `refundedAt`, `pdfUrl`, `jpegUrl`. `qrCode` is the printed verification code (`3F9A-1C77-B02E-55D1`). `size` is null (drawn on demand). `plotId` is the **inventory** plot, not a portfolio id. `status` is `valid` for receipts and allocation letters; a refunded payment's receipt stays `valid` (the money did arrive) with `refundedAt` set. Offer letters: `issued`, `accepted` or `lapsed`, with `acceptedAt`.
- **Downloads need the bearer token**, so fetch them as a blob (`apiClient` with `responseType: blob`) and hand the `File` to `navigator.share({ files })`; fall back to a download link where sharing files isn't supported. The backend never sends a document anywhere itself.
- **The QR code opens `{frontend}/verify/{code}`** (`DOCUMENTS_VERIFY_URL`, default `http://localhost:8443/verify`). That frontend page calls the verify route. `DocumentVerification`: `{ status ("valid" | "refunded", or for an offer "issued" | "accepted" | "lapsed"), documentType, number, issuedAt, issuedBy ("HomeTrue"), developer, estate, location, plot, holderInitials, refundedAt }` — **never an amount, and never the holder's name, email or phone** (initials only): anyone holding a copy can open it.
- The frontend's `POST /api/documents` (batch issue), `POST /api/documents/{id}/void` and `/api/documents/by-plot/{plotId}` **don't exist and won't**: documents are issued by the backend at the moment the event happens, and a reissue (letters, later) is a new document pointing at the old one.
- `/api/documents` lists receipts, offers and letters together, newest first; `/api/portfolio/plots/{id}/documents` returns the allocation letter first, then the receipts, then the offer. The verify page's `documentType` is `receipt`, `allocation_letter` or `offer_letter`; a letter's `developer` is the company it's from.
- **Next**: the irrevocable power of attorney.

### Portal: letterheads (changeset 090) — **built**

`{target}` is `company` or a branch id. Reads need `portal.documents.view` (Executive Director, branch manager, finance, legal); changes need `portal.documents.manage` (Executive Director) **and** a company-wide view — a branch-narrowed caller gets 403 `DOCUMENTS_REQUIRE_COMPANY_WIDE_SCOPE`. **Both slugs are new: add them to `authService.ts`.**

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/portal/letterheads` | — | `{ company: Letterhead, branches: Letterhead[] }` (a branch manager: their own branch only) |
| PUT | `/api/portal/letterheads/{target}/signatory` | `{ name, title }` | `Letterhead` |
| DELETE | `/api/portal/letterheads/{target}/signatory` | — | `Letterhead` (name, title and signature removed together) |
| POST | `/api/portal/letterheads/{target}/images/uploads` | `{ kind: "logo" \| "signature" \| "seal", fileName, contentType, sizeBytes }` | 201 upload ticket (PNG or JPEG, ≤ 2 MB, private) |
| POST | `/api/portal/letterheads/{target}/images/{fileId}/confirm` | — | `Letterhead` |
| DELETE | `/api/portal/letterheads/{target}/images/{kind}` | — | `Letterhead` |
| GET | `/api/portal/letterheads/files/{fileId}/link` | — | `{ url, expiresAt }` (5 minutes; current images of your company only) |

- `Letterhead`: `{ scope ("company" | "branch"), branchId, branchName, version, updatedAt, signatoryName, signatoryTitle, logo, signature, seal, printed, missing }`. The top-level fields are what this letterhead **sets itself**; `printed` is what a letter would show: `{ companyName, rcNumber, address, phone, email, website, signatoryName, signatoryTitle, signatorySource, logo, signature, seal }`. An image is `{ kind, fileId, fileName, fileType, sizeBytes, source ("company" | "branch") }` — show it through the link route.
- **A branch inherits** the company's logo, seal and signatory until it sets its own, and prints its own office address/phone/email (entered on the branch), each falling back to head office.
- **A branch's signer is one unit**: once a branch names its own signer it needs their signature too — the company's signature is never shown under another name.
- `missing` lists what still blocks letters: `signatory_name`, `signatory_title`, `signature`. Logo and seal are optional.
- `version` goes up on every real change (0 = nothing set). Letter-template approval (next step) is tied to it.
- Errors: `BRANCH_NOT_FOUND`, `LETTERHEAD_FILE_NOT_FOUND` (404), `UNKNOWN_IMAGE_KIND`, `INVALID_PARAMETER`, `VALIDATION_ERROR` (400), `LETTERHEAD_CHANGED` (409, retry), plus the storage upload errors below.

### Portal: allocation terms per estate (changeset 091) — **built**

Same permissions as letterheads (view to read; manage + company-wide to save).

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/portal/estates/{estateId}/letter-terms` | — | `EstateLetterTerms` |
| PUT | `/api/portal/estates/{estateId}/letter-terms` | `{ clauses: string[] }` (1–30, each ≤ 600 chars) | `EstateLetterTerms` |
| GET | `/api/portal/estates/{estateId}/letter-terms/versions` | — | `[{ version, clauses, savedAt, reviewStatus, reviewNote, reviewedAt }]`, newest first |

- `EstateLetterTerms`: `{ estateId, estateName, version (0 = never saved), clauses, updatedAt, generatedClauses, disclosureVersions: { fees, refundTerms, defaultTerms }, missingDisclosure, reviewStatus, reviewNote, reviewedAt, approvedVersion }`.
- **HomeTrue reviews every saved version** (changeset 092). `reviewStatus` of the current version: `pending` | `approved` | `rejected` (null before the first save); in the versions list an older undecided one is `superseded`. `reviewNote` is HomeTrue's note — always present on a rejection, saying what to change; show it. `approvedVersion` is the version letters use: a new version awaiting review doesn't replace it until approved. Null = nothing approved yet, so the estate can't issue letters — say so.
- **Show both lists, numbered together**: the developer's `clauses` first, then `generatedClauses` (read-only — written by HomeTrue from the estate's cost disclosure; the last one always says payments go only through HomeTrue). To change a generated clause, change the disclosure.
- **No amounts and no bank details in a clause**: 400 `CLAUSE_REFUSED` with every problem in `fieldErrors` keyed `clauses[0]`, `clauses[1]`…, nothing saved. Show each message under its clause.
- `missingDisclosure`: `fees`, `refund_terms`, `default_terms` not declared yet — link to the disclosure screens.
- Each save is a new version (earlier ones kept); saving identical clauses changes nothing. Errors: `ESTATE_NOT_FOUND` (404, also another branch's estate for branch staff), `LETTER_TERMS_CHANGED` (409, retry).

### Portal: previewing and approving the allocation letter (DS-5, changeset 093) — **built**

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/portal/estates/{estateId}/letter-approval` | — | `LetterApproval` (`portal.documents.view`) |
| GET | `/api/portal/estates/{estateId}/letter-preview/pdf?terms=approved\|latest` | — | PDF (`portal.documents.view`) |
| GET | `/api/portal/estates/{estateId}/letter-preview/jpeg?terms=approved\|latest` | — | JPEG, every page stacked |
| POST | `/api/portal/estates/{estateId}/letter-approval` | `{ versions: LetterVersions }` | `LetterApproval` (`portal.documents.manage`, company-wide) |
| POST | `/api/portal/estates/{estateId}/letter-approval/withdraw` | — | `LetterApproval` (same) |

- `LetterApproval`: `{ estateId, estateName, approved, approvedAt, approvedVersions, currentVersions, changedSinceApproval, blockers, newerTermsAwaitingReview }`. `LetterVersions`: `{ terms, companyLetterhead, branchLetterhead, fees, refundTerms, defaultTerms }` (integers or null).
- **Flow**: read the status → show the preview (fetch as a blob; it's a watermarked sample with placeholders) → approve by sending back the `currentVersions` you previewed against. If anything changed in between: 409 `LETTER_SETUP_CHANGED` — re-read and preview again.
- `blockers` (approve refused with 409 `LETTER_NOT_READY`): `no_approved_terms` (HomeTrue hasn't approved the conditions yet), `signatory_name`, `signatory_title`, `signature` — link each to its screen. The preview still works while blocked.
- `approved` goes back to false by itself when the conditions (once HomeTrue approves a new version), the letterhead (signatory, signature, seal, logo) or the cost disclosure change; `changedSinceApproval` says which: `terms`, `company_letterhead`, `branch_letterhead`, `fees`, `refund_terms`, `default_terms`. Show "needs approving again" with the reason.
- `newerTermsAwaitingReview`: a newer version of the conditions is with HomeTrue; letters use the approved one meanwhile. `?terms=latest` previews it.

### Portal: letter templates (changeset 094) — **built**

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/portal/letter-templates` | — | `{ company: LetterTemplate, branches: LetterTemplate[], fields: string[], defaultWording: { opening, signOff } }` (`portal.documents.view`) |
| PUT | `/api/portal/letter-templates/{target}` | `{ opening, closing?, signOff, standardClauses?: string[] }` | `LetterTemplate` (`portal.documents.manage`, company-wide; `target` = `company` or a branch id) |
| GET | `/api/portal/letter-templates/{target}/versions` | — | `LetterTemplateVersion[]`, newest first |

- `LetterTemplate`: `{ source: "company"\|"branch", branchId, branchName, current: LetterTemplateVersion\|null, approved: LetterTemplateVersion\|null, inUse: "branch"\|"company"\|"default" }`. `LetterTemplateVersion`: `{ version, opening, closing, signOff, standardClauses, savedAt, reviewStatus, reviewNote, reviewedAt }`.
- **Fill-in fields** (offer them as insertable chips): `{buyerName}`, `{plot}`, `{estate}`, `{location}`, `{applicationDate}`, `{allocationDate}`. The opening must contain `{plot}` and `{estate}`.
- 400 `TEMPLATE_REFUSED`: `fieldErrors` keyed `opening`, `closing`, `signOff`, `standardClauses[0]`… — show each under its field. 409 `LETTER_TEMPLATE_CHANGED`: retry.
- Every save is reviewed by HomeTrue; letters use `approved` (or the company's / HomeTrue's default — `inUse`). Approving a new version lapses estate letter approvals that used the old one (`changedSinceApproval` includes `template`). `LetterVersions` gains `templateSource` and `template`.

### Admin: reviewing letter templates (changeset 094) — **built**

Same shape and rules as the conditions review below, at `/api/admin/letter-template-reviews` (`?status=`, `/{templateId}`, `/{templateId}/approve` `{ note? }`, `/{templateId}/reject` `{ note }`). `LetterTemplateReview`: `{ templateId, tenantId, companyName, source, branchId, branchName, version, opening, closing, signOff, standardClauses, savedAt, status, note, reviewedAt, approved: LetterTemplateVersion\|null }`. Errors: `LETTER_TEMPLATE_NOT_FOUND` (404), `LETTER_TEMPLATE_ALREADY_DECIDED`, `LETTER_TEMPLATE_SUPERSEDED` (409).

### Admin: reviewing allocation terms (changeset 092) — **built**

Needs `admin.documents.review` (Super Admin, compliance officer) — **new slug for `authService.ts`**.

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/admin/letter-terms-reviews?status=pending\|approved\|rejected` | — | `LetterTermsReview[]` (pending, the default: oldest first) |
| GET | `/api/admin/letter-terms-reviews/{termsId}` | — | `LetterTermsReview` |
| POST | `/api/admin/letter-terms-reviews/{termsId}/approve` | `{ note? }` (body optional) | `LetterTermsReview` |
| POST | `/api/admin/letter-terms-reviews/{termsId}/reject` | `{ note }` (required, shown to the developer) | `LetterTermsReview` |

- `LetterTermsReview`: `{ termsId, estateId, estateName, estateState, companyName, version, clauses, savedAt, generatedClauses, status, note, reviewedAt, approvedVersion, approvedClauses }`. Show `clauses` (under review) beside `approvedClauses` (what letters use today); `generatedClauses` are context only.
- Errors: `LETTER_TERMS_NOT_FOUND` (404), `LETTER_TERMS_ALREADY_DECIDED` (409), `LETTER_TERMS_SUPERSEDED` (409 — the developer saved a newer version; reload the queue), `VALIDATION_ERROR` (400, reject without a note), `INVALID_PARAMETER` (400, unknown status).

File storage: **the storage module is built (step 1)**; estate photos (step 2) are the first feature using it — see "Estate photos" above. Files upload straight from the browser to object storage: the backend returns `{ fileId, uploadUrl, method: "PUT", headers, expiresAt }`; the browser sends the file to `uploadUrl` with exactly `headers` (the size and type are part of the signature); then the feature's own confirm route is called with `fileId`. Errors: `FILE_TYPE_NOT_ALLOWED`, `FILE_TOO_LARGE`, `FILE_EMPTY` (400), `UPLOAD_SIZE_MISMATCH` / `UPLOAD_CONTENT_MISMATCH` (400: what arrived wasn't what was promised; deleted, start again), `UPLOAD_NOT_RECEIVED` / `UPLOAD_EXPIRED` / `FILE_NOT_READY` (409), `FILE_NOT_FOUND` (404), `STORAGE_UNAVAILABLE` (503, retry). Public files come back with a `publicUrl`; private ones only through short-lived links. Accepted: JPG, PNG, WebP, PDF (never SVG). A stored file's `visibility` is `public` | `private` and its `fileType` `jpeg` | `png` | `webp` | `pdf` — lowercase like every other value in this API (`status` already was); requests accept either case. `IMAGE_UNREADABLE` (400) is added for features that ask for metadata removal (estate photos). `Document` downloads come with step 3/4.

## KYC — `kycService.ts` — **built**
| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/kyc` | — | `KycRecord` |
| POST | `/api/kyc` | `SubmitKycRequest` | `KycRecord` |
| GET | `/api/admin/kyc/{userId}` | — | `KycRecord` (needs `admin.kyc.review`) |
| POST | `/api/admin/kyc/{userId}/decision` | `{ decision, reason?, failedDocumentTypes? }` | `KycRecord` |

`KycBuyerType`: `"local" | "diaspora"` (derived from the country captured at
registration); doc types `nin \| passport \| proof_of_address`. A local buyer
submits an NIN **only**.

- The read route is `/api/kyc`, not `/api/kyc/status`.
- **The submitted NIN is never returned**, by any route, masked or otherwise
  — the frontend's optional `KycRecord.ninNumber` is simply absent.
- Files are **metadata only** (`{ fileName, fileSize?, storageKey? }`);
  object storage is not built, so no bytes are transferred.
- Verification is **manual**: a reviewer approves or rejects, and a rejection
  must name which documents failed. No registry integration exists.
- A buyer with no record reads as `unsubmitted` with every required document
  `missing` — nothing is written just by looking.

## Inspections — `inspectionService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/api/inspections` | `CreateInspectionInput` | `Inspection` |
| GET | `/api/inspections?cursor&limit` | — | `Page<Inspection>` |
| DELETE | `/api/inspections/{id}` | — | 204 |
| PATCH | `/api/inspections/{id}` | `{ date, timeSlot }` | `Inspection` |

## Enquiries — `enquiryService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/api/enquiries` | `CreateEnquiryInput` | `Enquiry` |
| GET | `/api/enquiries?cursor&limit` | — | `Page<Enquiry>` |

## Disputes — `disputesService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/disputes?cursor&limit` | — | `Page<DisputeTicket>` |
| POST | `/api/disputes` | `CreateDisputeInput` | `DisputeTicket` |

## Resale — `resaleService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/resale/listings?cursor&limit` | — | `Page<ResaleListing>` |
| GET | `/api/resale/listings/{id}` | — | `ResaleListing` |
| GET | `/api/resale/listings/mine` | — (derive from auth principal) | `ResaleListing[]` |
| POST | `/api/resale/listings` | `CreateListingInput` | `ResaleListing` |
| GET | `/api/resale/listings/{id}/offers` | — | `ResaleOffer[]` |
| GET | `/api/resale/offers/received` | — (derive from auth principal) | `{ listing, offers }[]` |
| POST | `/api/resale/offers` | `SubmitOfferInput` | `ResaleOffer` |
| POST | `/api/resale/offers/{id}/decline` | `{ reason? }` | 204 |
| POST | `/api/resale/offers/{id}/accept` | — | `ResaleTransfer` |
| GET | `/api/resale/transfers/{id}` | — | `ResaleTransfer` |
| GET | `/api/resale/transfers/mine` | — (derive from auth principal) | `ResaleTransfer[]` |

> Note: the frontend's mock-mode functions take a `sellerName: string` param for "my listings/offers/transfers" — that's a mock-data-lookup shortcut, not a real contract. The real endpoints should derive "mine" from the authenticated principal, not accept a name string.

`ResaleTransferStage`: `developer_approval → buyer_kyc → buyer_payment → finance_verification → title_transfer → settlement` — mirror the upgrade pipeline's shape; both are the same kind of multi-party async workflow.

## Upgrade / swap — `upgradeService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/api/upgrades` | `RequestUpgradeInput` | `UpgradeRequest` |
| GET | `/api/upgrades/{id}` | — | `UpgradeRequest` |
| GET | `/api/upgrades/mine` | — (derive from auth principal) | `UpgradeRequest[]` |
| GET | `/api/upgrades/by-plot/{ownedPlotId}` | — | `UpgradeRequest` |

`UpgradeStage`: `developer_approval → delta_payment → finance_verification → reallocation → completed`. `UpgradeQuote`'s delta is a **signed** amount — a downgrade must produce a real negative/refundable delta server-side, never clamp to zero. Completing a reallocation must be atomic: void/reissue documents and mark the old `OwnedPlot` `superseded` (not deleted) together with allocating the new one, in one transaction.

## Syndicates — `syndicatesService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/syndicates` | — | `Syndicate[]` |
| GET | `/api/syndicates/{id}` | — | `Syndicate` |
| POST | `/api/syndicates` | `CreateSyndicateInput` | `Syndicate` |

## Notifications — `notificationsService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/notifications?cursor&limit` | — | `Page<Notification>` |
| POST | `/api/notifications/{id}/read` | — | 204 |

## Super Admin: tenants — `tenantsService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/admin/tenants?<TenantFilters>&cursor&limit` | — | `Page<Tenant>` |
| GET | `/api/admin/tenants/{id}` | — | `Tenant` |
| POST | `/api/admin/tenants` | `CreateTenantDraftInput` | `Tenant` (Stage 1: identity/contact/presence) |
| POST | `/api/admin/tenants/{id}/submit-documents` | — | `Tenant` (see divergence note below — NOT the frontend's Stage 2) |
| POST | `/api/admin/tenants/{id}/begin-review` | — | `Tenant` |
| POST | `/api/admin/tenants/{id}/verification-decision` | `{ decision, reason?, failedDocumentIds? }` (no `reviewerName` — see note) | `Tenant` (appends `verificationHistory`, never overwrites it) |
| POST | `/api/admin/tenants/{id}/documents/{documentId}/resubmit` | `{ fileName, size }` | `Tenant` |
| PUT | `/api/admin/tenants/{id}/plan` | `{ plan, marketplacePublishing, mlmModule, fxRails }` | `Tenant` (flat, not nested — see divergence note below) |
| POST | `/api/admin/tenants/{id}/status` | `{ status, reason? }` | `Tenant` (`reason` required for `suspended`/`offboarded`; no `actor` — see note below) |
| POST | `/api/admin/tenants/{id}/support-access` | `{ reason, durationMinutes? }` (default 30) | `SupportAccessGrant` (no `actor` — grantee is the authenticated caller) |
| GET | `/api/admin/tenants/{id}/support-access` | — | `SupportAccessGrant[]` (most recent first) |
| GET | `/api/admin/audit-log` | filters below, plus `cursor`/`limit` | `Page<AuditLogEntry>` — **built**, requires `admin.audit.view` |

`/api/admin/audit-log` is implemented. Filters (all optional, combinable): `actorUserId`, `tenantId`, `targetType`, `targetId`, `action`, `privileged`, `occurredAfter`, `occurredBefore`. Ordering is always most-recent-first; page size defaults to 25 and is capped at 100. Each entry carries a resolved `actorName` (`"Unknown user"` when the account is gone — entries outlive accounts by design); `targetId` is **not** resolved to a name, so link on `targetType` + `targetId`.

`privileged=true` isolates support-access entries — that filter is the point of the screen for a compliance reviewer, not a nicety. The endpoint is **read-only**: there is no write, update, delete or archive route, and the append-only schema is what guarantees that.

`Tenant.verificationState`: `created → documents_submitted → under_review → verified` (or `rejected`, `suspended`). `Tenant` has `branches: TenantBranch[]` — real multi-branch support belongs here, not bolted on later.

**Two real divergences from `tenantsService.ts`, introduced deliberately by the "Tenancy slice B1" task and not yet reconciled:**

1. **Path**: the row above was previously `POST /api/admin/tenants/draft` — that path doesn't exist anywhere in the real frontend code (confirmed by direct inspection of `tenantsService.ts`'s `createTenantDraft`, which calls `POST /api/admin/tenants`). This table was simply wrong; now fixed.
2. **`submit-documents` is a genuinely different, simpler primitive than the frontend's real `submit-verification`.** The frontend's Stage 2 submits the *entire* verification payload in one call — `documents`, `regulatory`, `directors`, `directorsAttestation`, `financial` — and moves `verificationState` to `documents_submitted` as a side effect of that payload arriving. This backend's `submit-documents` takes **no body at all**: it only advances `CREATED → DOCUMENTS_SUBMITTED`, gated on at least one `OrganizationDocument` already existing (uploaded through some other, not-yet-built, mechanism — no document-upload endpoint exists in this slice either). **The real frontend's Stage 2 submission cannot work against this backend as built.** This was a deliberate scope-narrowing by the slice B1 task (build the verification *state-machine skeleton* with a document-existence guard; regulatory/director/financial *data capture* is a future slice), not a bug silently "fixed" toward the frontend — but it means `submit-verification` (and the document-upload endpoint it implies) is still a real gap, not yet scheduled.

Also: `RecordDecisionInput.reviewerName` (client-supplied) is intentionally **not** part of the real request body — the reviewer is the authenticated caller (`AccessTokenClaims.userId()`/`TenantContext.userId()`), the same "never client-supplied" principle as `tenant_id`. `reviewerName` is a mock-mode-only convenience with no real session to derive it from.

**Tenancy slice B2, two more real divergences, deliberate per that slice's own task spec:**

1. **`POST .../status` requires `reason`** for `suspended`/`offboarded`. The real frontend's `setTenantStatus` sends only `{ status }` — no reason. The endpoint still requires one; wiring a reason prompt into that frontend action would be needed to actually drive this endpoint as built.
2. **`PUT .../plan`'s body is flat**, not the real frontend's nested `{ plan, entitlements: {...} } }` shape. Also note: **no `actor` field** on either `status` or `support-access` — same "never client-supplied identity" principle as `reviewerName` above; the acting Super Admin and the support-access grantee are both always the authenticated caller.

## Super Admin: marketplace listing conflicts (SA-3.4) — `listingConflictsService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/admin/listing-conflicts?<ConflictFilters>&cursor&limit` | — | `Page<ListingConflict>` |
| GET | `/api/admin/listing-conflicts/{id}` | — | `ListingConflict` |
| POST | `/api/admin/listing-conflicts/{id}/review` | `{ decision: ConflictStatus, actor, note? }` | `ListingConflict` |

This is the one endpoint group that is **not** a straightforward CRUD wrapper: it must run real PostGIS geometric intersection (`ST_Intersects`/`ST_Overlaps` or equivalent) over every published `Estate.footprint`, not a distance/bounding-box heuristic. `ConflictStatus`: `open → investigating → confirmed_duplicate | dismissed`.

## Super Admin: platform metrics — `platformMetricsService.ts`
Currently **mock-backed on the frontend** (`src/data/mockPlatformMetrics.ts`), every zone rendering it tagged "Preview data" — these are the real endpoints that retire that flag once implemented:

| Method | Path | Response |
|---|---|---|
| GET | `/api/admin/attention-items` | `AttentionItem[]` |
| GET | `/api/admin/audit-log/recent?limit` | `AuditLogEntry[]` |
| GET | `/api/admin/metrics/platform-health?period` | `PlatformHealthMetrics` |
| GET | `/api/admin/metrics/marketplace-pulse` | `MarketplacePulseMetrics` |
| GET | `/api/admin/metrics/revenue` | `RevenueMetrics` |
| GET | `/api/admin/metrics/system-health` | `SystemHealthMetrics` |

Do not backfill these with plausible-looking numbers ahead of the real aggregation query — an honest zero/empty state is correct until the underlying data genuinely exists (this is the same rule the frontend just enforced on itself across Landing/Dashboard/testimonials/syndicate proceeds/support-chat claims — see recent frontend commit history).

---

## Keeping this in sync

When an endpoint here goes from stubbed to real:
1. Implement it in the relevant Modulith module.
2. Fill in the matching non-mock branch in the frontend's `src/services/*.ts` (the shape's already sketched there).
3. Update `~/landvault/INTEGRATION.md`'s "migrated to the service layer" list and this file's status line for that endpoint.


## Estate `state` is standardised (SB-1)

`state` on estate create/update accepts a state's name, ISO code (`NG-LA`) or a
common alias ("FCT", "Abuja", "Lagos State"), and is **returned as the
canonical name** from `nigerianStates.ts` — "FCT" comes back as "Federal
Capital Territory (Abuja)". Unknown values are `400 UNKNOWN_STATE`. A boundary
outside its declared state (beyond a 1 km margin) is `400
BOUNDARY_OUTSIDE_STATE`, and the message names the state it actually falls in.
Credit "State boundaries: GRID3, via geoBoundaries (CC BY 4.0)" wherever the
UI relies on this.


## Branches (TB-1..TB-3) and the seller's office

`GET/POST /api/portal/branches`, `PUT /api/portal/branches/{id}` — a tenant's
own branches; create/update need `portal.branches.manage` (Executive Director,
company-wide view). A branch is `{ id, name, street, city, state, phone,
email, createdAt }`; everything but `name` is optional and **shown publicly**
on that branch's listings. The marketplace `seller` gains `office: { street,
city, state, phone, email } | null` beside `branchName` and `companyName` —
additive to the frontend's `Seller` type. Frontend needs `portal.branches.manage`
in its permission list.

## Staff invitations (SI-1..SI-7)

`POST /api/portal/staff/invitations` `{ email, firstName, lastName, roleCode,
branchId? }`, `GET` (list with `status: pending|expired|accepted|revoked`),
`POST .../{id}/revoke`, `POST .../{id}/resend` — all `portal.staff.invite`.
Public: `POST /api/auth/invitations/preview` `{ token }` →
`{ email, firstName, companyName, roleName, branchName, expiresAt }`, and
`POST /api/auth/invitations/accept` `{ token, password }` → the login
response plus the refresh cookie. **The frontend needs**: a page at
`/accept-invitation` that reads the token from `location.hash` (`#token=`),
never the query string; `portal.staff.invite` in its permission list; and a
creating-a-tenant flow that no longer assumes the first Executive Director
has an account — they get an invitation email.

**Branch requests (changeset 068)**: `POST /api/portal/staff/invitations/requests`
(same body; `portal.staff.request`, Branch Manager) creates an
`awaiting_approval` invitation for the caller's own branch — nothing is sent
until `POST .../{id}/approve` (`portal.staff.invite`). Also
`POST .../{id}/reject` `{ reason }` and `POST .../{id}/cancel` (requester
only). `GET` is open to both permissions; a branch manager sees their branch
only. New statuses `awaiting_approval` and `rejected`; new fields
`approvalRequired`, `requestedBy`, `approvedAt`, `rejectedAt`,
`rejectionReason`. The frontend needs `portal.staff.request` in its permission
list and an approval queue for the Executive Director.

**Staff management**: `GET /api/portal/staff` → `[{ userId, firstName,
lastName, email, phone, status, roles: [{ roleCode, roleName, branchId,
branchName }], lastLoginAt, createdAt }]` (a branch manager sees their branch
only). `PUT /api/portal/staff/{userId}/role` `{ roleCode, branchId? }`
replaces all roles; `POST .../{userId}/deactivate` `{ reason }`;
`POST .../{userId}/reactivate`. Codes: `CANNOT_MANAGE_YOURSELF`,
`CANNOT_MANAGE_STAFF_MEMBER`, `LAST_EXECUTIVE_DIRECTOR`,
`STAFF_ALREADY_DEACTIVATED`, `STAFF_NOT_DEACTIVATED`, `STAFF_NOT_FOUND`,
plus the invitation role codes. Branch requests may now name any branch-level
role (surveyor included); `CANNOT_GRANT_ROLE` comes at approval instead.

**Roles**: `GET /api/portal/roles` (`portal.staff.invite` or
`portal.staff.request`) → `[{ code, name, description, scope:
"company"|"branch"|"either", permissions: string[], canGrant, custom }]`.
Replaces the role list hard-coded in the frontend's `staffService.ts`; use
`canGrant` to grey out roles the caller would get `CANNOT_GRANT_ROLE` for.

**Correcting an estate boundary (FP-2)**: `PUT /api/portal/estates/{id}/boundary`
`{ footprint, reason }` → `BoundaryChangeDto` with **200 `applied`** (and
`publicationBlocked` / `blockReason` / `warningConflictCount`) or **202
`pending`** when a published estate's land changes by more than 5% — the old
boundary stays live until a Super Admin decides. `GET .../boundary-changes`
(history, both shapes as GeoJSON), `POST .../boundary-changes/{id}/withdraw`.
Super Admin: `GET /api/admin/boundary-changes?status=pending`,
`POST .../{id}/approve` `{ note? }`, `POST .../{id}/reject` `{ note }`.
Codes: `BOUNDARY_NOT_SET`, `BOUNDARY_CHANGE_PENDING`, `BOUNDARY_UNCHANGED`,
`BOUNDARY_CHANGE_NOT_PENDING`, plus `PLOT_OUTSIDE_ESTATE` and
`BOUNDARY_OUTSIDE_STATE`. Conflict guidance for a cross-company overlap now
says "correct it — our team reviews the correction", not "contact support".

**Import tier mapping**: `POST .../plots/import/preview` and `.../plots/import`
accept an optional `tierMapping` form field, a JSON object
`{"<file value>": "<tier id>"}`; unmapped values still match by label or size;
a bad mapping is a 400. **Tenant create clashes** are now labelled by what
actually clashed: `RC_NUMBER_ALREADY_REGISTERED`, `EMAIL_ALREADY_REGISTERED`,
or `DUPLICATE_RECORD` — only route the user to the RC field on the first.

**Itemised conflicts**: the add-boundary, estate-correction (and its approval)
and plot-boundary responses carry `conflictChanges: { raised, resolved,
awaitingReview, stillOpen }`, each a list of `{ id, conflictType,
yourEntityId, yourEntityLabel, overlapAreaSqm, severity, status, live,
blocksPublication, underReview, guidance }` — the caller's side only.
`awaitingReview` means the overlap is gone but a cross-company conflict still
blocks until our team closes it. The existing counts remain for compatibility.

**Public plot list**: `GET /api/marketplace/estates/{id}/plots?available=&limit=&cursor=`
(no auth) → `Page<{ id, plotNumber, blockName, availability, isCorner,
priceTierId, nominalSizeSqm, actualAreaSqm, hasBoundary }>` — every plot,
including those the map can't draw (`hasBoundary: false`; tell the buyer the
boundary isn't surveyed). Portal estate detail gains `plotsWithoutBoundary`
for a developer warning. **No grid positions exist or will be added**: the
`/estates` grid stays mock-only; live mode uses `/marketplace`.

**Password rule** (register, reset-password, change-password, invitation accept;
never login): 400 `{ message, code: "WEAK_PASSWORD", fieldErrors: { password |
newPassword: message } }`, first failing rule, the frontend's own sentences —
plus one extra: "That password is too long — use fewer accented or special
characters." (under 64 characters but over 72 bytes). Change-password also
refuses the current password reused. Reset judges the password only after the
code is proven, and a refusal leaves the code (or invitation link) usable.

**Plot list by tier**: `GET /api/marketplace/estates/{id}/plots?priceTierId=…`
pages one tier's plots (`limit` default 100, max 500; `cursor` from the
previous page). Use it instead of loading the whole estate and filtering.

**Payments (Paystack), step 3**: `POST /api/transactions/{id}/payments` (no
body) → **201** new, or **200** the open link from the last 30 minutes →
`{ id, transactionId, reference, status: "initialized", amount, currency,
authorizationUrl, createdAt }`. Redirect the buyer to `authorizationUrl`;
Paystack returns them to `/payments/return?reference=…` on the frontend.
**Showing "paid" on that page is wrong** — wait for the backend to verify
(step 4). Errors: 404 `TRANSACTION_NOT_FOUND`, 409 `TRANSACTION_NOT_PAYABLE`,
400 `PAYMENT_PLAN_NOT_SUPPORTED` (milestone, or an instalment purchase opened before plans
existed) / `CURRENCY_NOT_SUPPORTED`, 502
`PAYMENT_PROVIDER_REFUSED`, 503 `PAYMENT_PROVIDER_UNAVAILABLE`.

**Payments, step 4 — confirm**: the return page (`/payments/return?reference=…`)
calls `POST /api/payments/{reference}/verify` (buyer's own, no body) →
`PaymentDto` with `status`, `gatewayResponse`, `channel`, `paidAt`.
`succeeded` → the transaction is `awaiting_finance` — say "payment received,
pending verification", **never** "you own this plot". `failed` → show
`gatewayResponse` and offer to pay again. `initialized` → not finished yet
(transfers take minutes): poll again shortly. `mismatched` → "we're reviewing
this payment". 404 `PAYMENT_NOT_FOUND`.

**Payments, step 5 — webhook** (server-to-server; not called by the frontend):
`POST /api/payments/webhook/paystack`, signed by Paystack. Effect for the
frontend: a payment can turn `succeeded` **without the buyer ever returning**
(a closed tab during a bank transfer) — so a buyer's transaction page should
read the transaction's status from the server, not assume the return page ran.

**Payments, step 6 — abandonment**: a purchase with nothing paid 15 minutes
after its hold ends becomes transaction status **`abandoned`** (new — add it to
`TransactionStatus` in `marketplaceCheckoutService.ts`); the plot is back on
sale. `PaymentDto` gains **`underReview`**: true when money arrived after the
purchase was abandoned — show "we've received your payment and our team will
contact you", never "you own this plot".

**Payments, step 7 — finance (developer portal)**: `portal.payments.verify`
(add to the frontend's permission list). `GET /api/portal/finance/transactions`
→ `[{ transactionId, transactionReference, estateName, plotLabel, buyerEmail,
expectedAmount, currency, awaitingSince, payment: { reference, amountPaid,
channel, paidAt, last4, gatewayResponse } | null, amountsMatch, letterApproved }]`.
`POST …/{id}/verify` → `{ transactionId, status: "verified", allocationLetterId, allocationLetterNumber }` (`status:
"on_plan"` for a deposit or non-final instalment — see "Instalment plans") (plot sold, reservation
`converted`, the buyer's allocation letter issued — only now may the buyer be told the plot is theirs). The two
letter fields name the letter just issued; they are null on every other decision.
`letterApproved: false` means verify will be refused with 409 `LETTER_NOT_APPROVED` until the Executive
Director approves the estate's letter — show it on the row and disable Verify;
`POST …/{id}/reject` `{ reason }` → `{ status: "rejected" }`. Errors: 404
`TRANSACTION_NOT_FOUND`, 409 `TRANSACTION_NOT_AWAITING_FINANCE` /
`PAYMENT_NOT_CONFIRMED` / `PLOT_NOT_RESERVED` / `LETTER_NOT_APPROVED`, 403 `COMPANY_SCOPE_REQUIRED`.
Phase 4: a plan payment's `expectedAmount` includes any late-payment penalty it carries; `penaltyAmount` and
`penalties[]` (`{ instalmentSequence, monthsLate, penaltyPct, amount }`) show it as its own line.

**Running plans and arrears (phase 4)**: `GET /api/portal/finance/plans?status=` (`portal.payments.verify`, own
company; a branch officer sees only their branch's) → `[{ transactionId, transactionReference, estateId, estateName,
plotId, plotLabel, buyerEmail, planName, currency, instalmentCount, instalmentsPaid, nextDueDate, nextDueAmount,
status, arrears }]`, most urgent first. `status` is `on_track` | `due_today` | `overdue` | `in_arrears` — the same the
buyer sees, worked out today; `arrears` as on the purchase's `standing`. Filter `status=` one of those or `late`
(overdue and in arrears); anything else is 400 `INVALID_STATUS`. Read-only.

**Payments, step 8a — payout account.** New permissions `portal.settlement.manage`
(Executive Director) and `admin.payouts.manage` (Super Admin) — add both to the
frontend's list. Portal: `GET /api/portal/settlement/banks` → `[{ code, name }]`;
`GET /api/portal/settlement/account` → `{ approved, pending, history[] }` of
`{ id, bankCode, bankName, accountNumber, accountName, currency, status, submittedAt,
decidedAt, decisionNote }` (`status`: pending | approved | rejected | withdrawn |
superseded); `POST /api/portal/settlement/account` `{ bankCode, accountNumber }` → 201
(**no account name field — the bank supplies it**; show it back to the user to
confirm); `POST /api/portal/settlement/account/{id}/withdraw`. Errors: 400
`UNKNOWN_BANK`, `ACCOUNT_NOT_RESOLVED`, `SETTLEMENT_ACCOUNT_UNCHANGED`; 403
`SETTLEMENT_REQUIRES_COMPANY_WIDE_SCOPE`; 409 `SETTLEMENT_ACCOUNT_PENDING`. Admin:
`GET /api/admin/settlement-accounts?status=pending|…|all` → review items with
`registeredName`, `tradingName`, `account`, `nameMatchesCompany`, onboarding fields,
`matchesOnboardingAccount` (null = none declared), `currentApproved`; `POST
…/{id}/approve`, `POST …/{id}/reject` `{ reason }`. Show the two match flags as
warnings, never as blockers.

**Payments, step 8b — payouts (Super Admin).** `admin.payouts.manage`, plus the
caller's own 2FA to send. `GET /api/admin/payouts/ready` → `[{ transactionId,
transactionReference, tenantId, companyName, estateName, plotLabel, amount, currency,
verifiedAt, payoutAccount: { bankName, accountLast4, accountName } | null, blockers[],
lastAttempt }]`; `GET /api/admin/payouts?status=` → `PayoutDto[]` `{ id,
transactionId, tenantId, companyName, amount, currency, status, reference, gatewayMessage, bankName,
accountLast4, createdAt, transferredAt }`; `companyName` is the selling company's
name (the ready list's), null only when none is on record. Every route that returns a
`PayoutDto` (the list, send, OTP, resend, cancel, the ready list's `lastAttempt` and
`GET /api/portal/payouts`) fills it. `status`: sending | awaiting_otp | pending |
success | failed | reversed | cancelled. `POST /api/admin/payouts` `{ transactionId }`
→ usually `awaiting_otp`: prompt for the code Paystack sent the account owner, then
`POST /api/admin/payouts/{id}/otp` `{ otp }`; `…/resend-otp`; `…/cancel`. Errors: 403
`TWO_FACTOR_REQUIRED`; 404 `SALE_NOT_PAYABLE`; 409 `PAYOUT_IN_PROGRESS`,
`COMPANY_NOT_ACTIVE`, `NO_APPROVED_PAYOUT_ACCOUNT`, `PAYOUT_NOT_AWAITING_OTP`; 400
`OTP_REJECTED`; 502 `PAYMENT_PROVIDER_REFUSED` (recorded as failed); 503
`PAYOUT_OUTCOME_UNKNOWN` — tell the user it's safe to press send again.

**Payouts in parts.** A sale above Paystack's per-transfer cap (₦10M) is paid in equal
parts: each `PayoutDto` carries `partNumber`/`partCount` and `amount` is the part.
`/api/admin/payouts/ready` items add `amountOwed`, `partCount`, `partsPaid`, `nextPart`,
`nextPartAmount` (null with blocker `CURRENCY_NOT_SUPPORTED` for non-NGN). Pressing pay
out sends the next part (one OTP per part); 409 `SALE_ALREADY_PAID` once all are paid.

**Escrow payouts (instalment plans, 3c).** What a sale owes comes in tranches, paid in
order. Ready items add `tranche` (`sale` outright | `share` the developer's share of one
verified plan payment | `release` what HomeTrue held, once the plan completes),
`trancheLabel` (e.g. "Developer's share of the deposit"), `sharePaymentId` (share only),
`trancheAmount`, and `heldInEscrow` (instalment plans still running; null for outright).
`partCount`/`partsPaid`/`nextPart`/`nextPartAmount` refer to the tranche; `amountOwed`
is everything owed now and unpaid. `PayoutDto` adds `tranche` and `sharePaymentId`.
Instalment purchases appear while on their plan, as soon as a share is owed. 409
`HELD_IN_ESCROW`: every share owed so far is paid; the rest waits for completion.

**Payments, step 8c — payout outcomes.** `GET /api/portal/payouts` (`portal.payments.verify`,
company-wide; 403 `COMPANY_WIDE_SCOPE_REQUIRED` when narrowed to a branch) → the company's
own `PayoutDto[]`, newest first, read-only. `PayoutDto` gains `reviewReason` (non-null =
HomeTrue is looking into it). A payout's status can change after the fact: `success` can
become `reversed` (the part is owed again and reappears on the admin ready list).

**Renamed (changeset 079): `payment.cardLast4` → `payment.last4`** in the finance queue. It is
the last 4 digits of whatever paid — a card, or the paying bank account for a transfer;
read it with `channel` ("card ending 4081" vs "account ending X890").

**Refunds.** Buyer: `GET /api/payments/{reference}/refund` → `{ status, amount, currency,
needsAccount, accountBankName, accountLast4, expectedAt, refundedAt }` (`status`: pending |
processing | needs-attention | processed | failed). When `needsAccount` is true, show the
bank picker (`GET /api/payments/banks`) and `POST /api/payments/{reference}/refund-account`
`{ bankCode, accountNumber }` — no name field; the bank supplies it. Admin
(`admin.payouts.manage` + 2FA to send): `GET /api/admin/refunds/due`, `GET /api/admin/refunds`,
`GET /api/admin/refunds/{id}` (with `account` and `accountNameMatchesBuyer` — a warning only),
`POST /api/admin/refunds` `{ paymentReference }`, `POST /api/admin/refunds/{id}/send-to-account`.
Errors: 404 `REFUND_NOT_DUE`, `REFUND_NOT_FOUND`; 409 `REFUND_IN_PROGRESS`,
`REFUND_NOT_AWAITING_ACCOUNT`, `REFUND_ACCOUNT_MISSING`; 503 `REFUND_OUTCOME_UNKNOWN`.

**Late money (developer finance).** `portal.payments.verify`. `GET /api/portal/finance/late-payments`
→ `[{ transactionId, transactionReference, estateName, plotLabel, buyerName, buyerEmail,
paymentReference, agreedPrice, amountPaid, currency, channel, paidAt, plotAvailable, amountsMatch, letterApproved }]`.
`POST /api/portal/finance/late-payments/{transactionId}/allocate` → `{ transactionId, status: "verified",
allocationLetterId, allocationLetterNumber }`, and the buyer's allocation letter is issued (409 `PLOT_NO_LONGER_AVAILABLE` / `PAYMENT_NOT_CONFIRMED` / `LETTER_NOT_APPROVED`,
nothing changed). `POST …/{transactionId}/refund`
`{ reason }` → `{ status: "refund_due" }`. Only offer Allocate while `plotAvailable` is true.

**Errors from payments and checkout are always `{ message, code, fieldErrors }`**, including
bad input: 400 `VALIDATION_ERROR` with `fieldErrors` (e.g. `{ "reason": "must not be blank" }`,
`{ "accountNumber": "must be a 10-digit account number" }`), 400 `MALFORMED_REQUEST` (body not
JSON), 400 `INVALID_PARAMETER` (an id in the path that isn't a UUID). Show `fieldErrors` beside
the matching inputs.

**Own purchase.** Finance verify/reject and late-payment allocate/refund return 403
`OWN_PURCHASE` when the signed-in staff member is the purchase's buyer. Show the message; another
finance officer or director must act.

**Buyer emails.** The buyer is emailed when finance verifies ("Good news: {plot} at {estate} is
yours") or rejects ("Your payment for {plot} is being returned", with the developer's reason and no
refund date), when late money is allocated or refunded, and when a refund needs their bank account.
Every one links to **`{frontend origin}/purchases/{transactionId}`**, the origin taken from
`PAYSTACK_CALLBACK_URL` — so the frontend needs a `/purchases/:transactionId` route that opens the
purchase (with its payment and refund) after sign-in. Sent only after the decision commits; a mail
failure never fails the request.

The two "is yours" emails (finance verify, late money allocated) also name the allocation letter —
"Your allocation letter, HT-ALC-2026-000012, is ready" — and link to
**`{frontend origin}/documents?letter={allocationLetterId}`**. Today that opens the document vault
(the letter is listed there); the vault may use `letter` to open that letter straight away
(`GET /api/documents/allocation-letters/{id}`, own only). Rejections and refunds carry no letter.


## Onboarding requests — **built** (no frontend service yet)

A developer company asking to join. **Public**: `POST /api/onboarding-requests`
`{ companyName, rcNumber?, companyAddress?, statesOfOperation?: string[],
estimatedEstateCount?, contactName, contactRole?, contactEmail, contactPhone,
preferredContactMethod: "email"|"phone"|"whatsapp"|"meeting",
preferredContactTime?, message? }` → **202** `{ message }`, always the same body
(a repeat from an email with a request still open is accepted and silently
dropped). 400 with `fieldErrors` for a bad field (`statesOfOperation` names an
unknown state); 429 `RATE_LIMITED` with `Retry-After` past 5 an hour per address.
Creates no account and no company.

**Super Admin** (`admin.tenants.view` to read, `admin.tenants.manage` to act):
`GET /api/admin/onboarding-requests?status=new|contacted|converted|declined&attention=true`
(paged, newest first), `GET .../{id}`. Every request carries `needsAttention: boolean` and
`attentionReason: "no_contact_yet" | null` — `no_contact_yet` when it is still `new` after
**2 working days** (Mon–Fri, Lagos time; public holidays count as working days;
`landvault.onboarding.attention-after-working-days`). `attention=true` lists only those,
**waiting longest first**, and combines with `status` (any status but `new` gives an empty
page). Computed at read time; contacting, declining or converting clears it. `POST .../{id}/contacted` `{ note? }`,
`POST .../{id}/decline` `{ reason }` (not emailed to the company),
`POST .../{id}/convert` with **exactly `POST /api/admin/tenants`'s body** —
prefill it from the request; it creates the tenant and invites its director,
and sets the request `converted` with `organizationId`, all or nothing.
Codes: `ONBOARDING_REQUEST_NOT_FOUND`, `ONBOARDING_REQUEST_CLOSED`, plus tenant
creation's own (`RC_NUMBER_ALREADY_REGISTERED`, `EMAIL_ALREADY_REGISTERED`).
**The frontend needs**: a public "List your estates on HomeTrue" form and an
admin queue with a "convert" action that opens the tenant wizard prefilled.

## Company-made roles — **built**

`GET /api/portal/permissions` → `[{ code, label, description, requiresCompanyWide, held,
group, groupLabel }]` (the role editor's checkboxes), ordered by group and then within the
group — both orders HomeTrue's (changeset 085). `group`/`groupLabel` are always present
(the database refuses an ungrouped `portal.*` permission). Groups today, in order:
`estates` "Estates", `finance` "Money", `branches` "Branches", `staff` "Staff and roles".
`permissions` in a create/edit body is capped at 100 entries — a request bound kept above
the catalogue size by a test, not a business limit. `POST /api/portal/roles` `{ name, description?,
scope: "company"|"branch"|"either", permissions: string[] }` → 201 the role
(`AssignableRoleDto`, `custom: true`, a generated `code` like `custom_9f…`).
`PUT /api/portal/roles/{code}` `{ name?, description?, permissions? }` (scope can't
change; don't send it), `DELETE /api/portal/roles/{code}` → 204. All `portal.roles.manage`
(Executive Director); create, edit and delete also need a company-wide view (the two
GETs don't). `GET /api/portal/roles` now
includes the company's own roles, and invitations / role changes take their
`code` like any other. Codes: `UNKNOWN_PERMISSION`, `CANNOT_GRANT_PERMISSION`,
`CANNOT_MANAGE_ROLE`, `PERMISSION_REQUIRES_COMPANY_SCOPE`, `INVALID_ROLE_SCOPE`,
`ROLE_SCOPE_IMMUTABLE`, `ROLE_NAME_TAKEN`, `ROLE_LIMIT_REACHED`, `ROLE_IN_USE`,
`STANDARD_ROLE_LOCKED`, `ROLE_NOT_FOUND`, `ROLES_REQUIRE_COMPANY_WIDE_SCOPE`.
**The frontend needs** `portal.roles.manage` in its permission list and a role
editor; edits reach holders at their next sign-in (within 15 minutes).
