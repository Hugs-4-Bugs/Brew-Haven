# Issue Fix Notes — Evidence-Backed

Baseline: `main @ 14804c8` (recovery tag `recovery-point-baseline`).
All "Before" evidence below is quoted from live probes against the baseline build;
all "After" evidence is quoted from live probes against the fixed build (2026-10-21).

---

## Issue #2 — Excel import: Excel ID vs database ID mismatch + no reporting

### Root causes (baseline)
1. The live import path (`ExcelService.parseExcelFile`) required categories to pre-exist and
   silently dropped rows for unknown categories — the repository's own `ProductDetails.xlsx`
   imported **0 of 5 rows**:
   > `Uploaded file is empty or has invalid data.`
2. A second, dead implementation (`ProductServiceImpl.processExcelData`, not reachable from
   any REST path) assigned manual ids: `product.setId(newProductId++)` computed from
   `findTopByOrderByIdDesc()` — colliding with the database auto-increment (the literal
   "Excel ID does not match database ID" defect).
3. Row failures were only written to `System.err` — the client received a single generic string.
4. `private static final Map columnIndexMap` (shared mutable static state) corrupted concurrent
   imports.
5. No admin authorization on the upload endpoint; no size/row limits; status values
   (`available`) were stored raw while every product query filters `status='true'`.
6. `ProductDetails12.xlsx` contains a repeated header row mid-file (handled only by luck).

### Fix
- Import is **ID-independent by design**: any sheet `id` column is ignored; identity is
  always database-generated. The dead duplicate implementation was removed.
- Structured report (`ExcelImportResult`): total rows, imported, duplicates, failed,
  categories auto-created, `idColumnIgnored` flag, and the first 50 row-level errors
  (`{row, field, reason}`).
- Categories are auto-created by name; status is normalized (`available→true`,
  `unavailable→false`, default `true`); repeated header rows are skipped.
- Thread-safe (no shared mutable state), atomic persistence (all valid rows in one
  transaction), file-size (5 MB) and row (5000) caps, corrupt-workbook safe-fail without
  leaking exception internals, admin-only endpoint.
- Full regression suite: `ExcelServiceTest` (12 tests) incl. within-file duplicate detection
  (found live in E2E and fixed).

### After (live evidence)
```
ProductDetails.xlsx  -> {"parsed":true,"importedCount":5,"duplicateCount":0,"failedCount":0,
                         "categoriesCreated":5,"idColumnIgnored":true,"errors":[]}
ProductDetails12.xlsx-> {"parsed":true,"importedCount":5,...,"categoriesCreated":4}
re-import same file  -> {"importedCount":0,"duplicateCount":5,"failedCount":0}
DB: product ids 1..10 assigned by the DATABASE (sheet ids were 8..16)
DB: status column 'true' (sheet said 'available')
```

---

## Issue #3 — UPI: duplicate payment reports UPI ID invalid; UPI never persists

### Root causes (baseline)
1. `verifyUpi` received the RAW JSON string body **including quotes** (`"prabhat@okhdfc"`) and
   checked `endsWith("@upi")||endsWith("@ybl")` — the trailing quote made the check fail for
   EVERY input:
   > `POST /api/payment/upi/verify` `"probe@ybl"` → `❌ Invalid UPI ID`
2. Even unquoted, the suffix whitelist contradicts the NPCI VPA format (`@okhdfc`, `@paytm`, …).
3. `Payment` and `UPIPayment` BOTH declared `upiId` (Java field shadowing). Hibernate persisted
   the parent's field; the child's setter wrote the unmapped child field — **UPI ids silently
   never persisted** (schema: single nullable `upi_id` column, always NULL).
4. `CardPayment`'s `@Column(nullable=false)` card fields made every UPI/WALLET insert fail under
   single-table inheritance:
   > `POST /api/payment/upi/pay` → HTTP 500, `java.sql.SQLException: Field 'card_expiry' doesn't have a default value`
5. The 6-arg `Payment` constructor had an **empty body** — subclass super-calls set nothing.
6. The intended `unique=true` on `UPIPayment.upiId` would reject a second payment from the same
   payer (the duplicate-payment false-invalid).

### Fix
- `verifyUpi`: JSON-quote tolerant + NPCI regex (`PaymentUtils.validateUpiId`). Validation is
  purely format-based — **a valid UPI ID remains valid no matter how often it is used**; there
  is deliberately NO uniqueness on `upi_id`.
- Single consistent mapping: `upiId` declared once (parent); duplicate child field removed.
- Card columns nullable (fresh DBs) + migration SQL for existing DBs
  (`scripts/sql/V20261021_01__payment_mapping_fix.sql`) — applied and verified.
- Constructor fixed; server-authoritative payments: server-generated `TXN-<uuid>` ids,
  server timestamps, amount > 0, UPI format validation, card numbers masked to last 4
  before persistence and never returned in full (`****-****-****-1111`).
- 17 regression tests in `PaymentServiceTest` incl. `verifyUpi_validIdRemainsValidOnReuse`
  and `processPayment_upi_persistsUpiIdAndServerFields`.

### After (live evidence)
```
verifyUpi "prabhat@okhdfc"   -> UPI ID is valid          (was: ❌ Invalid)
verifyUpi "bad handle@ybl"   -> Invalid UPI ID           (format check intact)
upi/pay  #1  {upiId: prabhat@okhdfc, amount 250} -> 200 TXN-7123ec6c-…
upi/pay  #2  SAME upiId,        amount 180  -> 200 TXN-e0e3aeca-…   (duplicate-payment scenario OK)
DB payments: dtype=UPI, upi_id=prabhat@okhdfc (persisted!), server TXN ids
upi/pay garbage upiId -> 400 {"message":"Invalid UPI ID format: garbage"}
```

---

## Issue #4 — Random QR instead of the UPI-specified QR

### Root causes (baseline)
```
POST /api/payment/upi/qr-pay {upiId: prabhat@okhdfc, amount: 250}
-> https://api.qrserver.com/v1/create-qr-code/?data=upi://pay?pa=prabhat@okhdfc&am=250.0
```
1. The "QR" was a **link to a third-party service** (external dependency + payee/amount data
   leak to that service) — not a QR at all.
2. The QR encoded **whatever upiId the caller supplied** — a customer/attacker-controlled
   account, not the cafe's configured merchant.
3. Incomplete UPI intent payload (no `pn`, `cu`, `tn`; unformatted `am`).
4. No configuration existed for the merchant payee; no validation; no safe-fail.

### Fix
- QR generation is **self-contained** (ZXing on the server): returns a
  `data:image/png;base64,…` data-URI the frontend renders directly. No external service.
- The payload is a standard UPI intent that ALWAYS encodes the **server-configured merchant
  payee** (`cafe.upi.payee-vpa`, `cafe.upi.payee-name`, `cafe.upi.currency`); the request's
  upiId is intentionally NOT used as the payee (backend-authoritative recipient).
- Configuration validation + safe-fail: unset/invalid payee config → HTTP 503 with a clear
  message (verified in unit tests + live) — never a QR pointing at the wrong account.
- Verified by decoding the generated PNG (OpenCV) during live verification.

### After (live evidence)
```
qr-pay {upiId:"whatever@user", amount:250} ->
  data:image/png;base64,iVBORw0KGgo… (300x300 PNG, 635 bytes)
decoded payload: upi://pay?pa=cafe%40ybl&pn=Cafe+Management+System&am=250.00&cu=INR&tn=Cafe+order+payment
assertions: payee == configured merchant (cafe@ybl); amount == 250.00; currency == INR;
            QR identical for identical amount regardless of caller-supplied upiId
payee unconfigured -> 503 {"message":"UPI payee is not configured on the server…"}
```

---

## Collateral defects fixed during the audit (selection — full list: audit/DEFECT_REGISTER.md)

| Defect | Impact before | Fix evidence |
|---|---|---|
| changePassword stored plaintext (A.1) | user permanently locked out after password change | E2E: change → old password "Bad Credential." → new password logs in |
| forgotPassword emailed the bcrypt hash (A.3) | useless + sensitive leak | now emails SecureRandom temp password (bcrypt-persisted); honest 500 when SMTP down |
| No first-admin path (A.4) | app unusable on fresh DB | `AdminBootstrap` created admin@cafe.local on fresh DB (verified live) |
| JwtFilter shared mutable state (A.5) | cross-request role confusion | ThreadLocal + always-cleared; malformed tokens → 403 not 500 |
| Bill PDF path hardcoded to dev's Mac (B.1) | every bill 500 | configurable `cafe.bill.store-location`; PDFs generated + downloaded |
| Bill persisted despite PDF failure (B.2) | duplicate bills on retry | PDF written before bill insert; bill count stable across failures |
| GET /bill/getPdf with body (B.3) | unusable from browsers | POST /bill/getPdf added (GET kept); frontend uses POST |
| uuid path traversal (B.4) | arbitrary file read | `../../etc/passwd` → 400 |
| deleteBill no authz (B.5) | any user deletes any bill | non-admin → 401 "Unauthorized access" (verified) |
| product add/update transient Category (E2E find) | REST product add/update always 500 | managed Category; "Product Added/Updated Successfully" via UI |
| category rename without transaction (E2E find) | rename always 500 | @Transactional; "Category Updated Successfully" via UI |
| payment listing exposed to any user | IDOR-class data exposure | non-admin → 401 (verified) |
| update(user) 500 when SMTP down | status actually saved but reported failure | mail best-effort; "User Status Updated Successfully" |
| CI JDK 11 + no DB + no frontend | guaranteed red pipeline | JDK 17 + MySQL service + `mvn verify` + frontend CI workflow |
| Committed DB password / hardcoded JWT secret / hardcoded mail sender | credential leakage | env-externalized with safe local defaults |
