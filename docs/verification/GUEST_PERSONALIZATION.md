# Guest Personalization & Restaurant Automation — Implementation Report

Date: 2026-10-21 · Branch: `main` · Component: `frontend/` (public guest surface)

This document describes what was implemented, how every feature is grounded in
real data, and the honest limitations that require future (separately
approved) backend work. No backend code, security configuration, or database
schema was changed by this feature.

---

## 1. What was built

| # | Feature | Where |
|---|---------|-------|
| 1 | Public guest homepage at `/` (was: redirect to protected `/dashboard`) | `src/pages/GuestHomePage.tsx` |
| 2 | First-visit preference modal (3 steps, multi-select checkboxes, optional note ≤ 280 chars) | `src/components/guest/PreferenceModal.tsx` |
| 3 | Versioned, crash-safe preference persistence (`saved` vs `skipped` distinct states) | `src/guest/preferenceStore.ts` |
| 4 | Deterministic recommendation engine grounded in real menu data + optional AI hook | `src/guest/recommendations.ts` |
| 5 | Signature-dish hero carousel (auto-advance, manual controls, reduced-motion) | `src/components/guest/SignatureCarousel.tsx`, `src/guest/featured.ts` |
| 6 | Restaurant-selected favourites section (honestly labelled, not live sales data) | `src/pages/GuestHomePage.tsx` |
| 7 | Public menu page `/menu` with category chips + preference-aware sort/filter | `src/pages/MenuPage.tsx` |
| 8 | Complementary pairing suggestions (informational, dismissible, never auto-added) | `src/guest/pairings.ts`, `src/components/guest/MenuItemCard.tsx` |
| 9 | Returning-guest welcome strip with Edit / Clear preferences | `src/pages/GuestHomePage.tsx` |

All existing authenticated routes (`/dashboard`, `/billing`, `/bills`,
`/products`, `/categories`, `/users`, `/excel-import`, `/change-password`,
`/login`, `/signup`, `/forgot-password`) are unchanged. The app shell
(sidebar Layout) now mounts as a pathless layout route; every protected route
still requires a JWT exactly as before.

## 2. Data sources (honesty contract)

**The backend requires a JWT for ALL product/category reads**
(`SecurityConfig: anyRequest().authenticated()`; anonymous requests to
`/product/productList` return 403 — verified live). Because changing the
security posture is out of scope for this task, the guest experience uses a
hybrid data strategy:

1. **Snapshot mode (anonymous guests).** A dated snapshot
   (`src/guest/menuSnapshot.ts`, `SNAPSHOT_CAPTURED_AT = 2026-10-21`) of the
   REAL menu captured from the live MariaDB `CafeMgt` database. Every name,
   price (₹), category id and description is a verbatim database value.
   The 4 test/evidence rows in the database (Probe Latte v2, E2E Special
   Latte, E2E Brownie, NoDesc Cookie) are excluded from the curated guest
   snapshot. The homepage/menu display the snapshot date so guests are never
   misled into thinking it is live.
2. **Live mode (authenticated sessions).** When a JWT is present, the pages
   fetch through the existing API layer (`api/cafe.ts` → `/product/productList`,
   `/category/get`) and render the real backend rows (including availability
   via the `status` field). Any fetch failure falls back to the snapshot —
   the guest experience never breaks.

**Recommendations, the carousel, "favourites" and pairing suggestions are
computed ONLY from these real items.** Nothing invents dishes, prices or
availability.

### Signature & popular configuration

The backend has no featured/signature flag. Until restaurant-controlled
featured management is approved server-side, featured status lives in the
documented frontend config `src/guest/featured.ts`
(`SIGNATURE_ITEM_IDS = [1, 2, 4, 8, 10]`, `POPULAR_ITEM_IDS = [2, 8, 10, 1]`).
Item data (name/price/description/availability) is resolved from the menu
source at runtime; unavailable items are skipped. The favourites section is
labelled "Restaurant-selected favourites — not live sales statistics" because
no public order-count API exists.

## 3. AI integration status

**No AI service is wired into this deployment** — the recommendation engine
is deterministic and labelled "⚙ Smart match". The architecture is
AI-ready:

- Setting `VITE_AI_RECOMMENDATION_URL` enables an optional AI pass: the
  engine POSTs the structured preference labels **plus the real candidate
  items** and only accepts response picks whose ids exist in the candidate
  set (invented dishes are rejected — grounding rule). Failures or timeouts
  (3 s) fall back to the rule-based matcher with an honest notice
  ("AI service unavailable — showing smart matches").
- **The guest's free-text note is NEVER sent to the external service** —
  only structured preference labels. Health-related notes are detected
  locally and produce a staff-confirmation advisory.
- A missing/unreachable AI endpoint can never block browsing: the menu,
  carousel and filters are fully functional without it.

The reference repository's Genkit flow (`get-personalized-recommendation`)
asked an LLM to invent a product name + reason with NO menu grounding —
that behaviour was deliberately NOT ported. The smallest backend change that
would enable a proper AI service later: an internal recommendation endpoint
that receives preference labels and returns ranked real item ids + reasons
(approval required).

## 4. Health, allergy and dietary safety

- Health-term notes (diabetes, allergy, digestive discomfort, …) shape
  matching only and ALWAYS produce: *"…it is not medical advice. Please
  confirm ingredients with our staff before ordering."*
- The engine never claims a dish treats/cures/is medically safe (enforced by
  tests: `guest-recommendations.test.ts` asserts forbidden claim words).
- **Verified** ingredient tags come only from real item text (e.g. "Butter
  Naan with garlic" → dairy). Dairy exclusion removes those items and
  reports the count, with a cross-contact caveat.
- **Unverifiable** concerns (gluten, nuts — no menu description states them)
  produce advisories, not fake safety guarantees: "confirm with staff".
- Availability: unavailable items (`status: 'false'`) are excluded from
  recommendations and the carousel; the menu lists them badged, never
  recommended.

## 5. Preference persistence

- `localStorage['brewHaven.guestPrefs.v1']` → `{version: 1, status:
  'saved'|'skipped', preferences, updatedAt}`.
- `saved` and `skipped` are distinct — skipped guests are never re-prompted;
  the modal only appears for a missing/corrupt record (fresh visit).
- Corruption safety: invalid JSON, unknown versions, invalid statuses, and
  hostile payloads are all sanitized or reset; storage failures (quota/
  private mode) degrade to in-session state; page never crashes (tested).
- Save/clear dispatch a window event so mounted pages update instantly.
- Authenticated guests: the backend exposes NO profile/preferences endpoint —
  none was invented; preferences remain client-side for all users (documented
  limitation).

## 6. Verification performed (all executed)

- **Unit/component tests (vitest): 99/99 PASS** — 71 new guest tests across
  6 files (store 12, recommendations 18, modal 14, carousel 10, menu 8,
  home 9) + 28 pre-existing tests (no regressions).
- **Type checking:** `tsc -b --noEmit` — 0 errors.
- **Production build:** `npm run build` — success (73 modules, 26.09 kB CSS,
  376.96 kB JS).
- **Browser E2E (real Chrome, in-container + public preview URL):**
  first-visit modal; multi-select; save → instant personalized homepage;
  diabetes-note advisory; carousel auto-advance + manual controls; returning
  guest (no modal); skip + reload (no loop, no nagging); menu taste filters
  (3 shown / 7 hidden + clear); category chips; `?item=` deep-link highlight;
  dairy-exclusion flow on the public URL; authenticated live-menu mode (14
  cards); login → dashboard → products regression; 390 px mobile; **zero
  page errors**. Evidence: `docs/verification/evidence/guest-01…08.png`.

## 7. Known limitations & separately-required backend work

| Limitation | Smallest approved change needed |
|---|---|
| Anonymous guests see the dated snapshot, not live data | Permit `GET /product/productList` + `GET /category/get` anonymously (security-posture decision, needs approval) |
| Signature/popular lists are frontend-configured | `featured` flag on Product + admin update endpoint + exposure in product API |
| No live popularity data | Public order-count aggregation endpoint (or admin-curated list labelled as such) |
| Preferences are device-local (not synced per account) | Guest-profile/preferences endpoint (schema + API design needs approval) |
| Gluten/nut content unverifiable | Ingredient/allergen fields on Product + maintenance workflow |
| AI explanations not active (no endpoint) | Internal AI recommendation endpoint grounded in the real menu (see §3) |
| Backend has no product images (product table has no image column) | Image URL field + upload workflow; today the UI renders intentional category-themed tiles (no fabricated photos) |
