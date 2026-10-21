# Test Evidence — Final Verification Run

Date: 2026-10-21 · Environment: JDK 21 (build target 17), MariaDB 11.4.5 (MySQL 8 dialect),
Node 24, backend :8000, frontend (Vite dev) :5173.

## 1. Backend unit + integration tests

Command: `mvn verify` (surefire + failsafe)

```
ExcelServiceTest          Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
CategoryServiceTest        Tests run:  8, Failures: 0, Errors: 0, Skipped: 0
UserServiceTest            Tests run:  7, Failures: 0, Errors: 0, Skipped: 0
PaymentServiceTest         Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
CafeManagementSystem…Tests Tests run:  1, Failures: 0, Errors: 0, Skipped: 0  (context boot vs real DB)
CategoryServiceIT (failsafe)Tests run:  1, Failures: 0, Errors: 0, Skipped: 0
------------------------------------------------------------------------
TOTAL                      Tests run: 46, Failures: 0, Errors: 0  → BUILD SUCCESS
```

Baseline for comparison: `Tests run: 7, Failures: 5` (CategoryServiceTest asserted a
contract that did not exist; PaymentServiceTest was an empty class; the IT never executed).

## 2. Frontend tests + build

```
npx tsc -b --noEmit          → 0 errors (strict TypeScript)
npx vitest run               → Test Files 4 passed (4) · Tests 28 passed (28)
npm run build                → dist/assets/index-*.js 339.87 kB (111.15 kB gzip)
                               dist/assets/index-*.css 11.44 kB (3.19 kB gzip)
```

Coverage areas: API client contract shapes (every endpoint's method/path/body verified
against the backend), JWT payload decoding, string-JSON response handling, 401/403 session
handling, error message extraction, bill-line JSON-string parsing, dashboard day-bucketing,
money formatting, reduced-motion guard.

## 3. End-to-end verification (browser automation against the LIVE stack)

Screenshots: `e2e-01 … e2e-11` (workspace artifacts).

| # | Flow | Result |
|---|---|---|
| 1 | Login (admin) → dashboard with REAL data (revenue chart, best sellers from actual bills) | ✅ |
| 2 | POS: filter by category, add items, quantity controls, totals | ✅ |
| 3 | Checkout: customer form → UPI QR → QR renders (self-contained, configured merchant payee) | ✅ e2e-02 |
| 4 | QR payment confirm → duplicate-payment guard message | ✅ |
| 5 | Bill generation → uuid returned → PDF download works | ✅ e2e-03/04 |
| 6 | Bills list (admin sees all), bill detail with line items, PDF re-download | ✅ e2e-05 |
| 7 | Products: search filter, add via form (toast), edit price, status toggle, delete | ✅ e2e-06/07/08 |
| 8 | Categories: add, nested product visibility, rename | ✅ e2e-09 |
| 9 | Users: disable → user cannot log in → approve → can log in | ✅ e2e-10 |
| 10 | Excel import via browser upload: structured result card (imported/duplicates/failed, id-column-ignored flag, per-row errors), re-import dedupes | ✅ e2e-11 |
| 11 | Change password → old password rejected → new password logs in | ✅ |
| 12 | Logout → protected route redirects to /login | ✅ |
| 13 | Unapproved user → `Wait for admin approval.` | ✅ |
| 14 | Malformed token → 403 (not 500) | ✅ |
| 15 | Staff token: Excel upload 401 · bill delete 401 · payment list 401 | ✅ |

## 4. Issue-specific live evidence (summarized — full quotes in ISSUE_FIXES.md)

- **#2**: `ProductDetails.xlsx` 5/5 imported (baseline 0), DB-assigned ids 1-10 (sheet ids 8-16
  ignored), status normalized to `true`, re-import 0 imported / 5 duplicates, corrupt file →
  400 structured, oversized → 400, missing headers → 400.
- **#3**: `verifyUpi("prabhat@okhdfc")` valid (baseline: always invalid); same UPI id paid
  twice → both persist with distinct server TXN ids (baseline: 500 `Field 'card_expiry'
  doesn't have a default value`); `upi_id` visible in DB rows; invalid format → 400.
- **#4**: QR decodes (OpenCV) to `upi://pay?pa=cafe@ybl&pn=Cafe+Management+System&am=250.00&cu=INR`
  — configured merchant regardless of the caller-supplied upiId; unconfigured payee → 503.

## 5. Backup/restore rehearsal

`mysqldump` → `DROP DATABASE` → restore → login + dashboard counts + PDF download all
verified against restored data (procedure + evidence in BACKUP_RESTORE.md).

## 6. Defects found by E2E that unit tests missed (all fixed)

1. Product add/update via REST always 500 (`TransientPropertyValueException` — mocked DAO
   hid the transient-Category bug).
2. Category rename via REST always 500 (`TransactionRequiredException` — pessimistic lock
   without transaction).
3. Within-file Excel duplicates imported twice (DB dup-check ran before batch persistence).
4. `{"message":...}` response objects crashing React children rendering (typed-as-string
   API layer).
5. Vite dev proxy prefixes capturing SPA routes on hard reload (`/bill` vs `/billing`).
