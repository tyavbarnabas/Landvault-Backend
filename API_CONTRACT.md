# API contract (stub, derived from the frontend)

**Status: not yet implemented anywhere.** This is a reverse-engineered contract read off every exported function in `~/landvault/src/services/*.ts` — the frontend's service layer already calls these shapes conditionally (`if (apiClient.isMockMode) { …mock… } else { …real fetch, mostly unfilled… }`), so this doc is the target for that "else" branch, not a description of something live. Treat paths as proposed, not fixed — adjust freely, but update the corresponding service file in the frontend repo (and this doc) together so the two never drift apart silently.

Base path convention below: `/api/...`. Auth: `Authorization: Bearer <token>` on everything except login/register. Pagination envelope and error body are defined in `AGENTS.md`.

## Auth — `authService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/api/auth/login` | `{ email, password }` | `{ user: AuthUser, token }` |
| POST | `/api/auth/register` | `RegisterInput` | `{ user: AuthUser, token }` |
| POST | `/api/auth/refresh` | (cookie or stored refresh token) | `{ token }` |
| POST | `/api/auth/forgot-password` | `{ email }` | `{ message }` — always 200, always the same body |
| POST | `/api/auth/reset-password` | `{ email, code, newPassword }` | `{ message }` |
| POST | `/api/auth/2fa/setup` | — (authenticated) | `{ secret, otpAuthUri }` — does **not** enable 2FA |
| POST | `/api/auth/2fa/confirm` | `{ code }` (authenticated) | `{ recoveryCodes }` — shown once, enables 2FA |
| POST | `/api/auth/2fa/verify` | `{ challengeToken, code }` | `AuthResponse` — the second login step |
| POST | `/api/auth/2fa/disable` | `{ code }` (authenticated) | `{ message }` |
| POST | `/api/auth/2fa/recovery-codes/regenerate` | `{ code }` (authenticated) | `{ recoveryCodes }` |

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
| POST | `/api/portal/estates/{id}/title` | `{ titleType, titleNumber, issuedDate, ... }` — 1:1 with the estate |
| POST | `/api/portal/estates/{id}/verification-checks` | `{ checkType, status?, verificationSource?, notes }` |

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

- **Refusal is a 409** naming every failing condition in `message`; `code` is the first of `PUBLICATION_VERIFICATION_PENDING`, `PUBLICATION_ENTITLEMENT_MISSING`, `PUBLICATION_TENANT_NOT_ACTIVE`, `PUBLICATION_CONFLICT_OUTSTANDING`. A conflict refusal never identifies the other company.
- **A MEDIUM conflict doesn't refuse** — publishing succeeds with `warningConflictCount > 0` and a `warning` to show.
- Publishing an estate that's already published re-checks everything. That's the way to learn why a published estate isn't on the marketplace (tenant suspended, a conflict arrived since).
- **`published` is the developer's intent and is never cleared by the platform.** If the company stops qualifying, its listings disappear from the marketplace and return on reinstatement, with no republishing.

### Public marketplace — **built, no authentication**

No `Authorization` header needed (or sent). Rate-limited: 429 with `Retry-After` beyond the limit.

| Method | Path | Returns |
|---|---|---|
| GET | `/api/marketplace/estates` | `Page<Listing>` — filters `q`, `state`, `city`, `minPrice`, `maxPrice`, `currency` (default `NGN`), `minSize`, `maxSize`, `titleType`, `intent`; `sort` = `newest` (default) \| `price_low` \| `price_per_sqm` \| `plots_remaining`; `limit`/`cursor` |
| GET | `/api/marketplace/estates/{id}` | `Listing` |
| GET | `/api/marketplace/estates/{id}/geojson` | GeoJSON `FeatureCollection`, same shape as the portal's |

- Only estates passing all five conditions appear. Anything else, including an id that exists but isn't eligible, is a plain **404**, with no hint that it exists.
- `Listing` follows the frontend's shape: `priceTiers` (each with `price`, `pricePerSqm` — null for a unit-type tier — `plotsRemaining`, `availability`), `cornerPremiumPct`, `seller: { branchName, companyName }`, `verified: true`, plus `fromPrice`/`fromPriceCurrency`, `plotsRemaining`, `hasMap`, and `verificationChecks`.
- **`verificationChecks` carry their `verificationSource`** — `manual_review` versus a registry is a real difference in front of a buyer. A check type that isn't listed was **never checked**: render it as unchecked, never as verified.
- **Plot `availability` on the map is only `AVAILABLE` or `UNAVAILABLE`.** Reserved and sold are deliberately indistinguishable. Plots carry `priceTierId` and `isCorner` but no price: compute it from the tier and `cornerPremiumPct`, as `priceForPlot()` already does.
- **A price filter only matches listings priced in the requested `currency`.**

Differences from the frontend's current services, all in AGENTS.md: the path is `/api/marketplace/estates`, not `/listings`; plots come from `/geojson`, not a paginated `/listings/{id}/plots`; plot status is collapsed; `paymentPlans` is absent (nothing stores it yet), so the frontend's `paymentPlans.includes(...)` filter needs guarding.

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

## Checkout / transactions — `marketplaceCheckoutService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/api/checkout/transactions` | `{ reservationId, intent, plan, installmentMonths? }` | `Transaction` — **built** |
| POST | `/api/checkout/transactions/{id}/payment` | `{ method: MarketplacePaymentMethod }` | `{ requiresTransfer: boolean, account?: VirtualAccountDetails }` |
| POST | `/api/checkout/transactions/{id}/confirm` | — | `Transaction` |
| POST | `/api/checkout/transactions/{id}/finance-verify` | — (Finance-role action) | `Transaction` |
| GET | `/api/checkout/transactions/{id}` | — | `Transaction` — **built** |

`TransactionStatus`: `pending_payment → payment_received → awaiting_finance → verified` (or `rejected`). A successful checkout must create the buyer's `OwnedPlot` — don't leave that as a frontend-only side effect.

**Only `pending_payment` is produced today**; everything after it belongs to
`finance`, which is not built. A reservation never allocates: the plot stays
held, never sold, until a finance-role human verifies a payment.

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

## Portfolio (owned plots + payments) — `portfolioService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/portfolio/plots?cursor&limit` | — | `Page<OwnedPlot>` |
| GET | `/api/portfolio/plots/{id}` | — | `OwnedPlot` |
| PATCH | `/api/portfolio/plots/{id}/status` | `{ status, patch? }` | `OwnedPlot` |
| GET | `/api/portfolio/plots/{plotId}/installment-schedule` | — | `InstallmentSchedule` |
| POST | `/api/portfolio/installments/payments` | `SubmitInstallmentPaymentInput` | `PaymentRecord` (status `pending_verification`) |
| POST | `/api/portfolio/plots/{plotId}/installments/payments/{paymentId}/verify` | — (Finance-role) | `VerifyInstallmentPaymentResult` |
| POST | `/api/portfolio/plots/{plotId}/restructure` | `{ note }` | `OwnedPlot` |

`OwnedPlot.status` is a real lifecycle, not a 3-state enum: `reserved → pending_verification → allocated → installment_active → completed`, plus side states `in_arrears`, `upgrade_pending`, `transferred`, `superseded`. Never collapse these into fewer states server-side either.

## Documents — `documentsService.ts`
| Method | Path | Response |
|---|---|---|
| GET | `/api/documents?cursor&limit` | `Page<Document>` |
| GET | `/api/documents/by-plot/{plotId}` | `Document[]` |
| POST | `/api/documents/{id}/void` | 204 |
| POST | `/api/documents` (batch issue) | `Document[]` |

File storage: frontend notes it needs real S3/MinIO-backed URLs behind "download" — `Document` itself carries only metadata; add a signed-URL or streaming endpoint when wiring real files.

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
