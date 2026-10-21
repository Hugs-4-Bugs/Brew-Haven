# Known Limitations (honest record)

These are architectural facts of the current system, verified against the live application.
None of them are hidden behind vague wording — each states what is missing, why, and what a
remediation requires.

## Payment model

1. **Payments are not FK-linked to bills.** The backend data model has no `bill_id` on
   `payments` and no `paid` flag on `bill` — a payment and a bill are separate records
   correlated only by creation flow. Consequence: the POS guards double-payment per bill in
   the UI (payment-done latch per cart) but there is no database-level bill↔payment
   constraint. Remediation: add a nullable `bill_uuid` column + unique index to `payments`
   (data-model change, deliberately out of scope of "adapt the frontend + fix #2/#3/#4").
2. **No payment-gateway integration.** Card/UPI/wallet flows RECORD transactions; nothing
   is authorized against a real PSP. The wallet endpoint returns deep-link URLs (baseline
   contract); the QR encodes a UPI intent that the customer's UPI app settles. Real
   settlement verification requires a PSP webhook contract that does not exist here.
3. **Bill totals are client-computed.** `POST /bill/generateReport` persists the total it is
   given (baseline contract; no server-side recalculation endpoint exists). The POS computes
   totals from product prices and cannot check out without a non-empty cart.

## Email

4. **forgot-password requires SMTP.** The flow generates a one-time temporary password and
   emails it (never the stored hash — that was the bug). With SMTP unconfigured the endpoint
   returns an honest 500 (`Unable to send email right now…`) rather than claiming success.
   There is no out-of-band reset channel (no reset-token URL endpoint exists in the contract).

## Session & identity

5. **JWT 10h, no server-side revocation.** Logout is client-side token discard. Revoking a
   stolen token requires an expiry change (`JWT_SECRET` rotation) or a token denylist
   (would be a new backend component).
6. **First-admin bootstrap is config-gated.** Without `CAFE_BOOTSTRAP_ADMIN_*` and without an
   existing admin, the app logs a prominent warning and no one can approve sign-ups.

## Data & search

7. **No server-side product search/pagination contract for the main list.** `GET /product/get`
   returns the full list (the contract the backend defines — a `Page<Product>` variant exists
   at `/product/productList` but exposes raw entities without the wrapper fields the UI
   needs). The frontend searches/filters client-side, which is correct up to a few thousand
   products. A dedicated search endpoint would be a backend addition.
8. **Bill dates derive from the `BILL-<epoch-millis>` uuid.** The `bill` table has no
   created-at column in the contract; dashboard day-bucketing parses the uuid (bills created
   through any other uuid scheme would be undatable).
9. **Excel import is intentionally idempotent by (name, category).** Re-importing a file
   skips existing products and reports them as duplicates; there is no upsert (price updates
   via import would be a new behavior).
10. **Product status vocabulary is `true`/`false`.** Excel files that say
    `available/unavailable` are normalized on import (documented mapping).

## Frontend

11. **Dev-mode caveat**: `npm run dev` proxies API paths with trailing-slash prefixes
    (`/user/`, `/bill/`, …) so SPA routes (`/billing`, `/users`) are never captured. In
    production the SPA is static hosting + same-origin reverse proxy (DEPLOYMENT.md).
12. **Analytics are client-computed from real bill/product data** (the dashboard backend
    contract only provides counts). Revenue charts therefore reflect the bills the signed-in
    admin can see (`getBills` returns all bills for admins, own bills for staff).

## Explicitly out of scope (per governing requirements)

- Microservices migration (Issue #5): the monolith structure `com/inn/cafe/{Rest,RestImpl,
  Service,ServiceImpl,DAO,POJO,Wrapper,JWT,Utils,Config,Constents}` is preserved.
- GraphiQL/GraphQL: the API is REST/OpenAPI — see docs/GRAPHQL_NOTE.md.
