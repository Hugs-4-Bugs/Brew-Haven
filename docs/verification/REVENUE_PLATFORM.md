# Restaurant Revenue Automation Platform — Engineering Report

**Date:** 2026-10-22 · **Base commit:** `975a14a` (role-aware experience) · **Status:** delivered on the running stack

---

## 1. Existing architecture (audited before any change)

| Layer | Discovered |
|---|---|
| Backend | Spring Boot 2.7 (Java 17), JWT stateless auth (`JwtFilter` + `anyRequest().authenticated()`), admin checks at the service layer (`jwtFilter.isAdmin()`), `ddl-auto=update`, MariaDB `CafeMgt` |
| Entities | User (status/role), Category, Product (status, price, category FK), Bill (uuid `BILL-<epoch-millis>`, email, total, productDetails JSON), CardPayment/UPIPayment/WalletPayment |
| APIs | `/user/*`, `/category/*`, `/product/*`, `/bill/*`, `/dashboard/details`, `/api/payment/*` — all JWT-protected except login/signup/forgot + swagger |
| Frontend | React 18 + Vite 5 + react-router 6, plain-CSS espresso theme, guest surface (`/`, `/menu`, preference modal, carousel, recommendations, pairings), admin console (POS, products, categories, users, excel), 108 tests |
| Data at start | 4 users, 11 categories, 14 products, 11 bills, 13 payments — all preserved unchanged |

**Capability matrix (spec §3):** customer profiles (none → additive), order history (bills by email, no customer view → additive `/bill/myBills`), recommendations (frontend deterministic engine — kept, now with server-pref sync), upselling (pairings — kept + measured), offers/campaigns (none → new), segmentation (none → computed from bills), analytics (dashboard counts only → full intelligence layer), attribution (none → server-verified), automation (none → audited rules), feedback (none → new).

## 2. Database changes (additive only; backup taken first)

Backup: `CafeMgt-pre-revenue-platform-20261022-004815.sql` (restorable in place). **No existing table dropped/reset; no existing row modified.**

| Change | Type |
|---|---|
| `customer_preference`, `feedback`, `campaign`, `campaign_event`, `interaction_event`, `automation_run` | NEW tables |
| `product.featured` (nullable bool — legacy rows stay NULL) | additive column |
| `bill.campaign_code` (nullable varchar 40) | additive column |
| `campaign.code` nullability relaxed on the empty brand-new table (creation-order fix) | fix during bring-up |

Migration verified: row counts identical pre/post (4/14/11/11/13), one reserved-word DDL issue (`trigger` → `trigger_type`) caught and fixed at boot, zero data loss.

## 3. New backend APIs (all JWT-protected; admin-only where noted)

| Endpoint | Access | Purpose |
|---|---|---|
| `GET /user/me` | any | own profile (checkout pre-fill) |
| `GET /bill/myBills` | any | own order history |
| `POST /customer/preferences` (+GET, +clear) | any | server-side preference sync (≤16 KB JSON, validated) |
| `POST /feedback/submit` (+`GET /feedback/all` admin) | any / admin | post-order rating (own bills only, idempotent) |
| `GET /campaign/active`, `POST /campaign/event` | any | banner + VIEWED/CLICKED (once per campaign for VIEWED) |
| `POST /campaign/create`, `GET /campaign/all`, `POST /campaign/status`, `POST /campaign/send` | admin | campaign lifecycle; codes are server-generated (`BREW<id>`) |
| `POST /events/record` | any | interaction events (UPSELL_SHOWN/ACCEPTED, cart funnel, PREF_SAVED) with 60/min in-memory rate limit |
| `GET /analytics/overview\|customers\|campaigns\|upsells\|insights\|cart-funnel` | admin | revenue intelligence |
| `POST /automation/thank-you` (any, idempotent per bill), `POST /automation/run` (admin), `GET /automation/runs\|status` (admin) | mixed | audited automation |
| `POST /product/setFeatured` | admin | signature merchandising flag |

**Existing APIs unchanged.** `ProductWrapper` JSON gains one optional `featured` field (backward compatible). `POST /bill/generateReport` accepts an optional `campaignCode` (absent = legacy behavior).

## 4. Honesty & safety model

- **No external AI anywhere.** The insight engine is deterministic and labelled `rule-based`; every insight carries `sampleSize` + `confidence` and degrades to **"Not enough data yet"** below thresholds (10 bills peak-hours, 3 co-occurrences affinity, 6 bills trend, 2 campaigns for comparison, 5 accepts for upsell leader).
- **Revenue attribution is server-verified only:** CONVERTED events are created exclusively inside the bill flow after validating the campaign code (active + in-window + under limit); idempotent per bill. Viewed/clicked are labelled client-reported.
- **Sends are never faked:** `NotificationChannel` adapter (EmailChannel wrapping the existing EmailUtils + always-available LogChannel). With no SMTP configured, sends are recorded with `channel=NONE` and the UI says "No delivery provider configured — events recorded only". WhatsApp/SMS = future adapters, no stubs pretending to work.
- **Automation safety:** thank-you is idempotent per bill and SKIPS honestly without a channel; win-back/VIP detections create **DRAFT** campaigns only; the nightly sweep is config-gated OFF (`cafe.automation.scheduled-enabled=false`) and records only. Every run is audited in `automation_run` with dedupe keys.
- **Health/allergy rules unchanged:** verified tags as facts, advisories say "confirm with staff", no medical claims; the health note never leaves the device except to the customer's own preference record.
- **No secrets to the frontend** (config-driven SMTP stays server-side; no API keys in the bundle).

## 5. Frontend changes

**Customer journey (guest surface):** cart with localStorage persistence → cart bar → CheckoutDrawer (profile pre-fill via `/user/me`, Cash at counter / UPI via the existing payment APIs, optional offer code) → order placed (existing billing API) → thank-you + feedback form → My Orders (`/my-orders`) with one-click reorder. Campaign banner (active offers, apply-at-checkout). Preference save/clear now syncs to the account record for signed-in customers (device record adopted when absent). Upsell pairing gains "+ Add" and every display/accept is measured. Signature carousel uses admin-curated featured products when live (fallback: documented config).

**Admin console:** new pages — **Revenue Intelligence** (`/analytics`), **Customers** (`/customers`, segments from real bills), **Campaigns** (`/campaigns`, create/activate/send/measure), **Automation** (`/automation`, run detections + audit trail); Products gains a signature toggle; nav adds Intelligence + Manage entries (admin-only) and customer "My Orders".

## 6. Verification actually executed

| Gate | Result |
|---|---|
| Backend `mvn verify` (67 unit + 1 IT, incl. 20 new tests) | **68/68 PASS, BUILD SUCCESS** |
| Frontend `tsc -b --noEmit` | 0 errors |
| Frontend `vitest run` (13 files) | **120/120 PASS** (12 new, zero regressions) |
| Frontend production build | SUCCESS (411 KB js / 131 KB gzip) |
| DB migration | all tables/columns created; row counts identical pre/post |
| Browser E2E (real Chrome) | 14-step journey: register → login → modal → skip → browse → save prefs → recs change → add item → upsell accept → checkout with BREW3 → order placed (bill ₹358, campaign_code BREW3) → feedback 5 stars → My Orders → admin analytics/customers/campaigns/automation → **zero page/console errors**, mobile 390px OK |
| Server-side proof | CONVERTED event (bill 12, ₹358, channel SERVER); thank-you run SKIPPED/NONE (honest); feedback row (5 stars); upsells shown 46 / accepted 1; funnel checkout 1 → order 1 |
| Existing-app regression | admin login/dashboard/products/bills flows re-verified; POS/payment APIs untouched |

## 7. Known limitations (honest)

1. **Card/wallet payments in customer checkout:** counter-assisted only (existing endpoints kept admin-flow semantics); Cash + UPI are self-serve.
2. **Anonymous guests:** menu snapshot (dated) + config-featured carousel; live menu/featured/campaign banner/events require sign-in (menu APIs are JWT-protected — security posture intentionally unchanged; relaxing it needs explicit approval).
3. **Upsell/funnel metrics** cover signed-in sessions only (client-reported, labelled as such).
4. **Campaign delivery** records events until SMTP/WhatsApp/SMS is configured; segmentation needs order history to exist (no data → "Not enough data yet").
5. Campaign offers are display+attribution only — price recalculation at checkout would require a billing-contract change (spec §18 forbids silently changing existing fields).

## 8. Deliverables

- Commit: see final SHA in the session log (this file ships in the same commit).
- Evidence screenshots: `platform-01…06` in `docs/verification/evidence/`.
- Recovery point: branch `recovery/pre-revenue-platform-975a14a` + full bundle (119 MB) + pre-migration SQL backup.
