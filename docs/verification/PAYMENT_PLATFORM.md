# Payment & Checkout Platform — Engineering Verification Report

**Commit scope:** server-authoritative pricing, coupon system (one-to-one with orders),
provider-ready UPI/Card payments, idempotency, webhook verification, QR UX, confirmation
UI fix. Commit: see `git log` (this file ships with the change).

---

## 1. Existing payment architecture discovered (audit)

| Component | Pre-existing behavior | Gap fixed |
|---|---|---|
| `Payment` / `payments` table | record-keeping row; **amount + `status=true` were client-supplied** | amount now server-computed; `status=true` set only after verification |
| `PaymentServiceImpl` | server-generated txn ids, card masking, NPCI UPI regex, ZXing QR for the **server-configured** payee (`cafe.upi.payee-vpa`), fail-safe 503 when unconfigured | kept as-is; QR gained an amount+note overload driven by intents |
| `BillServiceImpl.generateReport` | `totalAmount` + `productDetails` fully client-supplied; PDF-then-insert (B.2); campaign attribution without discount | legacy flow untouched (POS compat); new intent flow prices server-side |
| `Campaign` / `campaign_event` | codes `BREW<id>`, PERCENT/FLAT offers, date window, usage limit, idempotent CONVERTED attribution | added `min_order_value`, `max_discount`, `max_uses_per_customer` + DB-level 1:1 index |
| SecurityConfig | everything authenticated (login/signup/forgot/error/swagger public) | added public `/checkout/webhook` (HMAC-verified instead of JWT) |
| Frontend `BillingPage` (POS) | client-side totals, raw card-number form, QR "customer paid — confirm" | server pricing + coupon APPLY + intent flow; raw card form removed (provider-hosted checkout) |
| Frontend `CheckoutDrawer` | client totals; campaign code with no APPLY, no validation, **no discount effect** | full server-priced checkout with coupon APPLY + breakdown + payment panel + receipt |

**Confirmation-screen transparency bug (root cause):** `styles.css` `:root` defines
`--surface: rgba(255,255,255,0.035)` (dark admin theme — 96.5% transparent). The guest
drawer used `background: var(--surface, #fffaf3)`; because `--surface` **is** defined,
the opaque fallback never applied and the drawer rendered nearly invisible. Fixed with
an explicit opaque `#fffaf3` surface (see `styles-guest.css`).

## 2. Files changed

**Backend — new (13):** `POJO/PaymentIntent`, `DAO/PaymentIntentDao`,
`Wrapper/PricingResult`, `Utils/payments/{PaymentProvider,ProviderOrder,RazorpayProvider,SandboxProvider}`,
`Service/{PricingService,CheckoutService,CheckoutSettlementService}` +
`ServiceImpl/{PricingServiceImpl,CheckoutServiceImpl,CheckoutSettlementServiceImpl}`,
`Rest/CheckoutRest` + `RestImpl/CheckoutRestImpl`, `Utils/DbIndexMigrator`.

**Backend — patched (9):** `Campaign` (+3 nullable rule columns), `CampaignEventDao`
(+per-customer count), `CampaignServiceImpl` (create/list accept rules), `BillService`/
`BillServiceImpl` (+`getBillById`, +`createBillFromIntent`, insertBill createdBy override),
`PaymentService`/`PaymentServiceImpl` (+QR amount+note overload), `JWT/SecurityConfig`
(+public webhook), `application.properties` (+payment config).

**Frontend:** new `api/checkout.ts`; reworked `components/guest/CheckoutDrawer.tsx`;
upgraded `pages/BillingPage.tsx`; `pages/CampaignsPage.tsx` + `api/revenue.ts`
(coupon-rule fields); `styles-guest.css` (opacity fix + checkout styles);
`vite.config.ts` (+`/checkout/` proxy). Tests: `src/tests/checkout-platform.test.tsx`.

**Backend tests (new, 54):** `PricingServiceTest` (19), `CheckoutFlowTest` (27),
`RazorpaySignatureTest` (8).

## 3. Database changes (backward-compatible, no reset)

- **new table `payment_intent`** (unique: `reference`; `(user_id, client_key)`; `bill_id`)
- **new nullable columns on `campaign`:** `min_order_value`, `max_discount`, `max_uses_per_customer`
- **new unique index `uk_campaign_event_campaign_bill (campaign_id, bill_id)`** — applied at
  startup by `DbIndexMigrator` (Hibernate ddl-auto cannot add indexes to existing tables).
  Enforces the coupon 1:1 rule at the DB level: one CONVERTED application per campaign+bill;
  NULL bill_id rows (SENT/VIEWED/CLICKED) unaffected (multiple NULLs allowed).
- Existing data preserved: 13 bills / 14 payments / 8 campaign events before == after (verified).
- Backup: `CafeMgt-pre-payment-platform-20261022.sql`; recovery tag `pre-payment-platform-ebe1986`.

## 4. Coupon implementation (server-side only)

APPLY button → `POST /checkout/price {items:[{productId,quantity}], couponCode}` →
`PricingServiceImpl` validates: exists → ACTIVE → date window → total usage limit →
per-customer limit (CONVERTED events by email) → has a PERCENT/FLAT offer → minimum order
value → computes discount (BigDecimal, HALF_UP to whole ₹, capped by `max_discount` and by
the subtotal) → returns the full breakdown. Rejection reasons are human-readable strings.

**One coupon per order (1:1):** a single `coupon_code` column on `payment_intent` AND on
`bill`; the intent is priced with at most one coupon; a second/different code cannot be
attached (same clientKey idempotent replays return the SAME intent+coupon); attribution
writes exactly one CONVERTED `campaign_event` per campaign+bill (DB-unique).
**No many-to-many exists anywhere.**

## 5. Final-price calculation (single source of truth)

`PricingService.price()` is used by checkout display, intent creation, provider order
amount, bill total and revenue attribution. The API **accepts no amount fields** — only
`{productId, quantity}`; prices always come from live `product` rows (existence + status
checked; qty merged, 1–99, ≤50 distinct items). Tax (`CAFE_PAYMENT_TAX_PERCENT`, default 0)
applies on the discounted base. Provider amount = `total × 100` paise — exact.

## 6–8. UPI / Card / QR

- **UPI (RAZORPAY mode):** intent → provider order (exact paise) → Razorpay Checkout modal
  (public `keyId` only) → handler signature verified server-side → settle.
- **UPI (SANDBOX mode):** intent → merchant QR (`/checkout/qr/{ref}`, server-configured
  payee, **server-computed amount**, reference in the note) + live status polling
  ("Waiting for payment…" → result) + clearly-labelled TEST settlement panel.
- **Card:** provider-hosted checkout (tokenized). **No raw card number/CVV is collected,
  transmitted or stored by this app** (the legacy raw-card POS form was removed).
- **Assisted/counter flows:** CASH creates the bill only (no payment record — cash is not
  provider-verified money); operator-assisted UPI requires an ADMIN.

## 9. Idempotency

- `(user_id, client_key)` UNIQUE — double-click / retry / refresh with the same key returns
  the SAME intent (SUCCESS → same receipt with `alreadyCompleted:true`).
- State transitions are conditional JPQL UPDATEs (`status IN (CREATED,PENDING)`) — a
  database-level gate: exactly one racing transaction wins; losers return the winner's result.
- `payment_intent.bill_id` UNIQUE + guarded creation — one bill per intent, ever.
- Payment rows keyed by `transaction_id = intent reference`.

## 10. Webhook / verification

`POST /checkout/webhook` is public (JWT-less by design) — authenticity via
`X-Razorpay-Signature` = HMAC-SHA256(raw body, webhook secret), constant-time compare.
`payment.captured` → idempotent settle (same path as REST confirm); `payment.failed` →
FAILED transition; unknown-but-signed events → 200 acknowledged; **invalid signature → 400**.
Client-asserted success is never trusted anywhere.

## 11. Environment variables

```
PAYMENT_PROVIDER=AUTO            # AUTO | SANDBOX | RAZORPAY (explicit RAZORPAY without keys fails startup)
PAYMENT_PROVIDER_KEY=            # Razorpay key id      (public — handed to the checkout window)
PAYMENT_PROVIDER_SECRET=         # Razorpay key secret  (SERVER ONLY — never exposed to the frontend)
PAYMENT_PROVIDER_WEBHOOK_SECRET= # HMAC secret for X-Razorpay-Signature verification
CAFE_PAYMENT_TAX_PERCENT=0       # optional tax/fee percent on the discounted base
CAFE_PAYMENT_INTENT_EXPIRY_MINUTES=15
CAFE_UPI_PAYEE_VPA=              # merchant VPA for the sandbox/assisted QR
CAFE_UPI_PAYEE_NAME=Brew Haven Cafe
```
Dev works with **zero** provider secrets (SANDBOX, no external calls). Production requires
real secrets; none are committed and none reach the frontend except the public key id.

## 12. Tests executed

- Backend: **121 unit + 1 IT = 122, 0 failures** (68 pre-existing + 54 new).
  New coverage: pricing math & every coupon rule; idempotent intent creation; server-priced
  intent (client amounts structurally impossible); coupon rejection at intent time;
  signature verify (real HMAC vectors, tamper cases); assisted-confirm authorization;
  duplicate confirm/webhook idempotency (no second bill/payment); state machine; expiry;
  cash-creates-no-payment-record.
- Frontend: **vitest 126/126 (14 files)** — incl. new money-integrity tests (requests carry
  NO amount/price/discount fields) and the coupon-APPLY flow (server breakdown renders,
  rejection reason shows, payable never changes client-side). `tsc` 0 errors; build OK.

## 13–15. Confirmation UI + honesty guarantees

- Drawer opacity bug fixed (explicit opaque surface) — verified by screenshots on desktop
  AND 390×844 mobile (`evidence-payment/*.png`).
- Receipt shows Payment Successful / Order placed (cash), order ref, amount, method,
  transaction id — only after backend-confirmed settlement.
- **No hardcoded amounts anywhere** (grep `440|₹[0-9]` in backend: none; amounts flow only
  from DB prices through PricingService). No fake success paths: SANDBOX settlement is
  hard-gated (403 when RAZORPAY active) and clearly labelled TEST.

## E2E scenarios (all passed, evidence in `download/evidence-payment/`)

| Scenario | Result |
|---|---|
| A: 2×Veggie Pizza + 1×Masala Dosa (₹537) + BREW10-class coupon | Subtotal ₹537, Discount −₹54, **Payable ₹483**; UPI sandbox settle → intent SUCCESS, bill total **483** (not 537), payment 483, CONVERTED event revenue 483 |
| B: duplicate settle + duplicate confirm | both `alreadyCompleted:true`, same billUuid; counts stayed **1 bill / 1 payment / 1 conversion** |
| C: Card with same cart+coupon | intent total 483; CARD payment row 483; bill 483 |
| D: invalid coupon | "Coupon code not found." shown, payable unchanged; failed payment → FAILED intent, **no bill created** |
| Webhook | bad/missing signature → HTTP 400 (invalid signature) |
| POS cash + coupon | ₹338 → ₹304 bill, campaign_code BREW4, **no payment row** (cash not claimed) |
