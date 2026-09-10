# API contract (stub, derived from the frontend)

**Status: not yet implemented anywhere.** This is a reverse-engineered contract read off every exported function in `~/landvault/src/services/*.ts` — the frontend's service layer already calls these shapes conditionally (`if (apiClient.isMockMode) { …mock… } else { …real fetch, mostly unfilled… }`), so this doc is the target for that "else" branch, not a description of something live. Treat paths as proposed, not fixed — adjust freely, but update the corresponding service file in the frontend repo (and this doc) together so the two never drift apart silently.

Base path convention below: `/api/...`. Auth: `Authorization: Bearer <token>` on everything except login/register. Pagination envelope and error body are defined in `AGENTS.md`.

## Auth — `authService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/api/auth/login` | `{ email, password }` | `{ user: AuthUser, token }` |
| POST | `/api/auth/register` | `RegisterInput` | `{ user: AuthUser, token }` |
| POST | `/api/auth/refresh` | (cookie or stored refresh token) | `{ token }` |

`AuthUser`: `{ name, email, phone, country, currency, kycStatus, kycType, twoFAEnabled, role: "client"|"super_admin", permissions: string[] }`

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

## Marketplace plots — `marketplacePlotsService.ts`
| Method | Path | Response |
|---|---|---|
| GET | `/api/marketplace/listings/{listingId}/plots?cursor&limit` | `Page<ListingPlot>` |
| GET | `/api/marketplace/listings/{listingId}/plots/{plotId}` | `ListingPlot` |

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
| POST | `/api/checkout/transactions` | `InitiateTransactionInput` | `Transaction` |
| POST | `/api/checkout/transactions/{id}/payment` | `{ method: MarketplacePaymentMethod }` | `{ requiresTransfer: boolean, account?: VirtualAccountDetails }` |
| POST | `/api/checkout/transactions/{id}/confirm` | — | `Transaction` |
| POST | `/api/checkout/transactions/{id}/finance-verify` | — (Finance-role action) | `Transaction` |
| GET | `/api/checkout/transactions/{id}` | — | `Transaction` |

`TransactionStatus`: `pending_payment → payment_received → awaiting_finance → verified` (or `rejected`). A successful checkout must create the buyer's `OwnedPlot` — don't leave that as a frontend-only side effect.

## Reservations — `reservationService.ts`
| Method | Path | Response |
|---|---|---|
| POST | `/api/reservations` `{ listingId, plotId }` | `Reservation` (time-boxed hold, ~45 min) |
| DELETE | `/api/reservations/{id}` | 204 |
| POST | `/api/reservations/{id}/convert` | 204 |

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

## KYC — `kycService.ts`
| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/kyc/status` | — | `KycRecord` |
| POST | `/api/kyc` | `SubmitKycInput` | `KycRecord` |

`KycBuyerType`: `"local" | "diaspora"` (derived from buyer country); doc types `nin \| passport \| proof_of_address`.

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
| POST | `/api/admin/tenants/draft` | `CreateTenantDraftInput` | `Tenant` (Stage 1: identity/contact/presence) |
| POST | `/api/admin/tenants/{id}/submit-verification` | `SubmitVerificationInput` | `Tenant` (Stage 2: documents/regulatory/directors/financial) |
| POST | `/api/admin/tenants/{id}/begin-review` | — | `Tenant` |
| POST | `/api/admin/tenants/{id}/verification-decision` | `RecordDecisionInput` | `Tenant` (appends `verificationHistory`, never overwrites it) |
| POST | `/api/admin/tenants/{id}/documents/{documentId}/resubmit` | `{ fileName, size }` | `Tenant` |
| PATCH | `/api/admin/tenants/{id}/plan` | `{ plan, entitlements }` | `Tenant` |
| PATCH | `/api/admin/tenants/{id}/status` | `{ status, actor }` | `Tenant` |
| POST | `/api/admin/tenants/{id}/support-access` | `{ reason, actor }` | `SupportAccessGrant` |
| GET | `/api/admin/tenants/{id}/support-access` | — | `SupportAccessGrant[]` |
| GET | `/api/admin/audit-log?cursor&limit` | — | `Page<AuditLogEntry>` |

`Tenant.verificationState`: `created → documents_submitted → under_review → verified` (or `rejected`, `suspended`). `Tenant` has `branches: TenantBranch[]` — real multi-branch support belongs here, not bolted on later.

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
